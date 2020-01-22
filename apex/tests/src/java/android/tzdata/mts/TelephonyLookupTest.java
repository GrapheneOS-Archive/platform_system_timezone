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
import static org.junit.Assert.assertNull;

import android.timezone.TelephonyLookup;
import android.timezone.TelephonyNetwork;
import android.timezone.TelephonyNetworkFinder;

import org.junit.Test;

/**
 * Functional tests for module APIs accessed via {@link TelephonyLookup} that could be affected by
 * the time zone data module.
 *
 * <p>These tests are located with the tzdata mainline module because they help to validate
 * time zone data shipped via the tzdata module. At some point in the future, the class under test
 * will be implemented by a mainline module and the test can move there.
 *
 */
public class TelephonyLookupTest {

    /*
     * TODO: Replace "assumeAtLeastR()" calls below with a standard alternative.
     */

    // Only intended for R+. This tests APIs exposed in R.
    @Test
    public void findNetworkByMccMnc() {
        assumeAtLeastR();

        TelephonyLookup telephonyLookup = TelephonyLookup.getInstance();
        TelephonyNetworkFinder telephonyNetworkFinder = telephonyLookup.getTelephonyNetworkFinder();
        assertNull(telephonyNetworkFinder.findNetworkByMccMnc("111", "111"));

        // A network known to use a different MCC from the ISO country it is used in.
        TelephonyNetwork guamNetwork = telephonyNetworkFinder.findNetworkByMccMnc("310", "370");
        assertEquals("310", guamNetwork.getMcc());
        assertEquals("370", guamNetwork.getMnc());
        assertEquals("gu", guamNetwork.getCountryIsoCode());
    }
}
