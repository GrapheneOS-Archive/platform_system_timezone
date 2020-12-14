/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.timezone.geotz.provider.core;

import static com.android.timezone.geotz.provider.core.LogUtils.formatElapsedRealtimeMillis;
import static com.android.timezone.geotz.provider.core.LogUtils.formatUtcTime;
import static com.android.timezone.geotz.provider.core.LogUtils.logDebug;
import static com.android.timezone.geotz.provider.core.LogUtils.logWarn;
import static com.android.timezone.geotz.provider.core.Mode.MODE_DESTROYED;
import static com.android.timezone.geotz.provider.core.Mode.MODE_FAILED;
import static com.android.timezone.geotz.provider.core.Mode.MODE_STARTED;
import static com.android.timezone.geotz.provider.core.Mode.MODE_STOPPED;
import static com.android.timezone.geotz.provider.core.Mode.prettyPrintListenModeEnum;

import android.location.Location;
import android.os.SystemClock;
import android.service.timezone.TimeZoneProviderSuggestion;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.timezone.geotz.lookup.GeoTimeZonesFinder;
import com.android.timezone.geotz.lookup.GeoTimeZonesFinder.LocationToken;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A class encapsulating the time zone detection logic for an Offline location-based
 * {@link android.service.timezone.TimeZoneProviderService}. It has been decoupled from the Android
 * environment and many API via the {@link Environment} interface to enable easier unit testing.
 *
 * <p>The overall goal of this class is to balance power consumption with responsiveness.
 *
 * <p>Implementation details:
 *
 * <p>The instance interacts with multiple threads, but state changes occur in a single-threaded
 * manner through the use of a lock object, {@link #mLock}.
 *
 * <p>When first started, the current location is requested using {@link
 * Environment#startLocationListening(int, Consumer)} with {@link #LOCATION_LISTEN_MODE_HIGH} and
 * a timeout is requested using {@link Environment#startTimeout(Consumer, Object, long)}.
 *
 * <p>If a valid location is found within the timeout, the time zones for the location are looked up
 * and an {@link TimeZoneProviderResult result} is recorded via {@link
 * Environment#reportTimeZoneProviderResult(TimeZoneProviderResult)}.
 *
 * <p>If a valid location cannot be found within the timeout, a {@link
 * TimeZoneProviderResult#RESULT_TYPE_UNCERTAIN} {@link TimeZoneProviderResult result} is recorded to the
 * system server.
 *
 * <p>After an {@link TimeZoneProviderResult result} has been sent, the provider restarts
 * location listening with a new timeout but in {@link #LOCATION_LISTEN_MODE_LOW}. If the current
 * location continues to be available it will stay in this mode, extending the timeout, otherwise it
 * will switch to {@link #LOCATION_LISTEN_MODE_HIGH} with a shorter timeout.
 */
public final class OfflineLocationTimeZoneDelegate {

    @IntDef({ LOCATION_LISTEN_MODE_HIGH, LOCATION_LISTEN_MODE_LOW })
    public @interface ListenModeEnum {}

    /** Use when location listen mode is not applicable. */
    @ListenModeEnum
    public static final int LISTEN_MODE_NA = 0;

    /** The most power-expensive and aggressive location detection mode. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_HIGH = 1;

    /** The least power-expensive and aggressive location detection mode. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_LOW = 2;

    /**
     * The mode timeout value to use while waiting for a location when in {@link
     * #LOCATION_LISTEN_MODE_HIGH}.
     */
    static final long LOCATION_LISTEN_MODE_HIGH_TIMEOUT_MILLIS =
            Duration.ofMinutes(5).toMillis();

    /**
     * The mode timeout value to use while waiting for a location when in {@link
     * #LOCATION_LISTEN_MODE_LOW}.
     */
    static final long LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS =
            Duration.ofMinutes(15).toMillis();

    public interface Environment {

        /**
         * Requests a callback to {@code callback} with {@code callbackToken} after at least
         * {@code delayMillis}. An object is returned that can be used to cancel the timeout later.
         */
        @NonNull
        <T> Cancellable startTimeout(@NonNull Consumer<T> callback,
                @Nullable T callbackToken, long delayMillis);

        /**
         * Starts an async location lookup. The location passed to {@code locationListener} will not
         * be {@code null}. Returns a {@link Cancellable} that can be used to stop listening.
         */
        @NonNull
        Cancellable startLocationListening(
                @ListenModeEnum int listeningMode, @NonNull Consumer<Location> locationListener);

        /**
         * Returns an object that can be used to lookup time zones for a location.
         *
         * @throws IOException if there is a problem loading the tz geolocation data files
         */
        @NonNull
        GeoTimeZonesFinder createGeoTimeZoneFinder() throws IOException;

        /**
         * Used to report location time zone information.
         */
        void reportTimeZoneProviderResult(@NonNull TimeZoneProviderResult result);

        /** See {@link SystemClock#elapsedRealtime()}. */
        long elapsedRealtimeMillis();
    }

    @NonNull
    private final Environment mEnvironment;
    private final Object mLock = new Object();

    /** The current mode of the provider. See {@link Mode} for details. */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<Mode> mCurrentMode = new ReferenceWithHistory<>(10);

    /**
     * A token associated with the last location time zone lookup. Used to avoid unnecessary time
     * zone lookups. Can be {@code null} when location is uncertain.
     */
    @GuardedBy("mLock")
    @Nullable
    private LocationToken mLastLocationToken;

    /**
     * The last time zone provider result determined by the provider. Currently used for debugging
     * only.
     */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<TimeZoneProviderResult> mLastTimeZoneProviderResult =
            new ReferenceWithHistory<>(10);

    public OfflineLocationTimeZoneDelegate(@NonNull Environment environment) {
        mEnvironment = Objects.requireNonNull(environment);

        mCurrentMode.set(Mode.createStoppedMode());
    }

    public void onBind() {
        String entryCause = "onBind() called";
        logDebug(entryCause);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_STOPPED) {
                handleUnexpectedStateTransition(
                        "onBind() called when in unexpected mode=" + currentMode);
                return;
            }

            Mode newMode = new Mode(MODE_STOPPED, entryCause);
            mCurrentMode.set(newMode);
        }
    }

    public void onDestroy() {
        String entryCause = "onDestroy() called";
        logDebug(entryCause);

        synchronized (mLock) {
            cancelTimeoutAndLocationCallbacks();

            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum == MODE_STARTED) {
                sendTimeZoneUncertainResultIfNeeded();
            }
            Mode newMode = new Mode(MODE_DESTROYED, entryCause);
            mCurrentMode.set(newMode);
        }
    }

    public void onStopUpdates() {
        String debugInfo = "onStopUpdates()";
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            switch (currentMode.mModeEnum) {
                case MODE_STOPPED: {
                    // No-op - the provider is already stopped.
                    logWarn("Unexpected onStopUpdates() when currentMode=" + currentMode);
                    break;
                }
                case MODE_STARTED: {
                    enterStoppedMode(debugInfo);
                    break;
                }
                case MODE_FAILED:
                case MODE_DESTROYED:
                default: {
                    handleUnexpectedStateTransition(
                            "Unexpected onStopUpdates() when currentMode=" + currentMode);
                    break;
                }
            }
        }

    }

    public void onStartUpdates(long initializationTimeoutMillis) {
        String debugInfo = "onStartUpdates(),"
                + " initializationTimeoutMillis=" + initializationTimeoutMillis;
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            switch (currentMode.mModeEnum) {
                case MODE_STOPPED: {
                    // Always start in the most aggressive location listening mode. The request
                    // contains the time in which the LTZP is given to provide the first
                    // result, so this is used for the first timeout.
                    enterLocationListeningMode(LOCATION_LISTEN_MODE_HIGH, debugInfo,
                            initializationTimeoutMillis);
                    break;
                }
                case MODE_STARTED: {
                    // No-op - the provider is already started.
                    logWarn("Unexpected onStarted() received when in currentMode=" + currentMode);
                    break;
                }
                case MODE_FAILED:
                case MODE_DESTROYED:
                default: {
                    handleUnexpectedStateTransition(
                            "Unexpected onStarted() received when in currentMode=" + currentMode);
                    break;
                }
            }
        }
    }

    public void dump(@NonNull PrintWriter pw) {
        synchronized (mLock) {
            pw.println("System clock=" + formatUtcTime(System.currentTimeMillis()));
            pw.println("Elapsed realtime clock="
                    + formatElapsedRealtimeMillis(mEnvironment.elapsedRealtimeMillis()));
            pw.println("mCurrentMode=" + mCurrentMode);
            pw.println("mLastLocationToken=" + mLastLocationToken);
            pw.println("mLastTimeZoneProviderResult=" + mLastTimeZoneProviderResult);
            pw.println();
            pw.println("Mode history:");
            mCurrentMode.dump(pw);
            pw.println();
            pw.println("TimeZoneProviderResult history:");
            mLastTimeZoneProviderResult.dump(pw);
        }
    }

    public int getCurrentModeEnumForTests() {
        synchronized (mLock) {
            return mCurrentMode.get().mModeEnum;
        }
    }

    /**
     * Accepts the current location when in {@link Mode#MODE_STARTED}.
     */
    private void onLocationReceived(@NonNull Location location) {
        Objects.requireNonNull(location);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();

            if (currentMode.mModeEnum != MODE_STARTED) {
                // This is not expected to happen.
                String unexpectedStateDebugInfo = "Unexpected call to onLocationReceived(),"
                        + " location=" + location
                        + ", while in mode=" + currentMode;
                handleUnexpectedCallback(unexpectedStateDebugInfo);
                return;
            }

            String debugInfo = "onLocationReceived(), location=" + location
                    + ", currentMode.mListenMode=" + prettyPrintListenModeEnum(
                            currentMode.mListenMode);
            logDebug(debugInfo);

            // A good location has been received.
            try {
                sendTimeZoneCertainResultIfNeeded(location);

                // Move to the least aggressive location listening mode.
                enterLocationListeningMode(LOCATION_LISTEN_MODE_LOW, debugInfo,
                        LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS);
            } catch (IOException e) {
                // This should never happen.
                String lookupFailureDebugInfo = "IOException while looking up location."
                        + " previous debugInfo=" + debugInfo;
                logWarn(lookupFailureDebugInfo, e);

                enterFailedMode(new IOException(lookupFailureDebugInfo, e));
            }
        }
    }

    /**
     * Handles a mode timeout. The {@code timeoutToken} is the token that was provided when the
     * timeout was scheduled.
     */
    private void onTimeout(@NonNull String timeoutToken) {
        String debugInfo = "onTimeout(), timeoutToken=" + timeoutToken;
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_STARTED) {
                handleUnexpectedCallback("Unexpected timeout for mode=" + currentMode);
                return;
            }

            if (!Objects.equals(timeoutToken, currentMode.getTimeoutToken())) {
                handleUnexpectedCallback("Timeout triggered for unknown token=" + timeoutToken);
                return;
            }

            if (currentMode.mListenMode == LOCATION_LISTEN_MODE_HIGH) {
                sendTimeZoneUncertainResultIfNeeded();
                enterLocationListeningMode(LOCATION_LISTEN_MODE_LOW, debugInfo,
                        LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS);
            } else {
                enterLocationListeningMode(LOCATION_LISTEN_MODE_HIGH, debugInfo,
                        LOCATION_LISTEN_MODE_HIGH_TIMEOUT_MILLIS);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendTimeZoneCertainResultIfNeeded(@NonNull Location location)
            throws IOException {
        try (GeoTimeZonesFinder geoTimeZonesFinder = mEnvironment.createGeoTimeZoneFinder()) {
            // Convert the location to a LocationToken.
            LocationToken locationToken = geoTimeZonesFinder.createLocationTokenForLatLng(
                    location.getLatitude(), location.getLongitude());

            // If the location token is the same as the last lookup, there is no need to do the
            // lookup / send another suggestion.
            if (locationToken.equals(mLastLocationToken)) {
                logDebug("Location token=" + locationToken + " has not changed.");
            } else {
                List<String> tzIds =
                        geoTimeZonesFinder.findTimeZonesForLocationToken(locationToken);
                logDebug("tzIds found for location=" + location + ", tzIds=" + tzIds);
                TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                        .setTimeZoneIds(tzIds)
                        .setElapsedRealtimeMillis(mEnvironment.elapsedRealtimeMillis())
                        .build();

                TimeZoneProviderResult result =
                        TimeZoneProviderResult.createSuggestion(suggestion);
                reportTimeZoneProviderResultInternal(result, locationToken);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendTimeZoneUncertainResultIfNeeded() {
        TimeZoneProviderResult lastResult = mLastTimeZoneProviderResult.get();

        // If the last result was uncertain, there is no need to send another.
        if (lastResult == null ||
                lastResult.getType() != TimeZoneProviderResult.RESULT_TYPE_UNCERTAIN) {
            TimeZoneProviderResult result = TimeZoneProviderResult.createUncertain();
            reportTimeZoneProviderResultInternal(result, null /* locationToken */);
        } else {
            logDebug("sendTimeZoneUncertainResultIfNeeded(): Last result=" + lastResult
                    + ", no need to sent another.");
        }
    }

    @GuardedBy("mLock")
    private void sendPermanentFailureResult(@NonNull Throwable cause) {
        TimeZoneProviderResult result = TimeZoneProviderResult.createPermanentFailure(cause);
        reportTimeZoneProviderResultInternal(result, null /* locationToken */);
    }

    @GuardedBy("mLock")
    private void reportTimeZoneProviderResultInternal(
            @NonNull TimeZoneProviderResult result,
            @Nullable LocationToken locationToken) {
        mLastTimeZoneProviderResult.set(result);
        mLastLocationToken = locationToken;
        mEnvironment.reportTimeZoneProviderResult(result);
    }

    @GuardedBy("mLock")
    private void clearLocationState() {
        mLastLocationToken = null;
        mLastTimeZoneProviderResult.set(null);
    }

    /** Called when leaving the current mode to cancel all pending asynchronous operations. */
    @GuardedBy("mLock")
    private void cancelTimeoutAndLocationCallbacks() {
        Mode currentMode = mCurrentMode.get();
        currentMode.cancelLocationListening();
        currentMode.cancelTimeout();
    }

    @GuardedBy("mLock")
    private void handleUnexpectedStateTransition(@NonNull String debugInfo) {
        // To help track down unexpected behavior, this fails hard.
        logWarn(debugInfo);
        throw new IllegalStateException(debugInfo);
    }

    @GuardedBy("mLock")
    private void handleUnexpectedCallback(@NonNull String debugInfo) {
        // To help track down unexpected behavior, this fails hard.
        logWarn(debugInfo);
        throw new IllegalStateException(debugInfo);
    }

    @GuardedBy("mLock")
    private void enterFailedMode(@NonNull Throwable entryCause) {
        logDebug("Provider entering failed mode, entryCause=" + entryCause);

        cancelTimeoutAndLocationCallbacks();

        sendPermanentFailureResult(entryCause);

        String failureReason = entryCause.getMessage();
        Mode newMode = new Mode(MODE_FAILED, failureReason);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void enterStoppedMode(@NonNull String entryCause) {
        logDebug("Provider entering stopped mode, entryCause=" + entryCause);

        cancelTimeoutAndLocationCallbacks();

        // Clear all location-derived state. The provider may be stopped due to the current user
        // changing.
        clearLocationState();

        Mode newMode = new Mode(MODE_STOPPED, entryCause);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void enterLocationListeningMode(
            @ListenModeEnum int listenMode,
            @NonNull String entryCause, long timeoutMillis) {
        logDebug("Provider entering location listening mode"
                + ", listenMode=" + prettyPrintListenModeEnum(listenMode)
                + ", entryCause=" + entryCause);

        Mode currentMode = mCurrentMode.get();
        currentMode.cancelTimeout();

        Mode newMode = new Mode(MODE_STARTED, entryCause, listenMode);
        if (currentMode.mModeEnum != MODE_STARTED
                || currentMode.mListenMode != listenMode) {
            currentMode.cancelLocationListening();
            Cancellable locationListenerCancellable =
                    mEnvironment.startLocationListening(listenMode, this::onLocationReceived);
            newMode.setLocationListenerCancellable(locationListenerCancellable);
        } else {
            newMode.setLocationListenerCancellable(currentMode.getLocationListenerCancellable());
        }

        scheduleTimeout(newMode, timeoutMillis);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void scheduleTimeout(@NonNull Mode mode, long delayMillis) {
        String timeoutToken = delayMillis + "@" + mEnvironment.elapsedRealtimeMillis();
        Cancellable timeoutCancellable =
                mEnvironment.startTimeout(this::onTimeout, timeoutToken, delayMillis);
        logDebug("Scheduled timeout. timeoutToken=" + timeoutToken);
        mode.setTimeoutInfo(timeoutCancellable, timeoutToken);
    }
}