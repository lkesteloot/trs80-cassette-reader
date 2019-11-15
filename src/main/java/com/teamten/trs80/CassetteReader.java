/*
 * Copyright 2019 Lawrence Kesteloot
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teamten.trs80;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import picocli.CommandLine;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Main class for reading a WAV file and parsing out the programs on it.
 */
public class CassetteReader implements Runnable {

    @CommandLine.Parameters(index = "0", paramLabel = "INPUT_PATHNAME", description = "Input WAV file.")
    private String mInputPathname;

    @CommandLine.Parameters(index = "1", paramLabel = "OUTPUT_PREFIX", description = "Output prefix.")
    private String mOutputPrefix;

    @CommandLine.Option(names = { "--gui" }, description = "Show an interactive UI.")
    private boolean mShowGui = false;

    public static void main(String[] args) {
        // Parse command-line parameters.
        CommandLine.run(new CassetteReader(), args);
    }

    @Override
    public void run() {
        Results results;
        try {
            results = parsePrograms();
        } catch (IOException e) {
            System.err.println("I/O exception: " + e.getMessage());
            System.exit(1);
            return; // Silence error.
        }

        if (mShowGui) {
            // Hangs program until UI quits.
            new Gui(results);
        }
    }

    /**
     * Parse the input file and generate various output files.
     */
    private Results parsePrograms() throws IOException {
        InputStream is = new FileInputStream(mInputPathname);
        short[] samples;
        try {
            samples = AudioUtils.readWavFile(is, mInputPathname);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file: " + e.getMessage());
        }
        Results results = parsePrograms(samples);
        System.out.print(results.getLog());

        List<Program> programs = results.getPrograms();
        if (!programs.isEmpty()) {
            results.mLog.println("New programs at:");
            for (Program program : programs) {
                results.mLog.println("    " + AudioUtils.frameToTimestamp(program.getStartFrame()));
            }
        }

        // Dump all output files.
        for (Program program : programs) {
            boolean isProgram = program.isProgram();

            // Highlight non-programs in pathname.
            String suffix = isProgram ? "" : "-binary";

            byte[] binary = program.getBinary();

            // Binary dump.
            String basePathname = mOutputPrefix + program.getTrack() + "-" + program.getCopy() + suffix;
            OutputStream fos = new FileOutputStream(basePathname + ".bin");
            fos.write(binary);
            fos.close();

            if (isProgram) {
                // Basic dump.
                String basicProgram = Basic.fromTokenized(binary);
                if (basicProgram == null) {
                    results.mLog.println("Error: Cannot parse Basic program");
                } else {
                    Files.asCharSink(new File(basePathname + ".bas"), Charsets.UTF_8).write(basicProgram);
                }
            } else {
                // Dump non-Basic header.
                results.mLog.printf("First few bytes (of %,d):", binary.length);
                for (int i = 0; i < binary.length && i < 3; i++) {
                    results.mLog.printf(" 0x%02X", binary[i]);
                }
                results.mLog.println();
            }

            // WAV dump.
            File wavFile = new File(basePathname + ".wav");
            // Low-speed programs end in two 0x00, but high-speed programs
            // end in three 0x00. Add the additional 0x00 since we're
            // saving high-speed.
            byte[] highSpeedBytes = binary;
            if (highSpeedBytes.length >= 3 &&
                    highSpeedBytes[highSpeedBytes.length - 1] == (byte) 0x00 &&
                    highSpeedBytes[highSpeedBytes.length - 2] == (byte) 0x00 &&
                    highSpeedBytes[highSpeedBytes.length - 3] != (byte) 0x00) {

                highSpeedBytes = Arrays.copyOf(highSpeedBytes, highSpeedBytes.length + 1);
                highSpeedBytes[highSpeedBytes.length - 1] = 0x00;
            }
            short[] audio = HighSpeedTapeEncoder.encode(highSpeedBytes);
            AudioUtils.writeWavFile(audio, wavFile);
        }

        // Dump bad sections.
        int counter = 1;
        for (BitHistory bitHistory : results.getBadSections()) {
            bitHistory.dump(samples, 0, "bad-" + counter + ".png");
            counter += 1;
        }

        return results;
    }

    /**
     * Parse the programs in the specified samples.
     */
    Results parsePrograms(short[] samples) {
        Results results = new Results();
        results.setOriginalSamples(samples);

        results.mLog.println("Performing high-pass filter.");
        samples = AudioUtils.highPassFilter(samples, 500);
        results.setFilteredSamples(samples);

        int instanceNumber = 1;
        int trackNumber = 0;
        int copyNumber = 1;
        int frame = 0;
        int programStartFrame = -1;
        while (frame < samples.length) {
            results.mLog.println("--------------------------------------- " + instanceNumber);

            // Start out trying all decoders.
            TapeDecoder[] tapeDecoders = new TapeDecoder[] {
                    new LowSpeedTapeDecoder(),
                    new HighSpeedTapeDecoder()
            };

            int searchFrameStart = frame;
            TapeDecoderState state = TapeDecoderState.UNDECIDED;
            for (; frame < samples.length && (state == TapeDecoderState.UNDECIDED || state == TapeDecoderState.DETECTED); frame++) {
                // Give the sample to all decoders in parallel.
                int detectedIndex = -1;
                for (int i = 0; i < tapeDecoders.length; i++) {
                    TapeDecoder tapeDecoder = tapeDecoders[i];

                    tapeDecoder.handleSample(results, samples, frame);

                    // See if it detected its encoding.
                    if (tapeDecoder.getState() != TapeDecoderState.UNDECIDED) {
                        detectedIndex = i;
                    }
                }

                // If any has detected, keep only that one and kill the rest.
                if (state == TapeDecoderState.UNDECIDED) {
                    if (detectedIndex != -1) {
                        TapeDecoder tapeDecoder = tapeDecoders[detectedIndex];

                        // See how long it took to find it. A large gap means a new track.
                        double leadTime = (double) (frame - searchFrameStart)/AudioUtils.HZ;
                        if (leadTime > 10 || programStartFrame == -1) {
                            programStartFrame = frame;
                            trackNumber += 1;
                            copyNumber = 1;
                        }

                        results.mLog.printf("Decoder \"%s\" detected %d-%d at %s after %.1f seconds.\n",
                                tapeDecoder.getName(), trackNumber, copyNumber, AudioUtils.frameToTimestamp(frame), leadTime);

                        // Throw away the other decoders.
                        tapeDecoders = new TapeDecoder[] {
                                tapeDecoder
                        };

                        state = tapeDecoder.getState();
                    }
                } else {
                    // See if we should keep going.
                    state = tapeDecoders[0].getState();
                }
            }

            switch (state) {
                case UNDECIDED:
                    results.mLog.println("Reached end of tape without finding track.");
                    break;

                case DETECTED:
                    results.mLog.println("Reached end of tape while still reading track.");
                    break;

                case ERROR:
                    results.mLog.println("Decoder detected an error; skipping program.");
                    break;

                case FINISHED:
                    Program program = new Program(trackNumber, copyNumber, programStartFrame);
                    program.setBinary(tapeDecoders[0].getProgram());
                    results.addProgram(program);
                    break;
            }

            copyNumber += 1;
            instanceNumber += 1;
        }

        return results;
    }
}
