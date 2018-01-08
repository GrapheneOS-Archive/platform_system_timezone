/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.libcore.timezone.tzlookup;

import com.android.libcore.timezone.tzlookup.proto.CountryZonesFile;
import com.ibm.icu.util.BasicTimeZone;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.GregorianCalendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.TimeZoneRule;

import java.io.IOException;
import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLStreamException;

/**
 * Generates the tzlookup.xml file using the information from countryzones.txt and zones.tab.
 *
 * See {@link #main(String[])} for commandline information.
 */
public final class TzLookupGenerator {

    private final String countryZonesFile;
    private final String zoneTabFile;
    private final String outputFile;

    /**
     * Executes the generator.
     *
     * Positional arguments:
     * 1: The countryzones.txt file
     * 2: the zone.tab file
     * 3: the file to generate
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println(
                    "usage: java com.android.libcore.timezone.tzlookup.proto.TzLookupGenerator"
                            + " <input proto file> <zone.tab file> <output xml file>");
            System.exit(0);
        }
        boolean success = new TzLookupGenerator(args[0], args[1], args[2]).execute();
        System.exit(success ? 0 : 1);
    }

    TzLookupGenerator(String countryZonesFile, String zoneTabFile, String outputFile) {
        this.countryZonesFile = countryZonesFile;
        this.zoneTabFile = zoneTabFile;
        this.outputFile = outputFile;
    }

    boolean execute() throws IOException {
        // Parse the countryzones input file.
        CountryZonesFile.CountryZones countryZonesIn;
        try {
            countryZonesIn = CountryZonesFileSupport.parseCountryZonesTextFile(countryZonesFile);
        } catch (ParseException e) {
            logError("Unable to parse " + countryZonesFile, e);
            return false;
        }

        // Check the countryzones rules version matches the version that ICU is using.
        String icuTzDataVersion = TimeZone.getTZDataVersion();
        String inputIanaVersion = countryZonesIn.getIanaVersion();
        if (!icuTzDataVersion.equals(inputIanaVersion)) {
            logError("Input data is for " + inputIanaVersion + " but the ICU you have is for "
                    + icuTzDataVersion);
            return false;
        }

        // Pull out information we want to validate against from zone.tab (which we have to assume
        // matches the ICU version since it doesn't contain its own version info).
        ZoneTabFile zoneTabIn = ZoneTabFile.parse(zoneTabFile);
        Map<String, List<String>> zoneTabMapping =
                ZoneTabFile.createCountryToOlsonIdsMap(zoneTabIn);
        List<CountryZonesFile.Country> countriesIn = countryZonesIn.getCountriesList();
        List<String> countriesInIsos = CountryZonesFileSupport.extractIsoCodes(countriesIn);

        // Sanity check the countryzones file only contains lower-case country codes. The output
        // file uses them and the on-device code assumes lower case.
        if (!Utils.allLowerCaseAscii(countriesInIsos)) {
            logError("Non-lowercase country ISO codes found in: " + countriesInIsos);
            return false;
        }
        // Sanity check the countryzones file doesn't contain duplicate country entries.
        if (!Utils.allUnique(countriesInIsos)) {
            logError("Duplicate input country entries found: " + countriesInIsos);
            return false;
        }

        // Validate the country iso codes found in the countryzones against those in zone.tab.
        // zone.tab uses upper case, countryzones uses lower case.
        List<String> upperCaseCountriesInIsos = Utils.toUpperCase(countriesInIsos);
        Set<String> timezonesCountryIsos = new HashSet<>(upperCaseCountriesInIsos);
        Set<String> zoneTabCountryIsos = zoneTabMapping.keySet();
        if (!zoneTabCountryIsos.equals(timezonesCountryIsos)) {
            logError(zoneTabFile + " contains "
                    + Utils.subtract(zoneTabCountryIsos, timezonesCountryIsos)
                    + " not present in countryzones, "
                    + countryZonesFile + " contains "
                    + Utils.subtract(timezonesCountryIsos, zoneTabCountryIsos)
                    + " not present in zonetab.");
            return false;
        }

        // Start constructing the output structure.
        TzLookupFile.TimeZones timeZonesOut = new TzLookupFile.TimeZones(inputIanaVersion);
        TzLookupFile.CountryZones countryZonesOut = new TzLookupFile.CountryZones();
        timeZonesOut.setCountryZones(countryZonesOut);

        final long offsetSampleTimeMillis = getSampleOffsetTimeMillisForData(inputIanaVersion);

        Errors processingErrors = new Errors();

        // Process each Country.
        for (CountryZonesFile.Country countryIn : countriesIn) {
            String isoCode = countryIn.getIsoCode();
            processingErrors.pushScope("country=" + isoCode);
            try {
                // Each Country must have >= 1 time zone.
                List<CountryZonesFile.TimeZone> timeZonesIn = countryIn.getTimeZonesList();
                if (timeZonesIn.isEmpty()) {
                    processingErrors.addFatal("No time zones");
                    continue;
                }

                // Look for duplicate time zone IDs.
                List<String> countryTimeZoneIds = CountryZonesFileSupport.extractIds(timeZonesIn);
                if (!Utils.allUnique(countryTimeZoneIds)) {
                    processingErrors.addFatal("country's zones=" + countryTimeZoneIds
                            + " contains duplicates");
                }

                // Each Country needs a default time zone ID (but we can guess in some cases).
                String defaultTimeZoneId;
                if (countryIn.hasDefaultTimeZoneId()) {
                    defaultTimeZoneId = countryIn.getDefaultTimeZoneId();
                    if (!validTimeZoneId(defaultTimeZoneId)) {
                        processingErrors.addFatal(
                                "Default time zone ID " + defaultTimeZoneId + " is not valid");
                        continue;
                    }
                } else {
                    if (timeZonesIn.size() > 1) {
                        processingErrors.addFatal(
                                "To pick a default time zone there must be a single offset group");
                        continue;
                    }
                    defaultTimeZoneId = timeZonesIn.get(0).getId();
                }

                // Validate the default.
                if (!countryTimeZoneIds.contains(defaultTimeZoneId)) {
                    processingErrors.addFatal("defaultTimeZoneId=" + defaultTimeZoneId
                            + " is not one of the country's zones=" + countryTimeZoneIds);
                }

                // Work out the hint for whether the country uses a zero offset from UTC.
                // We don't care about historical use of UTC (e.g. parts of Europe like France prior
                // to WW2) so we start looking at the beginning of "this year".
                long startTimeMillis = getYearStartTimeMillisForData(inputIanaVersion);
                boolean everUsesUtc = anyZonesUseUtc(countryTimeZoneIds, startTimeMillis);

                // Add the country to the output structure.
                TzLookupFile.Country countryOut =
                        new TzLookupFile.Country(isoCode, defaultTimeZoneId, everUsesUtc);
                countryZonesOut.addCountry(countryOut);

                // Validate the country information against the equivalent information in zone.tab.
                processingErrors.pushScope("zone.tab comparison");
                try {
                    List<String> zoneTabCountryTimeZoneIds =
                            zoneTabMapping.get(isoCode.toUpperCase());
                    if (zoneTabCountryTimeZoneIds == null) {
                        processingErrors.addFatal("Unknown country=" + isoCode);
                        continue;
                    }

                    // Look for unexpected duplicate time zone IDs in zone.tab
                    if (!Utils.allUnique(zoneTabCountryTimeZoneIds)) {
                        processingErrors.addFatal(
                                "Duplicate time zone IDs found:" + zoneTabCountryTimeZoneIds);
                    }

                    if (!Utils.setEquals(zoneTabCountryTimeZoneIds, countryTimeZoneIds)) {
                        processingErrors.addFatal("IANA lists " + isoCode
                                + " as having zones: " + zoneTabCountryTimeZoneIds
                                + ", but countryzones has " + countryTimeZoneIds);
                        continue;
                    }
                } finally {
                    processingErrors.popScope();
                }

                // Process each input time zone.
                for (CountryZonesFile.TimeZone timeZoneIn : timeZonesIn) {
                    processingErrors.pushScope(
                            "offset=" + timeZoneIn.getUtcOffset() + ", id=" + timeZoneIn.getId());
                    try {
                        // Validate the offset information in countryzones.
                        validateNonDstOffset(offsetSampleTimeMillis, countryIn, timeZoneIn,
                                processingErrors);

                        String timeZoneInId = timeZoneIn.getId();
                        // Add the id to the output structure.
                        TzLookupFile.TimeZoneIdentifier timeZoneIdOut =
                                new TzLookupFile.TimeZoneIdentifier(timeZoneInId);
                        countryOut.addTimeZoneIdentifier(timeZoneIdOut);
                    } finally {
                        processingErrors.popScope();
                    }
                }
            } finally{
                // End of country processing.
                processingErrors.popScope();
            }
        }

        if (!processingErrors.isFatal()) {
            // Write the output structure if there wasn't a fatal error.
            logInfo("Writing " + outputFile);
            try {
                TzLookupFile.write(timeZonesOut, outputFile);
            } catch (XMLStreamException e) {
                e.printStackTrace(System.err);
                processingErrors.addFatal("Unable to write output file");
            }
        }

        // Report all warnings / errors
        if (!processingErrors.isEmpty()) {
            logInfo("Issues:\n" + processingErrors.asString());
        }

        return !processingErrors.isFatal();
    }

    private boolean anyZonesUseUtc(List<String> countryTimeZoneIds, long startTimeMillis) {
        for (String countryTimeZoneId : countryTimeZoneIds) {
            BasicTimeZone timeZone = (BasicTimeZone) TimeZone.getTimeZone(countryTimeZoneId);
            TimeZoneRule[] rules = timeZone.getTimeZoneRules(startTimeMillis);
            for (TimeZoneRule rule : rules) {
                int utcOffset = rule.getRawOffset() + rule.getDSTSavings();
                if (utcOffset == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a sample time related to the IANA version to enable any offset validation to be
     * repeatable (rather than depending on the current time when the tool is run).
     */
    private static long getSampleOffsetTimeMillisForData(String inputIanaVersion) {
        // Uses <year>/07/02 12:00:00 UTC, where year is taken from the IANA version + 1.
        // This is fairly arbitrary, but reflects the fact that we want a point in the future
        // WRT to the data, and once a year has been picked then half-way through seems about right.
        Calendar calendar = getYearStartForData(inputIanaVersion);
        calendar.set(calendar.get(Calendar.YEAR) + 1, Calendar.JULY, 2, 12, 0, 0);
        return calendar.getTimeInMillis();
    }

    /**
     * Returns the 1st Jan 00:00:00 UTC time on the year the IANA version relates to. Therefore
     * guaranteed to be before the data is ever used and can be treated as "the beginning of time"
     * (assuming derived information won't be used for historical calculations).
     */
    private static long getYearStartTimeMillisForData(String inputIanaVersion) {
        return getYearStartForData(inputIanaVersion).getTimeInMillis();
    }

    private static Calendar getYearStartForData(String inputIanaVersion) {
        String yearString = inputIanaVersion.substring(0, inputIanaVersion.length() - 1);
        int year = Integer.parseInt(yearString);
        Calendar calendar = new GregorianCalendar(TimeZone.GMT_ZONE);
        calendar.clear();
        calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        return calendar;
    }

    private static boolean validTimeZoneId(String timeZoneId) {
        TimeZone zone = TimeZone.getTimeZone(timeZoneId);
        return !zone.getID().equals(TimeZone.UNKNOWN_ZONE_ID);
    }

    private static void validateNonDstOffset(long offsetSampleTimeMillis,
            CountryZonesFile.Country country, CountryZonesFile.TimeZone timeZoneIn, Errors errors) {
        String utcOffsetString = timeZoneIn.getUtcOffset();
        long utcOffsetMillis;
        try {
            utcOffsetMillis = Utils.parseUtcOffsetToMillis(utcOffsetString);
        } catch (ParseException e) {
            errors.addFatal("Bad offset string: " + utcOffsetString);
            return;
        }

        final long minimumGranularity = TimeUnit.MINUTES.toMillis(15);
        if (utcOffsetMillis % minimumGranularity != 0) {
            errors.addWarning(
                    "Unexpected granularity: not a multiple of 15 minutes: " + utcOffsetString);
        }

        String timeZoneIdIn = timeZoneIn.getId();
        if (!validTimeZoneId(timeZoneIdIn)) {
            errors.addFatal("Time zone ID=" + timeZoneIdIn + " is not valid");
            return;
        }

        // Check the offset Android has matches what ICU thinks.
        TimeZone timeZone = TimeZone.getTimeZone(timeZoneIdIn);
        int[] offsets = new int[2];
        timeZone.getOffset(offsetSampleTimeMillis, false /* local */, offsets);
        int actualOffsetMillis = offsets[0];
        if (actualOffsetMillis != utcOffsetMillis) {
            errors.addFatal("Offset mismatch: You will want to confirm the ordering for "
                    + country.getIsoCode() + " still makes sense. Raw offset for "
                    + timeZoneIdIn + " is " + Utils.toUtcOffsetString(actualOffsetMillis)
                    + " and not " + Utils.toUtcOffsetString(utcOffsetMillis)
                    + " at " + Utils.formatUtc(offsetSampleTimeMillis));
        }
    }

    private static void logError(String msg) {
        System.err.println("E: " + msg);
    }

    private static void logError(String s, Throwable e) {
        logError(s);
        e.printStackTrace(System.err);
    }

    private static void logInfo(String msg) {
        System.err.println("I: " + msg);
    }
}
