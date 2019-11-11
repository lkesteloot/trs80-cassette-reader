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
import com.teamten.image.ImageUtils;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Main class for reading a WAV file and parsing out the programs on it.
 */
public class CassetteReader {
    public static final int HZ = 44100;
    private static final boolean FORCE = true;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: ./gradlew run --args \"INPUT_PATHNAME OUTPUT_PREFIX\"");
            System.exit(1);
        }

        String inputPathname = args[0];
        String outputPrefix = args[1];

        short[] samples = readWavFile(new File(inputPathname));

        System.out.println("Performing high-pass filter.");
        samples = highPassFilter(samples, 500);

        if (false) {
            // Dump filtered data.
            writeWavFile(samples, new File("filtered.wav"));
        }

        if (false) {
            // For debugging the low speed decoder.
            short[] pulseFrames = new short[samples.length];
            for (int i = 0; i < samples.length; i++) {
                int diff = i >= 7 ? samples[i - 7] - samples[i] : 0;
                pulseFrames[i] = (short) Math.max(Math.min(diff, Short.MAX_VALUE), Short.MIN_VALUE);
            }
            writeWavFile(pulseFrames, new File("pulse.wav"));
        }

        int instanceNumber = 1;
        int trackNumber = 0;
        int copyNumber = 1;
        int frame = 0;
        List<Integer> newPrograms = new ArrayList<>();
        while (frame < samples.length) {
            System.out.println("--------------------------------------- " + instanceNumber);

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

                    tapeDecoder.handleSample(samples, frame);

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
                        double leadTime = (double) (frame - searchFrameStart)/HZ;
                        if (leadTime > 10 || newPrograms.isEmpty()) {
                            newPrograms.add(frame);
                            trackNumber += 1;
                            copyNumber = 1;
                        }

                        System.out.printf("Decoder \"%s\" detected %d-%d at %s after %.1f seconds.\n",
                                tapeDecoder.getName(), trackNumber, copyNumber, frameToTimestamp(frame), leadTime);

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
                    System.out.println("Reached end of tape without finding track.");
                    break;

                case DETECTED:
                    System.out.println("Reached end of tape while still reading track.");
                    break;

                case ERROR:
                    System.out.println("Decoder detected an error; skipping program.");
                    break;

                case FINISHED:
                    TapeDecoder tapeDecoder = tapeDecoders[0];
                    byte[] outputBytes = tapeDecoder.getProgram();

                    // Detect Basic program.
                    boolean isProgram = outputBytes.length >= 3 &&
                            outputBytes[0] == (byte) 0xD3 &&
                            outputBytes[1] == (byte) 0xD3 &&
                            outputBytes[2] == (byte) 0xD3;

                    // Dump non-Basic header.
                    if (!isProgram) {
                        System.out.printf("First few bytes (of %,d):", outputBytes.length);
                        for (int i = 0; i < outputBytes.length && i < 3; i++) {
                            System.out.printf(" 0x%02X", outputBytes[i]);
                        }
                        System.out.println();
                    }

                    // Highlight non-programs in pathname.
                    String suffix = isProgram ? "" : "-binary";

                    // Binary dump.
                    String basePathname = outputPrefix + trackNumber + "-" + copyNumber + suffix;
                    OutputStream fos = new FileOutputStream(basePathname + ".bin");
                    fos.write(outputBytes);
                    fos.close();

                    // Basic dump.
                    if (isProgram) {
                        String basicProgram = Basic.fromTokenized(outputBytes);
                        if (basicProgram == null) {
                            System.out.println("Error: Cannot parse Basic program");
                        } else {
                            Files.asCharSink(new File(basePathname + ".bas"), Charsets.UTF_8).write(basicProgram);
                        }
                    }

                    // WAV dump.
                    File wavFile = new File(basePathname + ".wav");
                    if (wavFile.exists() && !FORCE) {
                        System.out.println("Not overwriting " + wavFile);
                    } else {
                        // Low-speed programs end in two 0x00, but high-speed programs
                        // end in three 0x00. Add the additional 0x00 since we're
                        // saving high-speed.
                        byte[] highSpeedBytes = outputBytes;
                        if (highSpeedBytes.length >= 3 &&
                                highSpeedBytes[highSpeedBytes.length - 1] == (byte) 0x00 &&
                                highSpeedBytes[highSpeedBytes.length - 2] == (byte) 0x00 &&
                                highSpeedBytes[highSpeedBytes.length - 3] != (byte) 0x00) {

                            highSpeedBytes = Arrays.copyOf(highSpeedBytes, highSpeedBytes.length + 1);
                            highSpeedBytes[highSpeedBytes.length - 1] = 0x00;
                        }
                        short[] audio = HighSpeedTapeEncoder.encode(highSpeedBytes);
                        writeWavFile(audio, wavFile);
                    }
                    break;
            }

            copyNumber += 1;
            instanceNumber += 1;
        }

        System.out.println("New programs at:");
        for (int newProgram : newPrograms) {
            System.out.println("    " + frameToTimestamp(newProgram));
        }
    }

    /**
     * Read a WAV file as an array of samples.
     */
    private static short[] readWavFile(File file) throws UnsupportedAudioFileException, IOException {
        System.out.println("Reading " + file);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(file);
        AudioFormat format = audioInputStream.getFormat();
        System.out.println(format);
        if (format.isBigEndian()) {
            throw new IllegalStateException("File must be little endian");
        }
        if (format.getChannels() != 1) {
            throw new IllegalStateException("File must be mono");
        }
        if (format.getSampleSizeInBits() != 16) {
            throw new IllegalStateException("File must be 16-bit audio");
        }
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalStateException("File must be PCM_SIGNED");
        }
        if (format.getFrameRate() != HZ || format.getSampleRate() != HZ) {
            throw new IllegalStateException("File must be " + HZ + " Hz");
        }
        if (format.getFrameSize() != 2) {
            throw new IllegalStateException("File must be 2 bytes per frame");
        }

        // Read entire file as bytes.
        int sampleCount = (int) audioInputStream.getFrameLength();
        int byteCount = sampleCount*format.getFrameSize();
        byte[] bytes = new byte[byteCount];
        int bytesRead = audioInputStream.read(bytes);
        if (bytesRead != byteCount) {
            throw new IllegalStateException("Wanted to read " + byteCount + " but read " + bytesRead);
        }

        // Convert to samples. Samples are little-endian.
        System.out.println("Converting to samples.");
        short[] samples = new short[sampleCount];
        int byteIndex = 0;
        int maxValue = 0;
        for (int frame = 0; frame < sampleCount; frame++, byteIndex += 2) {
            // Java bytes are signed. They sign-extend when converted to int, which is what
            // you want for the MSB but not the LSB.
            int value = ((int) bytes[byteIndex + 1] << 8) | ((int) bytes[byteIndex] & 0xFF);
            samples[frame] = (short) value;
            maxValue = Math.max(maxValue, value);
        }

        return samples;
    }

    /**
     * Writes the samples to a 16-bit mono little-endian WAV file.
     */
    private static void writeWavFile(short[] samples, File file) throws IOException {
        System.out.printf("Writing %s, %,d samples\n", file, samples.length);

        // Convert to bytes.
        byte[] bytes = new byte[samples.length*2];
        for (int i = 0; i < samples.length; i++) {
            short sample = samples[i];
            bytes[i*2] = (byte) sample;
            bytes[i*2 + 1] = (byte) (sample >> 8);
        }

        // Write file.
        AudioFormat format = new AudioFormat(HZ,16,1,true,false);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        AudioInputStream audioInputStream = new AudioInputStream(byteArrayInputStream, format, samples.length);
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, file);
    }

    /**
     * Simple high-pass filter.
     */
    private static short[] highPassFilter(short[] samples, int size) {
        short[] out = new short[samples.length];
        long sum = 0;

        for (int i = 0; i < samples.length; i++) {
            sum += samples[i];
            if (i >= size) {
                sum -= samples[i - size];
            }

            // Subtract out the average of the last "size" samples (to estimate local DC component).
            long value = samples[i] - sum/size;
            // A high-pass filter can generate values outside the short range. Clamp it.
            out[i] = (short) Math.min(Math.max(value, Short.MIN_VALUE), Short.MAX_VALUE);
        }

        return out;
    }

    /**
     * Generate a string version of the frame index.
     */
    public static String frameToTimestamp(int frame) {
        double time = (double) frame/HZ;

        long ms = (long) (time*1000);
        long sec = ms/1000;
        ms -= sec*1000;
        long min = sec/60;
        sec -= min*60;
        long hour = min/60;
        min -= hour*60;

        return String.format("%d:%02d:%02d.%03d (frame %,d)", hour, min, sec, ms, frame);
    }
}
