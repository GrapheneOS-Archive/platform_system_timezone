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

import com.android.libcore.timezone.tzlookup.proto.CountryZonesFile;
import com.google.protobuf.TextFormat;

import org.junit.Test;

import java.time.Instant;

import static com.android.libcore.timezone.tzlookup.proto.CountryZonesFile.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CountryZoneTreeTest {

    // 19700101 00:00:00 UTC
    private static final Instant START_INSTANT = Instant.EPOCH;
    // 19900101 00:00:00 UTC - in the past so the data shouldn't change.
    private static final Instant END_INSTANT = Instant.ofEpochSecond(631152000L);

    @Test
    public void testSimpleCountry() throws Exception {
        String countryText = "  isoCode:\"ad\"\n"
                + "  timeZoneMappings:<\n"
                + "    utcOffset:\"1:00\"\n"
                + "    id:\"Europe/Andorra\"\n"
                + "  >\n";
        Country country = parseCountry(countryText);
        CountryZoneTree zoneTree = CountryZoneTree.create(country, START_INSTANT, END_INSTANT);
        assertTrue(zoneTree.validateNoPriorityClashes().isEmpty());
        CountryZoneUsage zoneUsage = zoneTree.calculateCountryZoneUsage();
        assertNull(zoneUsage.getNotUsedAfterInstant("Europe/Andorra"));
    }

    @Test
    public void testCountryRequiringPriority() throws Exception {
        // This is a country that has two zones which were distinct initially but then became the
        // same. The CountryZoneTree needs a priority on one to indicate which "merged into" the
        // other. In this test it is lacking that priority.
        String countryText = "  isoCode:\"de\"\n"
                + "  defaultTimeZoneId:\"Europe/Berlin\"\n"
                + "  timeZoneMappings:<\n"
                + "    utcOffset:\"1:00\"\n"
                + "    id:\"Europe/Berlin\"\n"
                + "  >\n"
                + "  timeZoneMappings:<\n"
                + "    utcOffset:\"1:00\"\n"
                + "    id:\"Europe/Busingen\"\n"
                + "  >\n";
        Country country = parseCountry(countryText);
        CountryZoneTree zoneTree = CountryZoneTree.create(country, START_INSTANT, END_INSTANT);
        assertFalse(zoneTree.validateNoPriorityClashes().isEmpty());
        try {
            zoneTree.calculateCountryZoneUsage();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testCountryWithPriority() throws Exception {
        // This is a country that has two zones which were distinct initially but then became the
        // same. The CountryZoneTree needs a priority on one to indicate which "merged into" the
        // other. In this test one zone has the priority.
        String countryText = "  isoCode:\"de\"\n"
                + "  defaultTimeZoneId:\"Europe/Berlin\"\n"
                + "  timeZoneMappings:<\n"
                + "    utcOffset:\"1:00\"\n"
                + "    id:\"Europe/Berlin\"\n"
                + "    priority: 10\n"
                + "  >\n"
                + "  timeZoneMappings:<\n"
                + "    utcOffset:\"1:00\"\n"
                + "    id:\"Europe/Busingen\"\n"
                + "  >\n";
        Country country = parseCountry(countryText);
        CountryZoneTree zoneTree = CountryZoneTree.create(country, START_INSTANT, END_INSTANT);
        assertTrue(zoneTree.validateNoPriorityClashes().isEmpty());
        CountryZoneUsage countryZoneUsage  = zoneTree.calculateCountryZoneUsage();
        assertNull(countryZoneUsage.getNotUsedAfterInstant("Europe/Berlin"));
        Instant expectedNotUsedAfterInstant =
                Instant.ofEpochSecond(354675600); /* 1981-03-29T01:00:00Z */
        assertEquals(expectedNotUsedAfterInstant,
                countryZoneUsage.getNotUsedAfterInstant("Europe/Busingen"));
    }

    private static Country parseCountry(String text) throws Exception {
        Country.Builder builder =
                Country.newBuilder();
        TextFormat.getParser().merge(text, builder);
        return builder.build();
    }
}
