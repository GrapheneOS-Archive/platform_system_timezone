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
package android.timezone.geolocation;

import com.android.timezone.geotz.storage.tzs2range.read.TzS2RangeFileReader;
import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2LatLng;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * An implementation of {@link GeoTimeZonesFinder} that uses {@link TzS2RangeFileReader}.
 */
public final class GeoTimeZonesFinderImpl extends GeoTimeZonesFinder {

    @Nullable
    private final TzS2RangeFileReader mTzS2RangeFileReader;

    private final int mS2Level;

    private GeoTimeZonesFinderImpl(TzS2RangeFileReader tzS2RangeFileReader, int s2Level) {
        mTzS2RangeFileReader = Objects.requireNonNull(tzS2RangeFileReader);
        mS2Level = s2Level;
    }

    /**
     * Returns a new {@link GeoTimeZonesFinderImpl} using the specified data file.
     *
     * @throws IOException in the event of a problem while reading the underlying file
     */
    // @NonNull
    public static GeoTimeZonesFinderImpl create(File file) throws IOException {
        TzS2RangeFileReader reader = TzS2RangeFileReader.open(file);
        int s2Level = reader.getS2Level();
        return new GeoTimeZonesFinderImpl(reader, s2Level);
    }

    // @NonNull
    @Override
    public LocationToken createLocationTokenForLatLng(double latDegrees, double lngDegrees) {
        return new LocationTokenImpl(getS2CellId(latDegrees, lngDegrees).id());
    }

    // @NonNull
    @Override
    public List<String> findTimeZonesForLatLng(double latDegrees, double lngDegrees)
            throws IOException {
        S2CellId cellIdAtLevel = getS2CellId(latDegrees, lngDegrees);
        return findTimeZonesForS2CellId(cellIdAtLevel.id());
    }

    // @NonNull
    @Override
    public List<String> findTimeZonesForLocationToken(LocationToken locationToken)
            throws IOException {
        if (!(locationToken instanceof LocationTokenImpl)) {
            throw new IllegalArgumentException("Unknown locationToken=" + locationToken);
        }
        LocationTokenImpl locationTokenImpl = (LocationTokenImpl) locationToken;
        return findTimeZonesForS2CellId(locationTokenImpl.getS2CellId());
    }

    // @NonNull
    private List<String> findTimeZonesForS2CellId(long s2CellId) throws IOException {
        if (mTzS2RangeFileReader == null) {
            return Collections.emptyList();
        }

        TzS2RangeFileReader.Entry entry = mTzS2RangeFileReader.findEntryByCellId(s2CellId);
        if (entry == null) {
            return Collections.emptyList();
        }
        return entry.getTzS2Range().getTzIdSet();
    }

    // @NonNull
    private S2CellId getS2CellId(double latDegrees, double lngDegrees) {
        S2CellId cellId = S2CellId.fromLatLng(S2LatLng.fromDegrees(latDegrees, lngDegrees));
        return cellId.parent(mS2Level);
    }

    @Override
    public void close() throws IOException {
        if (mTzS2RangeFileReader == null) {
            return;
        }
        mTzS2RangeFileReader.close();
    }

    private static class LocationTokenImpl extends LocationToken {

        private final long mS2CellId;

        private LocationTokenImpl(long s2CellId) {
            this.mS2CellId = s2CellId;
        }

        long getS2CellId() {
            return mS2CellId;
        }

        @Override
        public String toString() {
            return "LocationToken{"
                    + "mS2CellId=" + mS2CellId
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LocationTokenImpl that = (LocationTokenImpl) o;
            return mS2CellId == that.mS2CellId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mS2CellId);
        }
    }
}
