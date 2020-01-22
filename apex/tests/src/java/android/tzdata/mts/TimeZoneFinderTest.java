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
package android.tzdata.mts;

import static android.tzdata.mts.MtsTestSupport.assumeAtLeastR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.icu.util.TimeZone;
import android.timezone.CountryTimeZones;
import android.timezone.TimeZoneFinder;

import org.junit.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Functional tests for module APIs accessed via {@link TimeZoneFinder} that could be affected by
 * the time zone data module.
 *
 * <p>These tests are located with the tzdata mainline module because they help to validate
 * time zone data shipped via the tzdata module. At some point in the future, the class under test
 * will be implemented by a mainline module and the test can move there.
 */
public class TimeZoneFinderTest {

    /*
     * TODO: Replace "assumeAtLeastR()" calls below with a standard alternative.
     */

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void getIanaVersion() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();
        assertNotNull(timeZoneFinder.getIanaVersion());
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void lookupCountryTimeZones_caseInsensitive() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        CountryTimeZones lowerCaseResult = timeZoneFinder.lookupCountryTimeZones("gb");
        assertEquals(lowerCaseResult, timeZoneFinder.lookupCountryTimeZones("GB"));
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void lookupCountryTimeZones_unknown() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();
        assertNull(timeZoneFinder.lookupCountryTimeZones("zz"));
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_gb() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a single-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("gb");
        assertNotNull(countryTimeZones);
        assertTrue(countryTimeZones.hasUtcZone(midnightUtcMillis(2020, 1, 1)));
        assertFalse(countryTimeZones.isDefaultTimeZoneBoosted());
        assertEquals("Europe/London", countryTimeZones.getDefaultTimeZoneId());
        assertEquals(timeZone("Europe/London"), countryTimeZones.getDefaultTimeZone());

        assertTrue(countryTimeZones.isForCountryCode("gb"));
        assertTrue(countryTimeZones.isForCountryCode("GB"));
        assertTrue(countryTimeZones.isForCountryCode("gB"));
        assertTrue(countryTimeZones.isForCountryCode("Gb"));
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_getEffectiveMappingsAt_gb() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a single-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("gb");
        assertNotNull(countryTimeZones);

        List<CountryTimeZones.TimeZoneMapping> timeZoneMappings =
                countryTimeZones.getEffectiveTimeZoneMappingsAt(midnightUtcMillis(2020, 1, 1));
        assertEquals(1, timeZoneMappings.size());

        CountryTimeZones.TimeZoneMapping timeZoneMapping = timeZoneMappings.get(0);
        assertEquals("Europe/London", timeZoneMapping.getTimeZoneId());
        assertEquals(timeZone("Europe/London"), timeZoneMapping.getTimeZone());
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_lookupByOffsetWithBias_gb() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a single-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("gb");
        assertNotNull(countryTimeZones);

        // No match.
        {
            int utcOffsetHours = 5;
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(countryTimeZones,
                    utcOffsetHours, null);
            assertNull(offsetResult);
        }

        // Single match, no bias
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(countryTimeZones, 0,
                    null);
            assertNotNull(offsetResult);
            assertEquals(timeZone("Europe/London"), offsetResult.getTimeZone());
            assertTrue(offsetResult.isOnlyMatch());
        }

        // Single match, with non-matching bias.
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(countryTimeZones, 0,
                    timeZone("Europe/Paris"));
            assertNotNull(offsetResult);
            assertEquals(timeZone("Europe/London"), offsetResult.getTimeZone());
            assertTrue(offsetResult.isOnlyMatch());
        }
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_us() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a multi-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("us");
        assertNotNull(countryTimeZones);
        assertFalse(countryTimeZones.hasUtcZone(midnightUtcMillis(2020, 1, 1)));
        assertFalse(countryTimeZones.isDefaultTimeZoneBoosted());
        String expectedZoneId = "America/New_York";
        assertEquals(expectedZoneId, countryTimeZones.getDefaultTimeZoneId());
        assertEquals(timeZone(expectedZoneId), countryTimeZones.getDefaultTimeZone());

        assertTrue(countryTimeZones.isForCountryCode("us"));
        assertTrue(countryTimeZones.isForCountryCode("US"));
        assertTrue(countryTimeZones.isForCountryCode("uS"));
        assertTrue(countryTimeZones.isForCountryCode("Us"));
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_getEffectiveMappingsAt_us() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a multi-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("us");
        assertNotNull(countryTimeZones);

        List<CountryTimeZones.TimeZoneMapping> timeZoneMappings =
                countryTimeZones.getEffectiveTimeZoneMappingsAt(midnightUtcMillis(2020, 1, 1));

        Set<String> expectedZoneIds = set(
                "America/New_York",
                "America/Chicago",
                "America/Denver",
                "America/Phoenix",
                "America/Los_Angeles",
                "America/Anchorage",
                "Pacific/Honolulu",
                "America/Adak");

        Set<String> actualZoneIds = new HashSet<>();
        timeZoneMappings.forEach(i -> {
            actualZoneIds.add(i.getTimeZoneId());
        });
        assertEquals(expectedZoneIds, actualZoneIds);
    }

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void countryTimeZones_lookupByOffsetWithBias_us() {
        assumeAtLeastR();

        TimeZoneFinder timeZoneFinder = TimeZoneFinder.getInstance();

        // Assert results for a multi-zone country that doesn't change very often.
        CountryTimeZones countryTimeZones = timeZoneFinder.lookupCountryTimeZones("us");
        assertNotNull(countryTimeZones);

        // No match.
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(
                    countryTimeZones, 5 /* utcOffsetHours */, null /* bias */);
            assertNull(offsetResult);
        }

        // Single match, no bias
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(
                    countryTimeZones, -8 /* utcOffsetHours */, null /* bias */);
            assertNotNull(offsetResult);
            assertEquals(timeZone("America/Los_Angeles"), offsetResult.getTimeZone());
            assertTrue(offsetResult.isOnlyMatch());
        }

        // Single match, with non-matching bias.
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(
                    countryTimeZones, -8 /* utcOffsetHours */,
                    timeZone("Europe/London") /* bias */);
            assertNotNull(offsetResult);
            assertEquals(timeZone("America/Los_Angeles"), offsetResult.getTimeZone());
            assertTrue(offsetResult.isOnlyMatch());
        }

        // Multiple match. No bias.
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(
                    countryTimeZones, -7 /* utcOffsetHours */, null /* bias */);
            assertNotNull(offsetResult);
            TimeZone actualTimeZone = offsetResult.getTimeZone();
            assertTrue(set("America/Denver", "America/Arizona").contains(actualTimeZone.getID()));
            assertFalse(offsetResult.isOnlyMatch());
        }

        // Multiple match. With bias.
        {
            CountryTimeZones.OffsetResult offsetResult =
                    lookup1Jan2020Offset(countryTimeZones, -7 /* utcOffsetHours */,
                            timeZone("America/Denver") /* bias */);
            assertNotNull(offsetResult);
            assertEquals("America/Denver", offsetResult.getTimeZone().getID());
            assertFalse(offsetResult.isOnlyMatch());
        }
        {
            CountryTimeZones.OffsetResult offsetResult = lookup1Jan2020Offset(
                    countryTimeZones, -7 /* utcOffsetHours */,
                    timeZone("America/Phoenix") /* bias */);
            assertNotNull(offsetResult);
            assertEquals("America/Phoenix", offsetResult.getTimeZone().getID());
            assertFalse(offsetResult.isOnlyMatch());
        }
    }

    private static CountryTimeZones.OffsetResult lookup1Jan2020Offset(
            CountryTimeZones countryTimeZones, int utcOffsetHours, TimeZone bias) {
        return countryTimeZones.lookupByOffsetWithBias(
                hourOffsetMillis(utcOffsetHours) /* totalOffsetMillis */,
                null /* isDst */,
                null /* dstOffsetMillis */,
                midnightUtcMillis(2020, 1, 1) /* whenMillis */,
                bias);
    }

    private static int hourOffsetMillis(int hours) {
        return (int) Duration.ofHours(hours).toMillis();
    }

    private static Set<String> set(String... strings) {
        return new HashSet<>(Arrays.asList(strings));
    }

    private static long midnightUtcMillis(int year, int month, int dayOfMonth) {
        LocalDateTime localDateTime = LocalDateTime.of(year, month, dayOfMonth, 0, 0);
        return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static TimeZone timeZone(String expectedZoneId) {
        return TimeZone.getTimeZone(expectedZoneId);
    }
}
