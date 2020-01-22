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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.timezone.TimeZoneFinder;
import android.timezone.TzDataSetVersion;
import android.timezone.ZoneInfoDb;
import android.util.TimeUtils;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests concerning version information associated with, or affected by, the time zone data module.
 *
 * <p>Generally we don't want to assert anything too specific here (like exact version), since that
 * would mean more to update every tzdb release. Also, if the module being tested incorrectly
 * contains an old version, then why wouldn't the tests be just as old too?
 */
public class TimeZoneVersionTest {

    private static final File TIME_ZONE_MODULE_VERSION_FILE =
            new File("/apex/com.android.tzdata/etc/tz/tz_version");

    @Before
    public void checkKnownRelease() {
        MtsTestSupport.assertKnownRelease();
    }

    @Test
    public void timeZoneModuleIsCompatibleWithThisDevice() throws Exception {
        if (MtsTestSupport.isQ()) {
            String majorVersion = readMajorFormatVersionFromModuleVersionFile();
            // Q is 3.x.
            assertEquals("003", majorVersion);
        } else {
            // R and above: APIs are exposed that enable the device to do its own checks.
            TzDataSetVersion tzDataSetVersion = TzDataSetVersion.read();
            assertTrue(TzDataSetVersion.isCompatibleWithThisDevice(tzDataSetVersion));

            // But we can assert some things without being too specific. This also exercises methods
            // we may need in the future if we do want to be more prescriptive.
            // These assertions are for the latest release at the time of writing.
            assertTrue(tzDataSetVersion.getRulesVersion().compareTo("2019c") >= 0);
            assertTrue(tzDataSetVersion.getFormatMajorVersion() >= 4);
            assertTrue(tzDataSetVersion.getFormatMinorVersion() >= 1);
            assertTrue(tzDataSetVersion.getRevision() >= 1);

            assertEquals(tzDataSetVersion.getFormatMajorVersion(),
                    TzDataSetVersion.currentFormatMajorVersion());
            assertTrue(tzDataSetVersion.getFormatMinorVersion()
                    >= TzDataSetVersion.currentFormatMinorVersion());
        }
    }

    /**
     * Confirms that tzdb version information available via published APIs is consistent.
     */
    @Test
    public void tzdbVersionIsConsistentAcrossApis() throws Exception {
        String icu4jTzVersion = android.icu.util.TimeZone.getTZDataVersion();
        assertEquals(icu4jTzVersion, TimeUtils.getTimeZoneDatabaseVersion());

        // NOTE: In Q and R *in general*, there's no guarantee that the tzdata module data isn't
        // overridden by /data files from a time zone data APK. However, MTS tests are not expected
        // to run against such devices. So, this test is opinionated that the various APIs must
        // report the version from the tzdata mainline module.
        String tzModuleTzdbVersion;
        if (MtsTestSupport.isQ()) {
            tzModuleTzdbVersion = readTzDbVersionFromModuleVersionFile();
        } else {
            // The device must be R or above.

            TzDataSetVersion tzDataSetVersion = TzDataSetVersion.read();
            tzModuleTzdbVersion = tzDataSetVersion.getRulesVersion();

            // Check additional APIs that were added in R match ICU4J while we're here.
            String javaUtilVersion = ZoneInfoDb.getInstance().getVersion();
            assertEquals(icu4jTzVersion, javaUtilVersion);

            String tzLookupTzVersion = TimeZoneFinder.getInstance().getIanaVersion();
            assertEquals(icu4jTzVersion, tzLookupTzVersion);
        }
        assertEquals(icu4jTzVersion, tzModuleTzdbVersion);
    }

    /**
     * Reads up to {@code maxBytes} bytes from the specified file. The returned array can be
     * shorter than {@code maxBytes} if the file is shorter.
     */
    private static byte[] readBytes(File file, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes ==" + maxBytes);
        }

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] max = new byte[maxBytes];
            int bytesRead = in.read(max, 0, maxBytes);
            byte[] toReturn = new byte[bytesRead];
            System.arraycopy(max, 0, toReturn, 0, bytesRead);
            return toReturn;
        }
    }

    private static String readTzDbVersionFromModuleVersionFile() throws IOException {
        byte[] versionBytes = readBytes(TIME_ZONE_MODULE_VERSION_FILE, 13);
        assertEquals(13, versionBytes.length);

        String versionString = new String(versionBytes, StandardCharsets.US_ASCII);
        // Format is: xxx.yyy|zzzzz|...., we want zzzzz
        String[] dataSetVersionComponents = versionString.split("\\|");
        return dataSetVersionComponents[1];
    }

    private static String readMajorFormatVersionFromModuleVersionFile() throws IOException {
        byte[] versionBytes = readBytes(TIME_ZONE_MODULE_VERSION_FILE, 7);
        assertEquals(7, versionBytes.length);

        String versionString = new String(versionBytes, StandardCharsets.US_ASCII);
        // Format is: xxx.yyy|zzzz|.... we want xxx
        String[] dataSetVersionComponents = versionString.split("\\.");
        return dataSetVersionComponents[0];
    }
}
