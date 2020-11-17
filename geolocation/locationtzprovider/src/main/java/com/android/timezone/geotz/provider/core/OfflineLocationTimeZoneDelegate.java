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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.location.Location;
import android.service.timezone.TimeZoneProviderSuggestion;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.timezone.geotz.lookup.GeoTimeZonesFinder;
import com.android.timezone.geotz.lookup.GeoTimeZonesFinder.LocationToken;
import com.android.timezone.geotz.provider.core.Environment.LocationListeningResult;
import com.android.timezone.geotz.provider.core.LocationListeningAccountant.ListeningInstruction;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
 * <p>There are two listening modes:
 * <ul>
 * <li>{@link #LOCATION_LISTEN_MODE_PASSIVE}: used most of the time and consumes a negligible amount
 * of power. It provides an indication of current location but no indication of when location is
 * unknown.</li>
 * <li>{@link #LOCATION_LISTEN_MODE_ACTIVE}: used in short bursts, is expected to be high power (may
 * use GNSS hardware), though it may also not cost much, depending on user settings. This mode
 * obtains a single location or an "unknown location" response.</li>
 * </ul>
 *
 * <p>When first started, the provider is given an initialization timeout. It is expected to produce
 * a time zone suggestion within this period. The timeout is configured and the {@link
 * #mInitializationTimeoutCancellable} field is set. The timeout is cancelled / cleared if a
 * location is determined (or if the provider is stopped). If the timeout is left to trigger, an
 * uncertain suggestion is made.
 *
 * <p>The provider starts in {@link #LOCATION_LISTEN_MODE_ACTIVE} and remains in that mode for a
 * short duration. Independently of whether a location is detected, the provider always moves from
 * {@link #LOCATION_LISTEN_MODE_ACTIVE} to {@link #LOCATION_LISTEN_MODE_PASSIVE}.
 *
 * <p>When entering {@link #LOCATION_LISTEN_MODE_PASSIVE}, a mode timeout is set. If a location is
 * detected while in {@link #LOCATION_LISTEN_MODE_PASSIVE}, the provider may stay in {@link
 * #LOCATION_LISTEN_MODE_PASSIVE}. If no location has been detected, then the provider may move into
 * into {@link #LOCATION_LISTEN_MODE_ACTIVE}.
 *
 * <p>Generally, when the location is determined, the time zones for the location are looked
 * up and an {@link TimeZoneProviderResult result} is submitted via {@link
 * Environment#reportTimeZoneProviderResult(TimeZoneProviderResult)}. When a location cannot be
 * determined and a suggestion is required, a {@link TimeZoneProviderResult#RESULT_TYPE_UNCERTAIN}
 * {@link TimeZoneProviderResult result} is submitted.
 */
public final class OfflineLocationTimeZoneDelegate {

    @IntDef({ LOCATION_LISTEN_MODE_ACTIVE, LOCATION_LISTEN_MODE_PASSIVE })
    public @interface ListenModeEnum {}

    /** Use when location listen mode is not applicable. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_NA = 0;

    /** Actively listen for a location, once, for a short period. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_ACTIVE = 1;

    /** Passively listen for a location until cancelled, possibly for a long period. */
    @ListenModeEnum
    public static final int LOCATION_LISTEN_MODE_PASSIVE = 2;

    /**
     * The ratio of how much time must part to accue a unit of active listening time.
     *
     * <p>Value detail: For every hour spent passive listening, 40 seconds of active
     * listening are allowed, i.e. 90 passive time units : 1 active time units.
     */
    private static final long PASSIVE_TO_ACTIVE_RATIO = (60 * 60) / 40;

    /** The minimum time to spend in passive listening. */
    private static final Duration MINIMUM_PASSIVE_LISTENING_DURATION = Duration.ofMinutes(2);

    /** The age before a "location not known" result is considered too stale to use. */
    private static final Duration LOCATION_NOT_KNOWN_AGE_THRESHOLD = Duration.ofMinutes(1);

    /** The age before a "location known" result is considered too stale to use. */
    private static final Duration LOCATION_KNOWN_AGE_THRESHOLD = Duration.ofMinutes(15);

    /** The shortest active listening time allowed. */
    private static final Duration MINIMUM_ACTIVE_LISTENING_DURATION = Duration.ofSeconds(5);

    /** The maximum time to stay active listening in one go. */
    private static final Duration MAXIMUM_ACTIVE_LISTENING_DURATION = Duration.ofSeconds(10);

    /** The amount of active listening budget the instance starts with. */
    private static final Duration INITIAL_ACTIVE_LISTENING_BUDGET =
            MINIMUM_ACTIVE_LISTENING_DURATION;

    /** The cap on the amount of active listening that can be accrued. */
    private static final Duration MAX_ACTIVE_LISTENING_BUDGET =
            MAXIMUM_ACTIVE_LISTENING_DURATION.multipliedBy(4);

    @NonNull
    private final Environment mEnvironment;
    private final Object mLock = new Object();
    @NonNull
    private final LocationListeningAccountant mLocationListeningAccountant;

    /** The current mode of the provider. See {@link Mode} for details. */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<Mode> mCurrentMode = new ReferenceWithHistory<>(10);

    /**
     * The last location listening result. Holds {@code null} if location listening hasn't started
     * or produced a result yet. The {@link LocationListeningResult#getLocation()} value can be
     * {@code null} when location is uncertain.
     */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<LocationListeningResult> mLastLocationListeningResult =
            new ReferenceWithHistory<>(10);

    /**
     * A token associated with the last location time zone lookup. Used to avoid unnecessary time
     * zone lookups. Can be {@code null} when location is uncertain.
     */
    @GuardedBy("mLock")
    @Nullable
    private LocationToken mLastLocationToken;

    /**
     * The last time zone provider result determined by the provider. This is used to determine
     * whether suggestions need to be made to revoke a previous suggestion. It is cleared when the
     * provider stops.
     */
    @GuardedBy("mLock")
    private final ReferenceWithHistory<TimeZoneProviderResult> mLastTimeZoneProviderResult =
            new ReferenceWithHistory<>(10);

    /**
     * Indicates whether the provider is still within its initialization period. When it times out,
     * if no suggestion has yet been made then an uncertain suggestion must be made. The reference
     * can (and must) be used to cancel the timeout if it is no longer required. The reference
     * must be cleared to indicate the initialization period is over.
     */
    @GuardedBy("mLock")
    @Nullable
    private Cancellable mInitializationTimeoutCancellable;

    @NonNull
    public static OfflineLocationTimeZoneDelegate create(@NonNull Environment environment) {
        LocationListeningAccountantImpl locationListeningAccountant =
                new LocationListeningAccountantImpl(
                        MINIMUM_PASSIVE_LISTENING_DURATION, MAX_ACTIVE_LISTENING_BUDGET,
                        MINIMUM_ACTIVE_LISTENING_DURATION, MAXIMUM_ACTIVE_LISTENING_DURATION,
                        LOCATION_NOT_KNOWN_AGE_THRESHOLD, LOCATION_KNOWN_AGE_THRESHOLD,
                        PASSIVE_TO_ACTIVE_RATIO);
        // Start with a non-zero budget for active listening so we start with a period of active
        // listening. This will be reset each time a provider is created.
        locationListeningAccountant.depositActiveListeningAmount(INITIAL_ACTIVE_LISTENING_BUDGET);

        return new OfflineLocationTimeZoneDelegate(environment, locationListeningAccountant);
    }

    // @VisibleForTesting
    OfflineLocationTimeZoneDelegate(
            @NonNull Environment environment,
            @NonNull LocationListeningAccountant locationListeningAccountant) {
        mEnvironment = Objects.requireNonNull(environment);
        mLocationListeningAccountant = Objects.requireNonNull(locationListeningAccountant);

        synchronized (mLock) {
            mCurrentMode.set(Mode.createStoppedMode());
        }
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
            cancelTimeoutsAndLocationCallbacks();

            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum == MODE_STARTED) {
                sendTimeZoneUncertainResultIfNeeded();
            }
            Mode newMode = new Mode(MODE_DESTROYED, entryCause);
            mCurrentMode.set(newMode);
        }
    }

    public void onStartUpdates(@NonNull Duration initializationTimeout) {
        Objects.requireNonNull(initializationTimeout);

        String debugInfo = "onStartUpdates(),"
                + " initializationTimeout=" + initializationTimeout;
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            switch (currentMode.mModeEnum) {
                case MODE_STOPPED: {
                    enterStartedMode(initializationTimeout, debugInfo);
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

    public void dump(@NonNull PrintWriter pw) {
        synchronized (mLock) {
            // Output useful "current time" information to help with debugging.
            pw.println("System clock=" + formatUtcTime(System.currentTimeMillis()));
            pw.println("Elapsed realtime clock="
                    + formatElapsedRealtimeMillis(mEnvironment.elapsedRealtimeMillis()));

            // State and constants.
            pw.println("mInitializationTimeoutCancellable=" + mInitializationTimeoutCancellable);
            pw.println("mActiveListeningAccountant=" + mLocationListeningAccountant);
            pw.println("mLastLocationToken=" + mLastLocationToken);
            pw.println();
            pw.println("Mode history:");
            mCurrentMode.dump(pw);
            pw.println();
            pw.println("Location history:");
            mLastLocationListeningResult.dump(pw);
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
     * Handles a {@link LocationListeningResult} from a period of active listening. The result may
     * contain a location or {@code null}.
     */
    private void onActiveListeningResult(@NonNull LocationListeningResult activeListeningResult) {
        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_STARTED
                    || currentMode.mListenMode != LOCATION_LISTEN_MODE_ACTIVE) {
                String unexpectedStateDebugInfo = "Unexpected call to onActiveListeningResult(),"
                        + " activeListeningResult=" + activeListeningResult
                        + ", currentMode=" + currentMode;
                handleUnexpectedCallback(unexpectedStateDebugInfo);
                return;
            }

            String debugInfo = "onActiveListeningResult()"
                    + ", activeListeningResult=" + activeListeningResult;
            logDebug(debugInfo);

            // Recover any active listening budget we didn't use.
            Duration timeListening = activeListeningResult.getEstimatedTimeListening();
            Duration activeListeningDuration =
                    activeListeningResult.getRequestedListeningDuration();
            Duration activeListeningDurationNotUsed = activeListeningDuration.minus(timeListening);
            if (!activeListeningDurationNotUsed.isNegative()) {
                mLocationListeningAccountant.depositActiveListeningAmount(
                        activeListeningDurationNotUsed);
            }

            // Handle the result.
            if (activeListeningResult.isLocationKnown()) {
                handleLocationKnown(activeListeningResult);
            } else {
                handleLocationNotKnown(activeListeningResult);
            }

            // Active listening returns only a single location and self-cancels so we need to start
            // listening again.
            startNextLocationListening(debugInfo);
        }
    }

    /**
     * Accepts the current location from passive listening.
     */
    private void onPassiveListeningResult(@NonNull LocationListeningResult passiveListeningResult) {
        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_STARTED
                    || currentMode.mListenMode != LOCATION_LISTEN_MODE_PASSIVE) {
                String unexpectedStateDebugInfo = "Unexpected call to onPassiveListeningResult(),"
                        + " passiveListeningResult=" + passiveListeningResult
                        + ", currentMode=" + currentMode;
                handleUnexpectedCallback(unexpectedStateDebugInfo);
                return;
            }
            logDebug("onPassiveListeningResult()"
                    + ", passiveListeningResult=" + passiveListeningResult);

            handleLocationKnown(passiveListeningResult);
        }
    }

    @GuardedBy("mLock")
    private void handleLocationKnown(@NonNull LocationListeningResult locationResult) {
        Objects.requireNonNull(locationResult);
        Objects.requireNonNull(locationResult.getLocation());

        mLastLocationListeningResult.set(locationResult);

        // Receiving a location means we will definitely send a suggestion, so the initialization
        // timeout is not required. This is a no-op if the initialization timeout is already
        // cancelled.
        cancelInitializationTimeout();

        Mode currentMode = mCurrentMode.get();
        String debugInfo = "handleLocationKnown(), locationResult=" + locationResult
                + ", currentMode.mListenMode=" + prettyPrintListenModeEnum(currentMode.mListenMode);
        logDebug(debugInfo);

        try {
            sendTimeZoneCertainResultIfNeeded(locationResult.getLocation());
        } catch (IOException e) {
            // This should never happen.
            String lookupFailureDebugInfo = "IOException while looking up location."
                    + " previous debugInfo=" + debugInfo;
            logWarn(lookupFailureDebugInfo, e);

            enterFailedMode(new IOException(lookupFailureDebugInfo, e));
        }
    }

    /**
     * Handles an explicit location not known. This can only happen with active listening; passive
     * only returns non-null locations.
     */
    @GuardedBy("mLock")
    private void handleLocationNotKnown(@NonNull LocationListeningResult locationResult) {
        Objects.requireNonNull(locationResult);
        if (locationResult.isLocationKnown()) {
            throw new IllegalArgumentException();
        }

        mLastLocationListeningResult.set(locationResult);

        Mode currentMode = mCurrentMode.get();
        String debugInfo = "handleLocationNotKnown()"
                + ", currentMode.mListenMode=" + prettyPrintListenModeEnum(currentMode.mListenMode);
        logDebug(debugInfo);

        sendTimeZoneUncertainResultIfNeeded();
    }

    /**
     * Handles a passive listening period ending naturally, i.e. not cancelled.
     *
     * @param duration the duration that listening took place for
     */
    private void onPassiveListeningEnded(@NonNull Duration duration) {
        String debugInfo = "onPassiveListeningEnded()";
        logDebug(debugInfo);

        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            if (currentMode.mModeEnum != MODE_STARTED
                    || currentMode.mListenMode != LOCATION_LISTEN_MODE_PASSIVE) {
                handleUnexpectedCallback("Unexpected call to onPassiveListeningEnded()"
                        + ", currentMode=" + currentMode);
                return;
            }

            // Track how long passive listening took place since this is what allows us to
            // actively listen.
            mLocationListeningAccountant.accrueActiveListeningBudget(duration);

            // Begin the next period of listening.
            startNextLocationListening(debugInfo);
        }
    }

    /**
     * Handles the timeout callback that fires when initialization period has elapsed without a
     * location being detected.
     */
    private void onInitializationTimeout(@NonNull String timeoutToken) {
        synchronized (mLock) {
            Mode currentMode = mCurrentMode.get();
            String debugInfo = "onInitializationTimeout() timeoutToken=" + timeoutToken
                    + ", currentMode=" + currentMode;
            logDebug(debugInfo);

            mInitializationTimeoutCancellable = null;

            // If the initialization timeout has been allowed to trigger without being cancelled
            // then that should mean no location has been detected during the initialization period
            // and the provider must declare it is uncertain.
            if (mLastTimeZoneProviderResult.get() == null) {
                TimeZoneProviderResult result = TimeZoneProviderResult.createUncertain();
                reportTimeZoneProviderResultInternal(result, null /* locationToken */);
            }
        }
    }

    /** Cancels the initialization timeout, if it is still set. */
    @GuardedBy("mLock")
    private void cancelInitializationTimeout() {
        if (mInitializationTimeoutCancellable != null) {
            mInitializationTimeoutCancellable.cancel();
            mInitializationTimeoutCancellable = null;
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
                // Rather than use the current elapsed realtime clock, use the time associated with
                // the location since that gives a more accurate answer.
                long elapsedRealtimeMillis =
                        NANOSECONDS.toMillis(location.getElapsedRealtimeNanos());
                TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                        .setTimeZoneIds(tzIds)
                        .setElapsedRealtimeMillis(elapsedRealtimeMillis)
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

        if (mInitializationTimeoutCancellable != null) {
            // If we're within the initialization timeout period, then the provider doesn't report
            // uncertainty. When the initialization timeout triggers, then an uncertain suggestion
            // will be sent if it's needed.
            return;
        }

        // If the last result was uncertain, there is no need to send another.
        if (lastResult == null ||
                lastResult.getType() != TimeZoneProviderResult.RESULT_TYPE_UNCERTAIN) {
            TimeZoneProviderResult result = TimeZoneProviderResult.createUncertain();
            reportTimeZoneProviderResultInternal(result, null /* locationToken */);
        } else {
            logDebug("sendTimeZoneUncertainResultIfNeeded(): Last result=" + lastResult
                    + ", no need to send another.");
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
        mLastLocationListeningResult.set(null);
        mLastLocationToken = null;
        mLastTimeZoneProviderResult.set(null);
    }

    /** Called when leaving the current mode to cancel all pending asynchronous operations. */
    @GuardedBy("mLock")
    private void cancelTimeoutsAndLocationCallbacks() {
        cancelInitializationTimeout();

        Mode currentMode = mCurrentMode.get();
        currentMode.cancelLocationListening();
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
    private void enterStartedMode(
            @NonNull Duration initializationTimeout, @NonNull String debugInfo) {
        Objects.requireNonNull(initializationTimeout);
        Objects.requireNonNull(debugInfo);

        // The request contains the initialization time in which the LTZP is given to provide the
        // first result. We set a timeout to try to ensure that we do send a result.
        String initializationToken = "initialization:" + initializationTimeout + "@"
                + formatElapsedRealtimeMillis(mEnvironment.elapsedRealtimeMillis());
        mInitializationTimeoutCancellable = mEnvironment.requestDelayedCallback(
                this::onInitializationTimeout, initializationToken,
                initializationTimeout);

        startNextLocationListening(debugInfo);
    }

    @GuardedBy("mLock")
    private void enterFailedMode(@NonNull Throwable entryCause) {
        logDebug("Provider entering failed mode, entryCause=" + entryCause);

        cancelTimeoutsAndLocationCallbacks();

        sendPermanentFailureResult(entryCause);

        String failureReason = entryCause.getMessage();
        Mode newMode = new Mode(MODE_FAILED, failureReason);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void enterStoppedMode(@NonNull String entryCause) {
        logDebug("Provider entering stopped mode, entryCause=" + entryCause);

        cancelTimeoutsAndLocationCallbacks();

        // Clear all location-derived state. The provider may be stopped due to the current user
        // changing.
        clearLocationState();

        Mode newMode = new Mode(MODE_STOPPED, entryCause);
        mCurrentMode.set(newMode);
    }

    @GuardedBy("mLock")
    private void startNextLocationListening(@NonNull String entryCause) {
        logDebug("Provider entering location listening mode entryCause=" + entryCause);

        Mode currentMode = mCurrentMode.get();
        // This method is safe to call on any mode, even when the mode doesn't use it.
        currentMode.cancelLocationListening();

        // Obtain the instruction for what mode to use.
        ListeningInstruction nextModeInstruction;
        try {
            // Hold a wake lock to prevent doze while the accountant does elapsed realtime millis
            // calculations for things like last location result age, etc. and start the next
            // period of listening.
            mEnvironment.acquireWakeLock();

            long elapsedRealtimeMillis = mEnvironment.elapsedRealtimeMillis();
            nextModeInstruction = mLocationListeningAccountant.getNextListeningInstruction(
                    elapsedRealtimeMillis, mLastLocationListeningResult.get());

            Cancellable listeningCancellable;
            if (nextModeInstruction.listenMode == LOCATION_LISTEN_MODE_PASSIVE) {
                listeningCancellable = mEnvironment.startPassiveLocationListening(
                        nextModeInstruction.duration,
                        this::onPassiveListeningResult,
                        this::onPassiveListeningEnded);
            } else {
                listeningCancellable = mEnvironment.startActiveGetCurrentLocation(
                        nextModeInstruction.duration, this::onActiveListeningResult);
            }
            Mode newMode = new Mode(
                    MODE_STARTED, entryCause, nextModeInstruction.listenMode, listeningCancellable);
            mCurrentMode.set(newMode);
        } finally {
            mEnvironment.releaseWakeLock();
        }
    }
}