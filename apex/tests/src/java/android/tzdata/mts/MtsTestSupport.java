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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;

import androidx.core.os.BuildCompat;

/**
 * General tzdata module utility methods used from several tests.
 */
class MtsTestSupport {
    private MtsTestSupport() {}

    /**
     * Returns true if the device release is exactly Q, excluding unfinished R devices that may
     * still report a Q API level.
     */
    static boolean isQ() {
        return BuildCompat.isAtLeastQ() && !BuildCompat.isAtLeastR();
    }

    /** Returns true if the device is an unfinished R or bona fide R device. */
    static boolean isAtLeastR() {
        return BuildCompat.isAtLeastR();
    }

    /** Asserts the device is running one of the known Android releases. */
    static void assertKnownRelease() {
        assertTrue("Unknown device version. SDK_INT=" + Build.VERSION.SDK_INT
                + ", CODENAME=" + Build.VERSION.CODENAME,
                BuildCompat.isAtLeastQ());
    }

    /** Assumes the release for tests; used to skip tests that cannot work on Q devices. */
    static void assumeAtLeastR() {
        assumeTrue(isAtLeastR());
    }
}
