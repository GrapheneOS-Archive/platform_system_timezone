/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.libcore.timezone.tzlookup.zonetree;

import com.ibm.icu.text.TimeZoneNames;
import com.ibm.icu.util.BasicTimeZone;
import com.ibm.icu.util.TimeZoneTransition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains information about a tzdb defined time zone for a period.
 */
final class ZoneInfo {

    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 10;

    /**
     * Priority can be used to establish dominance of one zone over another if they are otherwise
     * identical for a period.
     */
    private final int priority;

    /** The zone offset periods for the zone. They are stored in ascending order of start time. */
    private final List<ZoneOffsetPeriod> zoneOffsetPeriods;

    /** The time zone ID. */
    private final String zoneId;

    private ZoneInfo(String zoneId, int priority,
            List<ZoneOffsetPeriod> zoneOffsetPeriods) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException(
                    "priority must be >=" + MIN_PRIORITY + " and <= " + MAX_PRIORITY);
        }
        this.zoneOffsetPeriods = zoneOffsetPeriods;
        this.priority = priority;
        this.zoneId = zoneId;
    }

    /** Creates a ZoneInfo using the supplied ICU data and metadata. */
    public static ZoneInfo create(TimeZoneNames timeZoneNames, BasicTimeZone timeZone, int priority,
            Instant startInclusive, Instant endExclusive) {
        List<ZoneOffsetPeriod> zoneOffsetPeriods = new ArrayList<>();

        // Start off the zone key with an artificial entry at startInclusive.
        Instant start = startInclusive;
        do {
            ZoneOffsetPeriod zoneOffsetPeriod =
                    ZoneOffsetPeriod.create(timeZoneNames, timeZone, start, endExclusive);
            zoneOffsetPeriods.add(zoneOffsetPeriod);
            start = zoneOffsetPeriod.getEndInstant();
        } while (start.isBefore(endExclusive));

        return new ZoneInfo(timeZone.getID(), priority, zoneOffsetPeriods);
    }

    public String getZoneId() {
        return zoneId;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public String toString() {
        return "ZoneInfo{" +
                "priority=" + priority +
                ", zoneId=" + zoneId +
                '}';
    }

    /**
     * Creates a hashable key object that can be used to tell if the zone is the same for a range
     * of periods.
     *
     * @param zonePeriodStartIndex the start index (inclusive)
     * @param zonePeriodEndIndex the end index (exclusive)
     */
    public ZoneOffsetPeriod.ZonePeriodsKey createZonePeriodsKey(
            int zonePeriodStartIndex, int zonePeriodEndIndex) {
        List<ZoneOffsetPeriod> periodsSubList =
                zoneOffsetPeriods.subList(zonePeriodStartIndex, zonePeriodEndIndex);
        return new ZoneOffsetPeriod.ZonePeriodsKey(periodsSubList);
    }

    /**
     * Returns a single {@link ZoneOffsetPeriod}.
     */
    public ZoneOffsetPeriod getZoneOffsetPeriod(int index) {
        return zoneOffsetPeriods.get(index);
    }

    public int getZoneOffsetPeriodCount() {
        return zoneOffsetPeriods.size();
    }
}
