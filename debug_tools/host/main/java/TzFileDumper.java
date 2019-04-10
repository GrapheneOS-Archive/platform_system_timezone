/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.google.common.base.Joiner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dumps out the contents of a tzfile (v1 format data only) in a CSV form.
 *
 * <p>This class contains a copy of logic found in Android's ZoneInfo.
 */
public class TzFileDumper {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java TzFileDumper <tzfile|dir> <output file|output dir>");
            System.exit(0);
        }

        File input = new File(args[0]);
        File output = new File(args[1]);
        if (input.isDirectory()) {
            if (!output.isDirectory()) {
                System.err.println("If first args is a directory, second arg must be a directory");
                System.exit(1);
            }

            for (File inputFile : input.listFiles()) {
                if (inputFile.isFile()) {
                    File outputFile = new File(output, inputFile.getName() + ".csv");
                    try {
                        new TzFileDumper(inputFile, outputFile).execute();
                    } catch (IOException e) {
                        System.err.println("Error processing:" + inputFile);
                    }
                }
            }
        } else {
            if (!output.isFile()) {
                System.err.println("If first args is a file, second arg must be a file");
                System.exit(1);
            }
            new TzFileDumper(input, output).execute();
        }
    }

    private final File inputFile;
    private final File outputFile;

    private TzFileDumper(File inputFile, File outputFile) {
        this.inputFile = inputFile;
        this.outputFile = outputFile;
    }

    private void execute() throws IOException {
        System.out.println("Dumping " + inputFile + " to " + outputFile);
        MappedByteBuffer mappedTzFile = ZoneSplitter.createMappedByteBuffer(inputFile);

        // Variable names beginning tzh_ correspond to those in "tzfile.h".
        // Check tzh_magic.
        int tzh_magic = mappedTzFile.getInt();
        if (tzh_magic != 0x545a6966) { // "TZif"
            throw new IOException("File=" + inputFile + " has an invalid header=" + tzh_magic);
        }
        // Skip the uninteresting part of the header.
        mappedTzFile.position(mappedTzFile.position() + 28);

        // Read the sizes of the arrays we're about to read.
        int tzh_timecnt = mappedTzFile.getInt();
        // Arbitrary ceiling to prevent allocating memory for corrupt data.
        // 2 per year with 2^32 seconds would give ~272 transitions.
        final int MAX_TRANSITIONS = 2000;
        if (tzh_timecnt < 0 || tzh_timecnt > MAX_TRANSITIONS) {
            throw new IOException(
                    "File=" + inputFile + " has an invalid number of transitions=" + tzh_timecnt);
        }

        int tzh_typecnt = mappedTzFile.getInt();
        final int MAX_TYPES = 256;
        if (tzh_typecnt < 1) {
            throw new IOException("ZoneInfo requires at least one type to be provided for each"
                    + " timezone but could not find one for '" + inputFile + "'");
        } else if (tzh_typecnt > MAX_TYPES) {
            throw new IOException(
                    "File=" + inputFile + " has too many types=" + tzh_typecnt);
        }

        mappedTzFile.getInt(); // Skip tzh_charcnt.

        List<Transition> v1Transitions = readV1Transitions(mappedTzFile, tzh_timecnt, tzh_typecnt);
        List<Type> v1Types = readTypes(mappedTzFile, tzh_typecnt);

        try (Writer fileWriter = new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            writeCsvRow(fileWriter, "V1");
            writeCsvRow(fileWriter);
            writeTypes(v1Types, fileWriter);
            writeCsvRow(fileWriter);
            writeTransitions(v1Transitions, v1Types, fileWriter);
        }
    }

    private List<Transition> readV1Transitions(MappedByteBuffer mappedTzFile, int transitionCount,
            int typeCount) throws IOException {
        int[] transitionTimes = new int[transitionCount];
        byte[] typeIndexes = new byte[transitionCount];

        // Read the data.
        fillIntArray(mappedTzFile, transitionTimes);
        mappedTzFile.get(typeIndexes);

        // Validate and construct the CSV rows.
        List<Transition> transitions = new ArrayList<>();
        for (int i = 0; i < transitionCount; ++i) {
            if (i > 0 && transitionTimes[i] <= transitionTimes[i - 1]) {
                throw new IOException(
                        inputFile + " transition at " + i + " is not sorted correctly, is "
                                + transitionTimes[i] + ", previous is " + transitionTimes[i - 1]);
            }

            int typeIndex = typeIndexes[i] & 0xff;
            if (typeIndex >= typeCount) {
                throw new IOException(inputFile + " type at " + i + " is not < " + typeCount
                        + ", is " + typeIndex);
            }

            Transition transition = new Transition(transitionTimes[i], typeIndex);
            transitions.add(transition);
        }

        return transitions;
    }

    private void writeTransitions(List<Transition> transitions, List<Type> types, Writer fileWriter)
            throws IOException {

        List<Object[]> rows = new ArrayList<>();
        for (Transition transition : transitions) {
            Type type = types.get(transition.typeIndex);
            Object[] row = new Object[] {
                    transition.transitionTimeSeconds,
                    transition.typeIndex,
                    formatTimeSeconds(transition.transitionTimeSeconds),
                    formatDurationSeconds(type.gmtOffsetSeconds),
                    formatIsDst(type.isDst),
            };
            rows.add(row);
        }

        writeCsvRow(fileWriter, "Transitions");
        writeTuplesCsv(fileWriter, rows, "transition", "type", "[UTC time]", "[Type offset]",
                "[Type isDST]");
    }

    private List<Type> readTypes(MappedByteBuffer mappedTzFile, int typeCount)
            throws IOException {

        List<Type> types = new ArrayList<>();
        for (int i = 0; i < typeCount; ++i) {
            int gmtOffsetSeconds = mappedTzFile.getInt();
            byte isDst = mappedTzFile.get();
            if (isDst != 0 && isDst != 1) {
                throw new IOException(inputFile + " dst at " + i + " is not 0 or 1, is " + isDst);
            }

            // We skip the abbreviation index.
            mappedTzFile.get();

            types.add(new Type(gmtOffsetSeconds, isDst));
        }

        return types;
    }

    private void writeTypes(List<Type> types, Writer fileWriter) throws IOException {

        List<Object[]> rows = new ArrayList<>();
        for (Type type : types) {
            Object[] row = new Object[] {
                    type.gmtOffsetSeconds,
                    type.isDst,
                    formatDurationSeconds(type.gmtOffsetSeconds),
                    formatIsDst(type.isDst),
            };
            rows.add(row);
        }

        writeCsvRow(fileWriter, "Types");
        writeTuplesCsv(
                fileWriter, rows, "gmtOffset (seconds)", "isDst", "[gmtOffset ISO]", "[DST?]");
    }

    private static void fillIntArray(MappedByteBuffer mappedByteBuffer, int[] toFill) {
        for (int i = 0; i < toFill.length; i++) {
            toFill[i] = mappedByteBuffer.getInt();
        }
    }

    private static String formatTimeSeconds(long timeInSeconds) {
        long timeInMillis = timeInSeconds * 1000L;
        return Instant.ofEpochMilli(timeInMillis).toString();
    }

    private static String formatDurationSeconds(int duration) {
        return Duration.ofSeconds(duration).toString();
    }

    private String formatIsDst(byte isDst) {
        return isDst == 0 ? "STD" : "DST";
    }

    private static void writeCsvRow(Writer writer, Object... values) throws IOException {
        writer.append(Joiner.on(',').join(values));
        writer.append('\n');
    }

    private static void writeTuplesCsv(Writer writer, List<Object[]> lines, String... headings)
            throws IOException {

        writeCsvRow(writer, (Object[]) headings);
        for (Object[] line : lines) {
            writeCsvRow(writer, line);
        }
    }

    private static class Type {

        final int gmtOffsetSeconds;
        final byte isDst;

        Type(int gmtOffsetSeconds, byte isDst) {
            this.gmtOffsetSeconds = gmtOffsetSeconds;
            this.isDst = isDst;
        }
    }

    private static class Transition {

        final long transitionTimeSeconds;
        final int typeIndex;

        Transition(long transitionTimeSeconds, int typeIndex) {
            this.transitionTimeSeconds = transitionTimeSeconds;
            this.typeIndex = typeIndex;
        }
    }
}
