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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.location.timezone.provider.LocationTimeZoneEventUnbundled;
import com.android.timezone.geotz.lookup.GeoTimeZonesFinder;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Tests for {@link OfflineLocationTimeZoneDelegate}. */
public class OfflineLocationTimeZoneDelegateTest {

    private FakeEnvironment mTestEnvironment;
    private FakeGeoTimeZonesFinder mTestGeoTimeZoneFinder;

    private OfflineLocationTimeZoneDelegate mDelegate;

    @Before
    public void setUp() {
        mTestEnvironment = new FakeEnvironment();
        mTestGeoTimeZoneFinder = mTestEnvironment.mFakeGeoTimeZonesFinder;
        mDelegate = new OfflineLocationTimeZoneDelegate(mTestEnvironment);
    }

    @Test
    public void locationFoundImmediately() throws Exception {
        double latDegrees = 1.0;
        double lngDegrees = 1.0;
        List<String> timeZoneIds = Arrays.asList("Europe/London");
        mTestGeoTimeZoneFinder.setTimeZonesForLocation(latDegrees, lngDegrees, timeZoneIds);

        assertEquals(Mode.MODE_DISABLED, mDelegate.getCurrentModeEnumForTests());
        mTestEnvironment.assertIsNotListening();
        mTestEnvironment.assertNoTimeoutSet();

        mDelegate.onBind();
        assertEquals(Mode.MODE_DISABLED, mDelegate.getCurrentModeEnumForTests());
        mTestEnvironment.assertIsNotListening();
        mTestEnvironment.assertNoTimeoutSet();

        final int initializationTimeoutMillis = 20000;
        mDelegate.onEnable(initializationTimeoutMillis);
        assertEquals(Mode.MODE_ENABLED, mDelegate.getCurrentModeEnumForTests());
        mTestEnvironment.assertIsListening(
                OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_HIGH);
        mTestEnvironment.assertTimeoutSet(initializationTimeoutMillis);

        Location location = new Location("test provider");
        location.setLatitude(latDegrees);
        location.setLongitude(1.0);
        mTestEnvironment.simulateCurrentLocationDetected(location);

        mTestEnvironment.assertLocationEventReported(timeZoneIds);
        assertEquals(Mode.MODE_ENABLED, mDelegate.getCurrentModeEnumForTests());
        mTestEnvironment.assertIsListening(
                OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_LOW);
        mTestEnvironment.assertTimeoutSet(
                OfflineLocationTimeZoneDelegate.LOCATION_LISTEN_MODE_LOW_TIMEOUT_MILLIS);
    }

    private static class FakeEnvironment implements OfflineLocationTimeZoneDelegate.Environment {

        private FakeGeoTimeZonesFinder mFakeGeoTimeZonesFinder = new FakeGeoTimeZonesFinder();
        private long mElapsedRealtimeMillis;
        private TestLocationListenerState mLocationListeningState;
        private TestTimeoutState<?> mTimeoutState;
        private LocationTimeZoneEventUnbundled mLastEvent;

        @NonNull
        @Override
        public <T> Cancellable startTimeout(@NonNull Consumer<T> callback,
                @Nullable T callbackToken, @NonNull long delayMillis) {
            assertNull(mTimeoutState);
            mTimeoutState = new TestTimeoutState<T>(callback, callbackToken, delayMillis);
            return mTimeoutState;
        }

        @NonNull
        @Override
        public Cancellable startLocationListening(int listeningMode,
                @NonNull Consumer<Location> locationListener) {
            assertNull(mLocationListeningState);
            mLocationListeningState = new TestLocationListenerState(listeningMode, locationListener);
            return mLocationListeningState;
        }

        @NonNull
        @Override
        public FakeGeoTimeZonesFinder createGeoTimeZoneFinder() throws IOException {
            return mFakeGeoTimeZonesFinder;
        }

        @Override
        public void reportLocationTimeZoneEvent(@NonNull LocationTimeZoneEventUnbundled event) {
            assertNotNull(event);
            mLastEvent = event;
        }

        @Override
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis++;
        }

        public void simulateCurrentLocationDetected(Location location) {
            assertNotNull(mLocationListeningState);
            mLocationListeningState.mLocationListener.accept(location);
        }

        public void assertIsListening(int locationListenMode) {
            assertNotNull(mLocationListeningState);
            assertEquals(locationListenMode, mLocationListeningState.mListeningMode);
        }

        public void assertIsNotListening() {
            assertNull(mLocationListeningState);
        }

        public void assertNoTimeoutSet() {
            assertNull(mTimeoutState);
        }

        public void assertTimeoutSet(long expectedTimeoutMillis) {
            assertNotNull(mTimeoutState);
            assertEquals(expectedTimeoutMillis, mTimeoutState.mDelayMillis);
        }

        public void assertLocationEventReported(List<String> expectedTimeZoneIds) {
            assertNotNull(mLastEvent);
            assertEquals(LocationTimeZoneEventUnbundled.EVENT_TYPE_SUCCESS,
                    mLastEvent.getEventType());
            assertEquals(expectedTimeZoneIds, mLastEvent.getTimeZoneIds());
        }

        private class TestLocationListenerState extends TestCancellable {
            final int mListeningMode;
            final Consumer<Location> mLocationListener;

            private TestLocationListenerState(
                    int listeningMode, Consumer<Location> locationListener) {
                mListeningMode = listeningMode;
                mLocationListener = locationListener;
            }

            @Override
            public void cancel() {
                super.cancel();
                assertSame(this, mLocationListeningState);
                mLocationListeningState = null;
            }
        }

        private class TestTimeoutState<T> extends TestCancellable {

            private final Consumer<T> mCallback;
            private final T mCallbackToken;
            private final long mDelayMillis;

            public TestTimeoutState(Consumer<T> callback, T callbackToken, long delayMillis) {
                mCallback = callback;
                mCallbackToken = callbackToken;
                mDelayMillis = delayMillis;
            }

            @Override
            public void cancel() {
                super.cancel();
                assertSame(this, mTimeoutState);
                mTimeoutState = null;
            }
        }

        private static class TestCancellable implements Cancellable {

            private boolean mCancelled;

            @Override
            public void cancel() {
                assertFalse(mCancelled);
                mCancelled = true;
            }

            public boolean isCancelled() {
                return mCancelled;
            }
        }
    }

    private static class FakeGeoTimeZonesFinder extends GeoTimeZonesFinder {

        private boolean mFailureMode;

        private static class FakeLocationToken extends LocationToken {

            private final double mLngDegrees;
            private final double mLatDegrees;

            public FakeLocationToken(double latDegrees, double lngDegrees) {
                mLatDegrees = latDegrees;
                mLngDegrees = lngDegrees;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                FakeLocationToken that = (FakeLocationToken) o;
                return Double.compare(that.mLngDegrees, mLngDegrees) == 0 &&
                        Double.compare(that.mLatDegrees, mLatDegrees) == 0;
            }

            @Override
            public int hashCode() {
                return Objects.hash(mLngDegrees, mLatDegrees);
            }

            @Override
            public String toString() {
                return "FakeLocationToken{"
                        + "mLngDegrees=" + mLngDegrees
                        + ", mLatDegrees=" + mLatDegrees
                        + '}';
            }
        }

        private final Map<LocationToken, List<String>> mTimeZoneLookup = new HashMap<>();

        @Override
        public LocationToken createLocationTokenForLatLng(double latDegrees, double lngDegrees)
                throws IOException {
            throwExceptionIfInFailureMore();
            return new FakeLocationToken(latDegrees, lngDegrees);
        }

        @Override
        public List<String> findTimeZonesForLatLng(double latDegrees, double lngDegrees)
                throws IOException {
            throwExceptionIfInFailureMore();
            return findTimeZonesForLocationToken(
                    createLocationTokenForLatLng(latDegrees, lngDegrees));
        }

        @Override
        public List<String> findTimeZonesForLocationToken(LocationToken locationToken)
                throws IOException {
            throwExceptionIfInFailureMore();
            return mTimeZoneLookup.get(locationToken);
        }

        @Override
        public void close() throws IOException {
            // No-op in the fake
        }

        public void setTimeZonesForLocation(
                double latDegrees, double lngDegrees, List<String> timeZoneIds) {
            mTimeZoneLookup.put(new FakeLocationToken(latDegrees, lngDegrees), timeZoneIds);
        }

        public void setFailureMode(boolean fail) {
            mFailureMode = fail;
        }

        private void throwExceptionIfInFailureMore() throws IOException {
            if (mFailureMode) {
                throw new IOException("Faked lookup failure");
            }
        }
    }
}
