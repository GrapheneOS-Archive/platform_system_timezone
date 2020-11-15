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

package com.android.timezone.geotz.provider;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The service that provides the {@link OfflineLocationTimeZoneProvider}.
 */
public final class OfflineLocationTimeZoneService extends Service {

    private final Object mLock = new Object();
    @Nullable
    private OfflineLocationTimeZoneProvider mProvider;

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (mLock) {
            if (mProvider == null) {
                Bundle metaData = getServiceMetaData();
                mProvider = new OfflineLocationTimeZoneProvider(this, metaData);
                mProvider.onBind();
            }
            return mProvider.getBinder();
        }
    }

    /**
     * Metadata entries are used to configure the provider as this enables the provider code to
     * be a pure library, not an
     */
    private Bundle getServiceMetaData() {
        try {
            ComponentName myService = new ComponentName(this, this.getClass());
            return getPackageManager()
                    .getServiceInfo(myService, PackageManager.GET_META_DATA).metaData;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Unable to obtain service meta data.", e);
        }
    }

    @Override
    public void onDestroy() {
        synchronized (mLock) {
            if (mProvider != null) {
                mProvider.onDestroy();
                mProvider = null;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        synchronized (mLock) {
            if (mProvider != null) {
                mProvider.dump(writer);
            }
        }
    }
}
