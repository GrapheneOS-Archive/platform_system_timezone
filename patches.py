#!/usr/bin/python

# Copyright 2023 The Android Open Source Project
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

""" Applies Android release specific patches to IANA tzdata archives. """

import os
import re
import sys
import tarfile
import tempfile

sys.path.append('%s/external/icu/tools' % os.environ.get('ANDROID_BUILD_TOP'))
import i18nutil
import tzdatautil

android_build_top = i18nutil.GetAndroidRootOrDie()
timezone_dir = os.path.realpath('%s/system/timezone' % android_build_top)
i18nutil.CheckDirExists(timezone_dir, 'system/timezone')

timezone_input_data_dir = os.path.realpath('%s/input_data' % timezone_dir)
iana_input_data_dir = os.path.realpath('%s/iana' % timezone_input_data_dir)
iana_original_input_data_dir = os.path.realpath('%s/original' % iana_input_data_dir)
patches_dir = os.path.realpath('%s/patches' % iana_input_data_dir)
iana_patched_data_dir = os.path.realpath('%s/patched' % iana_input_data_dir)

temp_dir = tempfile.mkdtemp("-tzdata")

def extractTar(tar_file, dir):
  if not os.path.exists(dir):
     os.mkdir(dir)
  tar = tarfile.open(tar_file, 'r')
  tar.extractall(dir)

def applyPatchesTo(patches_dir, dest_dir):
  print("Applying patches from %s to %s" % (patches_dir, dest_dir))

  p = re.compile('\d+-(.*)\.patch')
  was_patched = False

  for patch_file in os.listdir(patches_dir):
    m = p.match(patch_file)
    if m:
      file_to_patch_path = '%s/%s' % (dest_dir, m.group(1))
      patch_path = '%s/%s' % (patches_dir, patch_file)
      ret_code = os.system('patch %s %s' % (file_to_patch_path, patch_path))
      if ret_code != 0:
        sys.exit('Failed to apply %s. Halting' % patch_path)
      was_patched = True
  return was_patched


def repack(temp_dir, dest):
  print("packing %s into %s" % (temp_dir, dest))
  old_cwd = os.getcwd()
  os.chdir(temp_dir)

  # To make sure that repeated run generates the same archive.
  for filename in os.listdir(temp_dir):
    os.system('touch -am -d "1970-01-01 00:00:00.000000000 +00000" %s' % filename)
  # This command is taken from eggert/tz Makefile.
  ret_code = os.system('tar --format=pax --pax-option=\'delete=atime,delete=ctime\' --numeric-owner --owner=0 --group=0 --mode=go+u,go-w --sort=name -cf - * | gzip -9n > %s' % dest)
  if ret_code != 0:
    sys.exit('Failed to repack patched files. Halting')

  os.chdir(old_cwd)

""" Returns path to archive which should be used in tzdata files generation. """
def Apply():
  iana_data_tar_file = tzdatautil.GetIanaTarFile(iana_original_input_data_dir, 'tzdata')
  extractTar(iana_data_tar_file, temp_dir)

  # There should be one single file only and it is always created anew if
  # patches are available.
  for filename in os.listdir(iana_patched_data_dir):
    os.remove('%s/%s' % (iana_patched_data_dir, filename))

  was_patched = applyPatchesTo(patches_dir, temp_dir)
  if was_patched:
    archive_name = iana_data_tar_file[iana_data_tar_file.rfind('/') + 1:]
    patched_data_tar_file = '%s/%s' % (iana_patched_data_dir, archive_name)
    repack(temp_dir, patched_data_tar_file)
    return patched_data_tar_file
  else:
    return iana_data_tar_file

