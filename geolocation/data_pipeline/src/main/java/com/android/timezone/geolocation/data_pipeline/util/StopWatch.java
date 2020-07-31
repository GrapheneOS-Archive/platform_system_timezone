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

package com.android.timezone.geolocation.data_pipeline.util;

/**
 * A basic class for tracking elapsed time of an operation.
 *
 * TODO Remove and migrate to Guava's StopWatch.
 */
public final class StopWatch {

    private final long mStartTimeMillis;

    /** Creates (and starts) the stop watch. */
    public StopWatch() {
        mStartTimeMillis = System.currentTimeMillis();
    }

    /** Reports elapsed time since this instance was created. */
    public String reportElapsed() {
        long elapsedMillis = System.currentTimeMillis() - mStartTimeMillis;
        if (elapsedMillis < 1000) {
            return elapsedMillis + " millis";
        }
        return (elapsedMillis / 1000) + " seconds";
    }
}
