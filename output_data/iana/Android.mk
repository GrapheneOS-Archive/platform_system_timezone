# -*- mode: makefile -*-
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := tzdata
LOCAL_SRC_FILES := $(LOCAL_MODULE)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT)/usr/share/zoneinfo
include $(BUILD_PREBUILT)

ifeq ($(WITH_HOST_DALVIK),true)

# A host version of the tzdata module for use by
# hostdex rules.

# Simulate /system
include $(CLEAR_VARS)
LOCAL_MODULE := tzdata_host
LOCAL_IS_HOST_MODULE := true
LOCAL_SRC_FILES := tzdata
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := $(LOCAL_SRC_FILES)
LOCAL_MODULE_PATH := $(HOST_OUT)/usr/share/zoneinfo
include $(BUILD_PREBUILT)

# Simulate the runtime module.
include $(CLEAR_VARS)
LOCAL_MODULE := tzdata_host_runtime_apex
LOCAL_IS_HOST_MODULE := true
LOCAL_SRC_FILES := tzdata
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := $(LOCAL_SRC_FILES)
LOCAL_MODULE_PATH := $(HOST_OUT)/com.android.runtime/etc/tz
include $(BUILD_PREBUILT)

endif

# Module definition producing a tzdata prebuilt file in
# /system/etc/tz for standalone ART testing purposes. This is a
# temporary change needed until the ART Buildbot and Golem both fully
# support the Runtime APEX (see b/121117762). This module should never
# be shipped by default (i.e. should never be part of
# `PRODUCT_PACKAGE`.)
#
# TODO(b/121117762, b/129332183): Remove this module definition when
# the ART Buildbot and Golem have full support for the Runtime APEX.
include $(CLEAR_VARS)
LOCAL_MODULE := tzdata-art-test
LOCAL_MODULE_STEM := tzdata
LOCAL_SRC_FILES := $(LOCAL_MODULE_STEM)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT)/etc/tz
include $(BUILD_PREBUILT)
