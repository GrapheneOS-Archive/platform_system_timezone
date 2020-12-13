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
import static com.android.timezone.geotz.provider.core.Mode.MODE_DISABLED;
import static com.android.timezone.geotz.provider.core.Mode.MODE_ENABLED;
import static com.android.timezone.geotz.provider.core.Mode.prettyPrintListenModeEnum;

import android.location.Location;
import android.os.SystemClock;
import android.timezone.geolocation.GeoTimeZonesFinder;
import android.timezone.geolocation.GeoTimeZonesFinder.LocationToken;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.location.timezone.provider.LocationTimeZoneEventUnbundled;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A class encapsulating the time zone detection logic for an Offline LocationTimeZoneProvider.
 * It has been decoupled from the Android environment and many API via the {@link Environment}
 * interface to enable easier unit testing.
 *
 * <p>The overall goal of this class is to balance power consumption with responsiveness.
 *
 * <p>Implementation details:
 *
 * <p>The instance interacts with multiple threads, but state changes occur in a single-threaded
 * manner through the use of a lock object, {@link #mLock}.
 *
 * <p>When first enabled, the current location is requested using {@link
 * Environment#startLocationListening(int, Consumer)} with {@link #LOCATION_LISTEN_MODE_HIGH} and
 * a timeout is requested using {@link Environment#startTimeout(Consumer, Object, long)}.
 *
 * <p>If a valid location is found within the timeout, the time zones for the location are looked up
 * and an {@link LocationTimeZoneEventUnbundled event} is sent via {@link
 * Environment#reportLocationTimeZoneEvent(LocationTimeZoneEventUnbundled)}.
 *
 * <p>If a valid location cannot be found within the timeout, a {@link
 * LocationTimeZoneEventUnbundled#EVENT_TYPE_UNCERTAIN} {@link
 * LocationTimeZoneEventUnbundled event} is sent to the system server.
 *
 * <p>After an {@link LocationTimeZoneEventUnbundled event} has been sent, the provider restarts
 * location listening with a new timeout but in {@link #LOCATION_LISTEN_MODE_LOW}. If the current
 * location continues to be available it will stay in this mode, extending the timeout, otherwise it
 * will switch to {@link #LOCATION_LISTEN_MODE_HIGH} with a shorter timeout.
 */
public final class OfflineLocationTimeZoneDelegate {

    @IntDef({ LOCATION_LISTEN_MODE_HIGH, LOCATION_LISTEN_MODE_LOW })
    public @interface ListenModeEnum {}

    /** Use when location listen mode is not applicable. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_NA = 0;

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
                @Nullable T callbackToken, @NonNull long delayMillis);

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
        void reportLocationTimeZoneEvent(@NonNull LocationTimeZoneEventUnbundled event);

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
     * The last location time zone event sent by the provider. Currently used for debugging only.
     */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<LocationTimeZoneEventUnbundled> mLastLocationTimeZoneEvent =
            new ReferenceWithHistory<>(10);

    public OfflineLocationTimeZoneDelegate(@NonNull Environment environment) {
        mEnvironment = Objects.requireNonNull(environment);

        mCurrentMode.set(Mode.createDisabledMode());
    }

    public void onBind() {
        String entryCause = "onBind() called";
        logDebug(entryCause);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_DISABLED) {
                handleUnexpectedStateTransition(
                        "onBind() called when in unexpected mode=" + currentMode);
                return;
            }

            Mode newMode = new Mode(MODE_DISABLED, entryCause);
            mCurrentMode.set(newMode);
        }
    }

    public void onDestroy() {
        String entryCause = "onDestroy() called";
        logDebug(entryCause);

        synchronized (mLock) {
            cancelTimeoutAndLocationCallbacks();

            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum == MODE_ENABLED) {
                sendTimeZoneUncertainEventIfNeeded();
            }
            Mode newMode = new Mode(MODE_DESTROYED, entryCause);
            mCurrentMode.set(newMode);
        }
    }

    public void onDisable() {
        String debugInfo = "onDisable()";
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            switch (currentMode.mModeEnum) {
                case MODE_DISABLED: {
                    // No-op - the provider is already disabled.
                    logWarn("Unexpected onDisable() when currentMode=" + currentMode);
                    break;
                }
                case MODE_ENABLED: {
                    enterDisabledMode(debugInfo);
                    break;
                }
                case MODE_FAILED:
                case MODE_DESTROYED:
                default: {
                    handleUnexpectedStateTransition(
                            "Unexpected onDisable() when currentMode=" + currentMode);
                    break;
                }
            }
        }

    }

    public void onEnable(@NonNull long initializationTimeoutMillis) {
        String debugInfo = "onEnable(), initializationTimeoutMillis=" + initializationTimeoutMillis;
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            switch (currentMode.mModeEnum) {
                case MODE_DISABLED: {
                    // Always start in the most aggressive location listening mode. The request
                    // contains the time in which the LTZP is given to provide the first
                    // event, so this is used for the first timeout.
                    enterLocationListeningMode(LOCATION_LISTEN_MODE_HIGH, debugInfo,
                            initializationTimeoutMillis);
                    break;
                }
                case MODE_ENABLED: {
                    // No-op - the provider is already enabled.
                    logWarn("Unexpected onEnabled() received when in currentMode=" + currentMode);
                    break;
                }
                case MODE_FAILED:
                case MODE_DESTROYED:
                default: {
                    handleUnexpectedStateTransition(
                            "Unexpected onEnabled() received when in currentMode=" + currentMode);
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
            pw.println("mLastLocationTimeZoneEvent=" + mLastLocationTimeZoneEvent);
            pw.println();
            pw.println("Mode history:");
            // pw.increaseIndent();
            mCurrentMode.dump(pw);
            // pw.decreaseIndent();
            pw.println();
            pw.println("LocationTimeZoneEvent history:");
            // pw.increaseIndent();
            mLastLocationTimeZoneEvent.dump(pw);
            // pw.decreaseIndent();
        }
    }

    public int getCurrentModeEnumForTests() {
        synchronized (mLock) {
            return mCurrentMode.get().mModeEnum;
        }
    }

    /**
     * Accepts the current location when in {@link Mode#MODE_ENABLED}.
     */
    private void onLocationReceived(@NonNull Location location) {
        Objects.requireNonNull(location);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();

            if (currentMode.mModeEnum != MODE_ENABLED) {
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
                sendTimeZoneCertainEventIfNeeded(location);

                // Move to the least aggressive location listening mode.
                enterLocationListeningMode(LOCATION_LISTEN_MODE_LOW, debugInfo,
                        LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS);
            } catch (IOException e) {
                // This should never happen.
                String lookupFailureDebugInfo = "IOException while looking up location."
                        + " previous debugInfo=" + debugInfo;
                logWarn(lookupFailureDebugInfo, e);

                enterFailedMode(lookupFailureDebugInfo);
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
            if (currentMode.mModeEnum != MODE_ENABLED) {
                handleUnexpectedCallback("Unexpected timeout for mode=" + currentMode);
                return;
            }

            if (!Objects.equals(timeoutToken, currentMode.getTimeoutToken())) {
                handleUnexpectedCallback("Timeout triggered for unknown token=" + timeoutToken);
                return;
            }

            if (currentMode.mListenMode == LOCATION_LISTEN_MODE_HIGH) {
                sendTimeZoneUncertainEventIfNeeded();
                enterLocationListeningMode(LOCATION_LISTEN_MODE_LOW, debugInfo,
                        LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS);
            } else {
                enterLocationListeningMode(LOCATION_LISTEN_MODE_HIGH, debugInfo,
                        LOCATION_LISTEN_MODE_HIGH_TIMEOUT_MILLIS);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendTimeZoneCertainEventIfNeeded(@NonNull Location location)
            throws IOException {
        try (GeoTimeZonesFinder geoTimeZonesFinder = mEnvironment.createGeoTimeZoneFinder()) {
            // Convert the location to a LocationToken.
            LocationToken locationToken = geoTimeZonesFinder.createLocationTokenForLatLng(
                    location.getLatitude(), location.getLongitude());

            // If the location token is the same as the last lookup, there is no need to do the
            // lookup / send another event.
            if (locationToken.equals(mLastLocationToken)) {
                logDebug("Location token=" + locationToken + " has not changed.");
            } else {
                List<String> tzIds =
                        geoTimeZonesFinder.findTimeZonesForLocationToken(locationToken);
                logDebug("tzIds found for location=" + location + ", tzIds=" + tzIds);
                LocationTimeZoneEventUnbundled event =
                        new LocationTimeZoneEventUnbundled.Builder()
                                .setEventType(LocationTimeZoneEventUnbundled.EVENT_TYPE_SUCCESS)
                                .setTimeZoneIds(tzIds)
                                .build();
                reportLocationTimeZoneEventInternal(event, locationToken);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendTimeZoneUncertainEventIfNeeded() {
        LocationTimeZoneEventUnbundled lastEvent = mLastLocationTimeZoneEvent.get();

        // If the last event was uncertain, there is no need to send another.
        if (lastEvent == null ||
                lastEvent.getEventType() != LocationTimeZoneEventUnbundled.EVENT_TYPE_UNCERTAIN) {
            LocationTimeZoneEventUnbundled event = new LocationTimeZoneEventUnbundled.Builder()
                    .setEventType(LocationTimeZoneEventUnbundled.EVENT_TYPE_UNCERTAIN)
                    .build();
            reportLocationTimeZoneEventInternal(event, null /* locationToken */);
        } else {
            logDebug("sendTimeZoneUncertainEventIfNeeded(): Last event=" + lastEvent
                    + ", no need to sent another.");
        }
    }

    @GuardedBy("mLock")
    private void sendPermanentFailureEvent() {
        LocationTimeZoneEventUnbundled event = new LocationTimeZoneEventUnbundled.Builder()
                .setEventType(LocationTimeZoneEventUnbundled.EVENT_TYPE_PERMANENT_FAILURE)
                .build();
        reportLocationTimeZoneEventInternal(event, null /* locationToken */);
    }

    @GuardedBy("mLock")
    private void reportLocationTimeZoneEventInternal(
            @NonNull LocationTimeZoneEventUnbundled event,
            @Nullable LocationToken locationToken) {
        mLastLocationTimeZoneEvent.set(event);
        mLastLocationToken = locationToken;
        mEnvironment.reportLocationTimeZoneEvent(event);
    }

    @GuardedBy("mLock")
    private void clearLocationState() {
        mLastLocationToken = null;
        mLastLocationTimeZoneEvent.set(null);
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
    private void enterFailedMode(@NonNull String entryCause) {
        logDebug("Provider entering failed mode, entryCause=" + entryCause);

        cancelTimeoutAndLocationCallbacks();

        sendPermanentFailureEvent();

        Mode newMode = new Mode(MODE_FAILED, entryCause);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void enterDisabledMode(@NonNull String entryCause) {
        logDebug("Provider entering disabled mode, entryCause=" + entryCause);

        cancelTimeoutAndLocationCallbacks();

        // Clear all location-derived state. The provider may be disabled due to the current user
        // changing.
        clearLocationState();

        Mode newMode = new Mode(MODE_DISABLED, entryCause);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void enterLocationListeningMode(
            @ListenModeEnum int listenMode,
            @NonNull String entryCause, @NonNull long timeoutMillis) {
        logDebug("Provider entering location listening mode"
                + ", listenMode=" + prettyPrintListenModeEnum(listenMode)
                + ", entryCause=" + entryCause);

        Mode currentMode = mCurrentMode.get();
        currentMode.cancelTimeout();

        Mode newMode = new Mode(MODE_ENABLED, entryCause, listenMode);
        if (currentMode.mModeEnum != MODE_ENABLED
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