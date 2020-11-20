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
package com.android.timezone.geotz.provider;

import static com.android.timezone.geotz.provider.core.LogUtils.formatDelayMillis;
import static com.android.timezone.geotz.provider.core.LogUtils.formatElapsedRealtimeMillis;
import static com.android.timezone.geotz.provider.core.LogUtils.logWarn;
import static com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_HIGH;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.timezone.geolocation.GeoTimeZonesFinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.location.timezone.provider.LocationTimeZoneEventUnbundled;
import com.android.timezone.geotz.provider.core.Cancellable;
import com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate;
import com.android.timezone.geotz.provider.core.OfflineLocationTimeZoneDelegate.ListenModeEnum;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The real implementation of {@link OfflineLocationTimeZoneDelegate.Environment}.
 */
class EnvironmentImpl implements OfflineLocationTimeZoneDelegate.Environment {

    // TODO(b/152746105): Make this configurable and consider the value.
    /** The maximum allowed age of locations received. */
    private static final long MAX_LAST_LOCATION_AGE_NANOS = Duration.ofMinutes(15).toNanos();

    private static final String RESOURCE_CONFIG_PROPERTIES = "offlineltzprovider.properties";
    private static final String CONFIG_KEY_GEODATA_PATH = "geodata.path";

    @NonNull
    private final LocationManager mLocationManager;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final Consumer<LocationTimeZoneEventUnbundled> mEventConsumer;
    @NonNull
    private final File mGeoDataFile;

    EnvironmentImpl(
            @NonNull Context context,
            @NonNull Consumer<LocationTimeZoneEventUnbundled> eventConsumer) {
        mLocationManager = context.getSystemService(LocationManager.class);
        mEventConsumer = Objects.requireNonNull(eventConsumer);
        mHandler = new Handler(Looper.getMainLooper());

        Properties configProperties = loadConfigProperties(getClass().getClassLoader());
        mGeoDataFile = new File(configProperties.getProperty(CONFIG_KEY_GEODATA_PATH));
    }

    private static Properties loadConfigProperties(ClassLoader classLoader) {
        Properties configProperties = new Properties();
        try (InputStream configStream =
                classLoader.getResourceAsStream(RESOURCE_CONFIG_PROPERTIES)) {
            if (configStream == null) {
                throw new IllegalStateException("Unable to find config properties"
                        + " resource=" + RESOURCE_CONFIG_PROPERTIES);
            }
            configProperties.load(configStream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load config properties from"
                    + " resource=" + RESOURCE_CONFIG_PROPERTIES, e);
        }
        return configProperties;
    }

    @Override
    @NonNull
    public <T> Cancellable startTimeout(@Nullable Consumer<T> callback, @NonNull T callbackToken,
            @NonNull  long delayMillis) {

        // Deliberate use of an anonymous class as the equality of lambdas is not well defined but
        // instance equality is required for the remove call.
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                callback.accept(callbackToken);
            }
        };

        Cancellable cancellable = new Cancellable() {
            /** Recorded for debugging: provides identity and start time. */
            final String mStartElapsedRealTime =
                    formatElapsedRealtimeMillis(SystemClock.elapsedRealtime());
            /** Recorded for debugging. */
            final String mDelay = formatDelayMillis(delayMillis);
            boolean mCancelled = false;

            @Override
            public void cancel() {
                if (!mCancelled) {
                    mHandler.removeCallbacks(runnable);
                    mCancelled = true;
                }
            }

            @Override
            public String toString() {
                return "{"
                        + "mStartElapsedRealTime=" + mStartElapsedRealTime
                        + ", mDelay=" + mDelay
                        + ", mCancelled=" + mCancelled
                        + '}';
            }
        };

        mHandler.postDelayed(runnable, delayMillis);
        return cancellable;
    }

    @Override
    @NonNull
    public Cancellable startLocationListening(
            @ListenModeEnum int listeningMode, @NonNull Consumer<Location> locationConsumer) {
        // Deliberate use of an anonymous class as the equality of lambdas is not well defined.
        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (isLocationTooOld(location)) {
                    // Old locations are filtered.
                    String badLocationDebugInfo = "Old location received: onLocationReceived()"
                            + ", location=" + location;
                    logWarn(badLocationDebugInfo);

                    return;
                }

                locationConsumer.accept(location);
            }
        };

        Cancellable locationListenerCancellable = new Cancellable() {
            /** Recorded for debugging: provides identity and start time. */
            private final String mStartElapsedRealtime =
                    formatElapsedRealtimeMillis(SystemClock.elapsedRealtime());
            private boolean mCancelled = false;

            @Override
            public void cancel() {
                if (!mCancelled) {
                    mLocationManager.removeUpdates(locationListener);
                    mCancelled = true;
                }
            }

            @Override
            public String toString() {
                return "{"
                        + "mStartElapsedRealtime=" + mStartElapsedRealtime
                        + ", mCancelled=" + mCancelled
                        + '}';
            }
        };

        // TODO(b/152746105): Make this configurable and consider the value.
        final long minTimeMs = 5 * 60 * 1000;
        final int minDistanceM = 250;

        Criteria criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(false);

        // TODO(b/152746105): Make this configurable and consider the value.
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        if (listeningMode == LOCATION_LISTEN_MODE_HIGH) {
            criteria.setPowerRequirement(Criteria.POWER_HIGH);
        } else {
            criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
        }
        criteria.setCostAllowed(false);

        // TODO(b/152746105): Move to a different API that explicitly uses the fused provider.
        mLocationManager.requestLocationUpdates(
            minTimeMs, minDistanceM, criteria, locationListener, mHandler.getLooper());
        return locationListenerCancellable;
    }

    @Override
    @NonNull
    public GeoTimeZonesFinder createGeoTimeZoneFinder() throws IOException {
        return GeoTimeZonesFinder.create(mGeoDataFile);
    }

    @Override
    public void reportLocationTimeZoneEvent(@NonNull LocationTimeZoneEventUnbundled event) {
        mEventConsumer.accept(event);
    }

    @Override
    public long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    private static boolean isLocationTooOld(@NonNull Location location) {
        return getElapsedRealtimeAgeNanos(location) > MAX_LAST_LOCATION_AGE_NANOS;
    }

    private static long getElapsedRealtimeAgeNanos(@NonNull Location location) {
        // location.getElapsedRealtimeAgeNanos() is hidden
        return SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
    }
}
