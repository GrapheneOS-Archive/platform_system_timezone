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

package com.android.timezone.tzids;

import static java.time.ZoneOffset.UTC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.android.timezone.tzids.proto.TzIdsProto;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.HashMap;
import java.util.Map;

/** Tests for {@link TimeZoneIds}. */
public final class TimeZoneIdsTest {

    @Test
    public void getCountryIdMap_links() throws Exception {
        TzIdsProto.TimeZoneIds.Builder tzIdsBuilder = TzIdsProto.TimeZoneIds.newBuilder();

        TzIdsProto.CountryMapping gb = TzIdsProto.CountryMapping.newBuilder()
                .setIsoCode("gb")
                .addTimeZoneIds("Europe/London")
                .addTimeZoneLinks(createLink("GB", "Europe/London"))
                .build();
        tzIdsBuilder.addCountryMappings(gb);

        TimeZoneIds tzIds = new TimeZoneIds(tzIdsBuilder.build());

        Map<String, String> expectedMap = new HashMap<>();
        expectedMap.put("Europe/London", "Europe/London");
        expectedMap.put("GB", "Europe/London");

        assertEquals(expectedMap, tzIds.getCountryIdMap("Gb", Instant.EPOCH));
        assertEquals(expectedMap, tzIds.getCountryIdMap("GB", Instant.EPOCH));
        assertEquals(expectedMap, tzIds.getCountryIdMap("gB", Instant.EPOCH));

        assertEquals(expectedMap, tzIds.getCountryIdMap("gb", Instant.MIN));
        assertEquals(expectedMap, tzIds.getCountryIdMap("gb", Instant.EPOCH));
        assertEquals(expectedMap, tzIds.getCountryIdMap("gb", Instant.MAX));
    }

    @Test
    public void getCountryIdMap_replacements() throws Exception {
        TzIdsProto.TimeZoneIds.Builder tzIdsBuilder = TzIdsProto.TimeZoneIds.newBuilder();

        // A much-simplified version of the US time zone IDs.
        Instant boiseFrom = LocalDateTime.of(1974, Month.FEBRUARY, 3, 9, 0).toInstant(UTC);
        Instant dakotaFrom = LocalDateTime.of(1992, Month.OCTOBER, 25, 8, 0).toInstant(UTC);
        TzIdsProto.CountryMapping us = TzIdsProto.CountryMapping.newBuilder()
                .setIsoCode("us")
                .addTimeZoneIds("America/Phoenix")
                .addTimeZoneIds("America/Chicago")
                .addTimeZoneReplacements(
                        createReplacement("America/Boise", "America/Phoenix", boiseFrom))
                .addTimeZoneReplacements(
                        createReplacement(
                                "America/North_Dakota/Center", "America/Chicago", dakotaFrom))
                .build();
        tzIdsBuilder.addCountryMappings(us);

        TimeZoneIds tzIds = new TimeZoneIds(tzIdsBuilder.build());

        Map<String, String> baseExpectedMap = new HashMap<>();
        baseExpectedMap.put("America/Phoenix", "America/Phoenix");
        baseExpectedMap.put("America/Chicago", "America/Chicago");

        // Before all replacements in effect.
        {
            Map<String, String> expectedMap = new HashMap<>(baseExpectedMap);
            expectedMap.put("America/Boise", "America/Boise");
            expectedMap.put("America/North_Dakota/Center", "America/North_Dakota/Center");

            assertEquals(expectedMap, tzIds.getCountryIdMap("us", Instant.EPOCH));
            assertEquals(expectedMap, tzIds.getCountryIdMap("us", boiseFrom.minusMillis(1)));
        }

        // One replacement in effect.
        {
            Map<String, String> expectedMap = new HashMap<>(baseExpectedMap);
            expectedMap.put("America/Boise", "America/Phoenix");
            expectedMap.put("America/North_Dakota/Center", "America/North_Dakota/Center");

            assertEquals(expectedMap, tzIds.getCountryIdMap("us", boiseFrom));
            assertEquals(expectedMap, tzIds.getCountryIdMap("us", dakotaFrom.minusMillis(1)));
        }

        // All replacements in effect.
        {
            Map<String, String> expectedMap = new HashMap<>(baseExpectedMap);
            expectedMap.put("America/Boise", "America/Phoenix");
            expectedMap.put("America/North_Dakota/Center", "America/Chicago");

            assertEquals(expectedMap, tzIds.getCountryIdMap("us", dakotaFrom));
            assertEquals(expectedMap,
                    tzIds.getCountryIdMap("us", Instant.ofEpochMilli(Long.MAX_VALUE)));
        }
    }

    @Test
    public void getCountryCodeForZoneId() {
        TzIdsProto.TimeZoneIds.Builder tzIdsBuilder = TzIdsProto.TimeZoneIds.newBuilder();

        TzIdsProto.CountryMapping gb = TzIdsProto.CountryMapping.newBuilder()
                .setIsoCode("gb")
                .addTimeZoneIds("Europe/London")
                .addTimeZoneLinks(createLink("GB", "Europe/London"))
                .build();
        tzIdsBuilder.addCountryMappings(gb);

        Instant boiseFrom = LocalDateTime.of(1974, Month.FEBRUARY, 3, 9, 0).toInstant(UTC);
        TzIdsProto.CountryMapping us = TzIdsProto.CountryMapping.newBuilder()
                .setIsoCode("us")
                .addTimeZoneIds("America/Phoenix")
                .addTimeZoneLinks(createLink("US/Arizona", "America/Phoenix"))
                .addTimeZoneReplacements(
                        createReplacement("America/Boise", "America/Phoenix", boiseFrom))
                .build();
        tzIdsBuilder.addCountryMappings(us);

        TimeZoneIds tzIds = new TimeZoneIds(tzIdsBuilder.build());
        assertNull(tzIds.getCountryCodeForZoneId("FooBar"));
        assertEquals("gb", tzIds.getCountryCodeForZoneId("GB"));
        assertEquals("gb", tzIds.getCountryCodeForZoneId("Europe/London"));
        assertEquals("us", tzIds.getCountryCodeForZoneId("America/Phoenix"));
        assertEquals("us", tzIds.getCountryCodeForZoneId("US/Arizona"));
        assertEquals("us", tzIds.getCountryCodeForZoneId("America/Boise"));
    }

    private static TzIdsProto.TimeZoneLink createLink(
            String alternativeId, String preferredId) {
        return TzIdsProto.TimeZoneLink.newBuilder()
                .setAlternativeId(alternativeId)
                .setPreferredId(preferredId)
                .build();
    }

    private static TzIdsProto.TimeZoneReplacement createReplacement(String replacedId,
            String replacementId, Instant from) {
        return TzIdsProto.TimeZoneReplacement.newBuilder()
                .setReplacedId(replacedId)
                .setReplacementId(replacementId)
                .setFromMillis(from.toEpochMilli())
                .build();
    }
}
