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

import static com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.LISTEN_MODE_NA;
import static com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_HIGH;
import static com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_LOW;
import static com.android.timezone.geotz.provider.core.LogUtils.formatElapsedRealtimeMillis;
import static com.android.timezone.geotz.provider.core.LogUtils.formatUtcTime;

import android.os.SystemClock;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.ListenModeEnum;

import java.util.Objects;

/**
 * Holds state associated with the {@link OfflineLocationTimeZoneDelegate}'s mode. This class exists
 * to make it clear during debugging what transitions the {@link OfflineLocationTimeZoneDelegate}
 * has gone through to arrive at its current state.
 *
 * <p>This class is not thread safe: {@link OfflineLocationTimeZoneDelegate} handles all
 * synchronization.
 *
 * <p>See the docs for each {@code MODE_} constant for an explanation of each mode.
 *
 * <pre>
 * The initial mode is {@link #MODE_STOPPED}.
 *
 * Valid transitions:
 *
 * {@link #MODE_STOPPED}
 *   -> {@link #MODE_STARTED}(1)
 *       - when the LTZP first receives an started request it starts listening for the current
 *         location with {@link OfflineLocationTimeZoneDelegate#LOCATION_LISTEN_MODE_HIGH}.
 * {@link #MODE_STARTED}(1)
 *   -> {@link #MODE_STARTED}(2)
 *       - when the LTZP receives a valid location, or if it is unable to determine the current
 *         location within the mode timeout, it moves to {@link
 *         OfflineLocationTimeZoneDelegate#LOCATION_LISTEN_MODE_LOW}
 *   -> {@link #MODE_STOPPED}
 *       - when the system server sends a "stopped" request the LTZP is stopped.
 * {@link #MODE_STARTED}(2)
 *   -> {@link #MODE_STARTED}(1)
 *       - when no valid location has been received within the mode timeout, the LTZP will start
 *         listening for the current location using {@link
 *         OfflineLocationTimeZoneDelegate#LOCATION_LISTEN_MODE_HIGH}.
 *   -> {@link #MODE_STARTED}(2)
 *       - when the LTZP receives a valid location, it stays in {@link
 *         OfflineLocationTimeZoneDelegate#LOCATION_LISTEN_MODE_LOW}
 *   -> {@link #MODE_STOPPED}
 *       - when the system server sends a "stopped" request the LTZP is stopped.
 *
 * {All states}
 *   -> {@link #MODE_FAILED} (terminal state)
 *       - when there is a fatal error.
 * {Most states}
 *   -> {@link #MODE_DESTROYED} (terminal state)
 *       - when the provider's service is destroyed, perhaps as part of the current user changing
 * </pre>
 */
class Mode {

    @IntDef({ MODE_STOPPED, MODE_STARTED, MODE_FAILED, MODE_DESTROYED })
    @interface ModeEnum {}

    /**
     * An inactive state. The LTZP may not have received a request yet, or it has and the LTZP has
     * been explicitly stopped.
     */
    @ModeEnum
    static final int MODE_STOPPED = 1;

    /**
     * The LTZP has been started by the system server, and is listening for the current location.
     */
    @ModeEnum
    static final int MODE_STARTED = 2;

    /**
     * The LTZP's service has been destroyed.
     */
    @ModeEnum
    static final int MODE_DESTROYED = 3;

    /**
     * The LTZP encountered a failure it cannot recover from.
     */
    @ModeEnum
    static final int MODE_FAILED = 4;

    /** The current mode. */
    @ModeEnum
    final int mModeEnum;

    /**
     * The current location listen mode. Only used when mModeEnum == {@link #MODE_STARTED}.
     */
    final @ListenModeEnum int mListenMode;

    /**
     * Debug information: The time according to {@link SystemClock#elapsedRealtime()} when the mode
     * was created.
     */
    private final String mModeEnteredElapsedTime;

    /**
     * Debug information: The time according to {@link System#currentTimeMillis()} when the mode
     * was created.
     */
    private final String mModeEnteredSystemClockTime;

    /**
     * Debug information: Information about why the mode was entered.
     */
    @NonNull
    private final String mEntryCause;

    /**
     * Used when mModeEnum == {@link #MODE_STARTED}. The {@link Cancellable} that can be
     * used to stop listening for the current location.
     */
    @Nullable
    private Cancellable mLocationListenerCancellable;

    /**
     * Used when mModeEnum == {@link #MODE_STARTED}. The {@link Cancellable} that can be
     * used to stop listening for the current location.
     */
    @Nullable
    private Cancellable mTimeoutCancellable;

    /**
     * Used when mModeEnum == {@link #MODE_STARTED} to record the token associated with the
     * mode timeout.
     */
    @Nullable
    private String mTimeoutToken;

    Mode(@ModeEnum int modeEnum, @NonNull String entryCause) {
        this(modeEnum, entryCause, LISTEN_MODE_NA);
    }

    Mode(@ModeEnum int modeEnum, @NonNull String entryCause,
            @ListenModeEnum int listenMode) {
        mModeEnum = validateModeEnum(modeEnum);
        mListenMode = validateListenModeEnum(modeEnum, listenMode);

        // Information useful for logging / debugging.
        mModeEnteredElapsedTime = formatElapsedRealtimeMillis(SystemClock.elapsedRealtime());
        mModeEnteredSystemClockTime = formatUtcTime(System.currentTimeMillis());
        mEntryCause = entryCause;
    }

    /** Returns the stopped mode which is the starting state for a provider. */
    @NonNull
    static Mode createStoppedMode() {
        return new Mode(MODE_STOPPED, "init" /* entryCause */);
    }

    /**
     * Associates the supplied {@link Cancellable} with the mode to enable location listening to
     * be cancelled. Used when mModeEnum == {@link #MODE_STARTED}. See
     * {@link #cancelLocationListening()}.
     */
    void setLocationListenerCancellable(@NonNull Cancellable locationListenerCancellable) {
        if (mLocationListenerCancellable != null) {
            throw new IllegalStateException("mLocationListenerCancellable already set,"
                    + " current cancellable=" + mLocationListenerCancellable
                    + " new cancellable=" + locationListenerCancellable);
        }
        mLocationListenerCancellable = Objects.requireNonNull(locationListenerCancellable);
    }

    @NonNull
    Cancellable getLocationListenerCancellable() {
        return Objects.requireNonNull(mLocationListenerCancellable);
    }

    /**
     * If {@link #setLocationListenerCancellable(Cancellable)} has been called, this invokes {@link
     * Cancellable#cancel()}.
     */
    void cancelLocationListening() {
        if (mLocationListenerCancellable != null) {
            mLocationListenerCancellable.cancel();
        }
    }

    /**
     * Associates the {@code timeoutToken} with the mode for later retrieval. Used for
     * {@link #MODE_STARTED}.
     */
    void setTimeoutInfo(@NonNull Cancellable timeoutCancellable,
            @NonNull String timeoutToken) {
        if (mTimeoutCancellable != null) {
            throw new IllegalStateException("mTimeoutCancellable already set,"
                    + " current cancellable=" + mTimeoutCancellable
                    + " new cancellable=" + timeoutCancellable);
        }
        mTimeoutCancellable = Objects.requireNonNull(timeoutCancellable);
        mTimeoutToken = Objects.requireNonNull(timeoutToken);
    }

    /**
     * If {@link #setTimeoutInfo(Cancellable, String)} has been called, calls
     * {@link Cancellable#cancel()}.
     */
    void cancelTimeout() {
        if (mTimeoutCancellable != null) {
            mTimeoutCancellable.cancel();
        }
    }

    /** Returns the current timeout token, or {@code null} if there isn't one. */
    @Nullable
    String getTimeoutToken() {
        return mTimeoutToken;
    }

    @Override
    public String toString() {
        return "Mode{"
                + "mModeEnum=" + prettyPrintModeEnum(mModeEnum)
                + ", mListenMode=" + prettyPrintListenModeEnum(mListenMode)
                + ", mModeEnteredElapsedTime=" + mModeEnteredElapsedTime
                + ", mModeEnteredSystemClockTime=" + mModeEnteredSystemClockTime
                + ", mEntryCause='" + mEntryCause + '\''
                + ", mTimeoutCancellable=" + mTimeoutCancellable
                + ", mTimeoutToken=" + mTimeoutToken
                + ", mLocationListenerCancellable=" + mLocationListenerCancellable
                + '}';
    }

    /** Returns a string representation of the {@link ModeEnum} value provided. */
    static String prettyPrintModeEnum(@ModeEnum int modeEnum) {
        switch (modeEnum) {
            case MODE_STOPPED:
                return "MODE_STOPPED";
            case MODE_STARTED:
                return "MODE_STARTED";
            case MODE_DESTROYED:
                return "MODE_DESTROYED";
            case MODE_FAILED:
                return "MODE_FAILED";
            default:
                return modeEnum + " (Unknown)";
        }
    }

    /** Returns a string representation of the {@link ListenModeEnum} value provided. */
    static String prettyPrintListenModeEnum(@ListenModeEnum int listenMode) {
        switch (listenMode) {
            case LISTEN_MODE_NA:
                return "LISTEN_MODE_NA";
            case LOCATION_LISTEN_MODE_HIGH:
                return "LOCATION_LISTEN_MODE_HIGH";
            case LOCATION_LISTEN_MODE_LOW:
                return "LOCATION_LISTEN_MODE_LOW";
            default:
                return listenMode + " (Unknown)";
        }
    }

    private static @ModeEnum int validateModeEnum(@ModeEnum int modeEnum) {
        if (modeEnum < MODE_STOPPED || modeEnum > MODE_FAILED) {
            throw new IllegalArgumentException("modeEnum=" + modeEnum);
        }
        return modeEnum;
    }

    private static @ListenModeEnum int validateListenModeEnum(
            @ModeEnum int modeEnum, @ListenModeEnum int listenMode) {
        if (modeEnum == MODE_STARTED) {
            if (listenMode != LOCATION_LISTEN_MODE_HIGH && listenMode != LOCATION_LISTEN_MODE_LOW) {
                throw new IllegalArgumentException();
            }
        } else {
            if (listenMode != LISTEN_MODE_NA) {
                throw new IllegalArgumentException();
            }
        }
        return listenMode;
    }
}
