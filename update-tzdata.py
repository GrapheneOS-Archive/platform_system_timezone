#!/usr/bin/python -B

"""Updates the timezone data held in bionic and ICU."""

import ftplib
import glob
import httplib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.path.append('../../external/icu/tools')
import i18nutil
import updateicudata

regions = ['africa', 'antarctica', 'asia', 'australasia',
           'etcetera', 'europe', 'northamerica', 'southamerica',
           # These two deliberately come last so they override what came
           # before (and each other).
           'backward', 'backzone' ]

# Calculate the paths that are referred to by multiple functions.
android_build_top = i18nutil.GetAndroidRootOrDie()
bionic_dir = os.path.realpath('%s/bionic' % android_build_top)
bionic_libc_zoneinfo_dir = '%s/libc/zoneinfo' % bionic_dir
i18nutil.CheckDirExists(bionic_libc_zoneinfo_dir, 'bionic/libc/zoneinfo')
zone_compactor_dir = '%s/system/timezone/zone_compactor' % android_build_top
i18nutil.CheckDirExists(zone_compactor_dir, 'system/timezone/zone_compactor')

tmp_dir = tempfile.mkdtemp('-tzdata')


def GetCurrentTzDataVersion():
  return open('%s/tzdata' % bionic_libc_zoneinfo_dir).read().split('\x00', 1)[0]


def WriteSetupFile(extracted_iana_dir):
  """Writes the list of zones that ZoneCompactor should process."""
  links = []
  zones = []
  for region in regions:
    for line in open('%s/%s' % (extracted_iana_dir, region)):
      fields = line.split()
      if fields:
        if fields[0] == 'Link':
          links.append('%s %s %s' % (fields[0], fields[1], fields[2]))
          zones.append(fields[2])
        elif fields[0] == 'Zone':
          zones.append(fields[1])
  zones.sort()

  zone_compactor_setup_file = '%s/setup' % tmp_dir
  setup = open(zone_compactor_setup_file, 'w')
  for link in sorted(set(links)):
    setup.write('%s\n' % link)
  for zone in sorted(set(zones)):
    setup.write('%s\n' % zone)
  setup.close()
  return zone_compactor_setup_file


def FtpRetrieveFile(ftp, filename):
  ftp.retrbinary('RETR %s' % filename, open(filename, 'wb').write)


def FtpRetrieveFileAndSignature(ftp, data_filename):
  """Downloads and repackages the given data from the given FTP server."""
  print 'Downloading data...'
  FtpRetrieveFile(ftp, data_filename)

  print 'Downloading signature...'
  signature_filename = '%s.asc' % data_filename
  FtpRetrieveFile(ftp, signature_filename)


def BuildIcuData(iana_tar_file):
  icu_build_dir = '%s/icu' % tmp_dir

  updateicudata.PrepareIcuBuild(icu_build_dir)
  updateicudata.MakeTzDataFiles(icu_build_dir, iana_tar_file)
  updateicudata.MakeAndCopyIcuDataFiles(icu_build_dir)


def CheckSignature(data_filename):
  signature_filename = '%s.asc' % data_filename
  print 'Verifying signature...'
  # If this fails for you, you probably need to import Paul Eggert's public key:
  # gpg --recv-keys ED97E90E62AA7E34
  subprocess.check_call(['gpg', '--trusted-key=ED97E90E62AA7E34', '--verify',
                         signature_filename, data_filename])


def BuildTzdata(iana_tar_file):
  iana_tar_filename = os.path.basename(iana_tar_file)
  new_version = re.search('(tzdata.+)\\.tar\\.gz', iana_tar_filename).group(1)

  print 'Extracting...'
  extracted_iana_dir = '%s/extracted_iana' % tmp_dir
  os.mkdir(extracted_iana_dir)
  tar = tarfile.open(iana_tar_file, 'r')
  tar.extractall(extracted_iana_dir)

  print 'Calling zic(1)...'
  zic_output_dir = '%s/data' % tmp_dir
  os.mkdir(zic_output_dir)
  zic_generator_template = '%s/%%s' % extracted_iana_dir
  zic_inputs = [ zic_generator_template % x for x in regions ]
  zic_cmd = ['zic', '-d', zic_output_dir ]
  zic_cmd.extend(zic_inputs)
  subprocess.check_call(zic_cmd)

  zone_compactor_setup_file = WriteSetupFile(extracted_iana_dir)

  print 'Calling ZoneCompactor to update tzdata to %s...' % new_version
  class_files_dir = '%s/classes' % tmp_dir
  os.mkdir(class_files_dir)

  subprocess.check_call(['javac', '-d', class_files_dir,
                         '%s/main/java/ZoneCompactor.java' % zone_compactor_dir])

  zone_tab_file = '%s/zone.tab' % extracted_iana_dir
  subprocess.check_call(['java', '-cp', class_files_dir, 'ZoneCompactor',
                         zone_compactor_setup_file, zic_output_dir, zone_tab_file,
                         bionic_libc_zoneinfo_dir, new_version])


# Run with no arguments from any directory, with no special setup required.
# See http://www.iana.org/time-zones/ for more about the source of this data.
def main():
  print 'Found bionic in %s ...' % bionic_dir
  print 'Found icu in %s ...' % updateicudata.icuDir()

  print 'Looking for new tzdata...'

  tzdata_filenames = []

  ftp = ftplib.FTP('ftp.iana.org')
  ftp.login()
  ftp.cwd('tz/releases')
  for filename in ftp.nlst():
    if filename.startswith('tzdata20') and filename.endswith('.tar.gz'):
      tzdata_filenames.append(filename)
  tzdata_filenames.sort()

  # If you're several releases behind, we'll walk you through the upgrades
  # one by one.
  current_version = GetCurrentTzDataVersion()
  current_filename = '%s.tar.gz' % current_version
  for filename in tzdata_filenames:
    if filename > current_filename:
      print 'Found new tzdata: %s' % filename
      i18nutil.SwitchToNewTemporaryDirectory()
      FtpRetrieveFileAndSignature(ftp, filename)
      CheckSignature(filename)

      iana_tar_file = '%s/%s' % ( os.getcwd(), filename)
      BuildIcuData(iana_tar_file)
      BuildTzdata(iana_tar_file)
      print 'Look in %s and %s for new data files' % (bionic_dir, updateicudata.icuDir())
      sys.exit(0)

  print 'You already have the latest tzdata in bionic (%s)!' % current_version
  sys.exit(0)


if __name__ == '__main__':
  main()
