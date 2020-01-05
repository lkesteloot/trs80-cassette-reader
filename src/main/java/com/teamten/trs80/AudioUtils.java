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

import com.google.common.io.ByteStreams;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility methods for reading, writing, and processing audio files.
 */
public class AudioUtils {
    /**
     * The sampling frequency we're dealing with.
     */
    public static final int HZ = 44100;

    /**
     * Writes the samples to a 16-bit mono little-endian WAV file.
     */
    public static void writeWavFile(short[] samples, File file) throws IOException {
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
     * Read a WAV file as an array of samples.
     */
    public static short[] readWavFile(InputStream is, String pathname) throws UnsupportedAudioFileException, IOException {
        // The AudioSystem requires an input stream that supports mar() and reset(). The normal
        // FileInputStream doesn't support this, and it's not clear whether the resource InputStream
        // does, so suck the whole file into memory and use a ByteArrayInputStream, which definitely
        // supports it.
        byte[] rawBytes = ByteStreams.toByteArray(is);
        ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(bais);
        AudioFormat format = audioInputStream.getFormat();
        System.out.println("Format of " + pathname + " " + format);
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
     * Simple high-pass filter.
     */
    public static short[] highPassFilter(short[] samples, int size) {
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
