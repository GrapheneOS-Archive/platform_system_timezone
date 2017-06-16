#!/bin/bash

# A script to generate TZ data updates.
#
# Usage: ./createTimeZoneDistro.sh <tzupdate.properties file> <output file>
# See com.android.timezone.distro.tools.CreateTimeZoneDistro for more information.

# Fail if anything below fails
set -e

if [[ -z "${ANDROID_BUILD_TOP}" ]]; then
  echo "Configure your environment with build/envsetup.sh and lunch"
  exit 1
fi

cd ${ANDROID_BUILD_TOP}
make time_zone_distro_tools-host

TOOLS_LIB=${ANDROID_BUILD_TOP}/out/host/common/obj/JAVA_LIBRARIES/time_zone_distro_tools-host_intermediates/javalib.jar
SHARED_LIB=${ANDROID_BUILD_TOP}/out/host/common/obj/JAVA_LIBRARIES/time_zone_distro-host_intermediates/javalib.jar

cd -
java -cp ${TOOLS_LIB}:${SHARED_LIB} com.android.timezone.distro.tools.CreateTimeZoneDistro $@
