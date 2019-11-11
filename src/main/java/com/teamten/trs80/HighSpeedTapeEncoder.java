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

import com.google.common.primitives.Shorts;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes high-speed (1500 baud) cassettes.
 */
public class HighSpeedTapeEncoder {
    /**
     * Length of a zero bit, in samples.
     */
    private static final int ZERO_LENGTH = 32;
    /**
     * Length of a one bit, in samples.
     */
    private static final int ONE_LENGTH = 15;
    /**
     * Samples representing a zero bit.
     */
    private static final short[] ZERO = generateCycle(ZERO_LENGTH);
    /**
     * Samples representing a one bit.
     */
    private static final short[] ONE = generateCycle(ONE_LENGTH);
    /**
     * Samples representing a long zero bit. This is the first start bit
     * after the end of the header. It's 1 ms longer than a regular zero.
     */
    private static final short[] LONG_ZERO = generateCycle(ZERO_LENGTH + AudioUtils.HZ/1000);
    /**
     * The final cycle in the entire waveform, which is necessary
     * to force that last negative-to-positive transition (and interrupt).
     * We could just use a simple half cycle here, but it's nicer to do
     * something like the original analog.
     */
    private static final short[] FINAL_HALF_CYCLE = generateFinalHalfCycle(ZERO_LENGTH*3, ZERO);

    /**
     * Encode the sequence of bytes as an array of audio samples.
     */
    public static short[] encode(byte[] bytes) {
        List<short[]> samplesList = new ArrayList<>();

        // Start with half a second of silence.
        samplesList.add(new short[AudioUtils.HZ/2]);

        // Header of 0x55.
        for (int i = 0; i < 256; i++) {
            addByte(samplesList, 0x55);
        }
        addByte(samplesList, 0x7F);

        // Write program.
        boolean firstStartBit = true;
        for (byte b : bytes) {
            // Start bit.
            if (firstStartBit) {
                samplesList.add(LONG_ZERO);
                firstStartBit = false;
            } else {
                samplesList.add(ZERO);
            }
            addByte(samplesList, b);
        }

        // Finish off the last cycle, so that it generates an interrupt.
        samplesList.add(FINAL_HALF_CYCLE);

        // End with half a second of silence.
        samplesList.add(new short[AudioUtils.HZ/2]);

        // Concatenate all samples.
        return Shorts.concat(samplesList.toArray(new short[0][]));
    }

    /**
     * Adds the byte "b" to the samples list, most significant bit first.
     * @param samplesList list of samples we're adding to.
     * @param b byte to generate.
     */
    private static void addByte(List<short[]> samplesList, int b) {
        // MSb first.
        for (int i = 7; i >= 0; i--) {
            if ((b & (1 << i)) != 0) {
                samplesList.add(ONE);
            } else {
                samplesList.add(ZERO);
            }
        }
    }

    /**
     * Generate one cycle of a sine wave.
     * @param length number of samples in the full cycle.
     * @return audio samples for one cycle.
     */
    private static short[] generateCycle(int length) {
        short[] audio = new short[length];

        for (int i = 0; i < length; i++) {
            double t = 2*Math.PI*i/length;
            double value = Math.sin(t);
            // -0.5 to 0.5, matches recorded audio.
            short shortValue = (short) (value * 16384);
            audio[i] = shortValue;
        }

        return audio;
    }

    /**
     * Generate a half cycle that fades off to zero instead of coming down hard to zero.
     *
     * @param zero the previous cycle, so we copy the ending slope.
     */
    private static short[] generateFinalHalfCycle(int length, short[] zero) {
        // Copy the slope of the end of the zero bit.
        int slope = zero[zero.length - 1] - zero[zero.length - 2];

        // Points on the Bezier.
        int x1 = 0;
        int y1 = 0;
        int y2 = Short.MAX_VALUE;
        int x2 = (y2 - y1 + x1*slope)/slope;
        int x3 = length/2;
        int y3 = 0;
        int x4 = length - 1;
        int y4 = 0;

        // Generated audio;
        short[] audio = new short[length];

        // Go through Bezier in small steps.
        int position = 0;
        for (int i = 0; i <= 128; i++) {
            double t = i/128.0;

            // Compute Bezier value.
            double x12 = x1 + (x2 - x1)*t;
            double y12 = y1 + (y2 - y1)*t;
            double x23 = x2 + (x3 - x2)*t;
            double y23 = y2 + (y3 - y2)*t;
            double x34 = x3 + (x4 - x3)*t;
            double y34 = y3 + (y4 - y3)*t;

            double x123 = x12 + (x23 - x12)*t;
            double y123 = y12 + (y23 - y12)*t;
            double x234 = x23 + (x34 - x23)*t;
            double y234 = y23 + (y34 - y23)*t;

            double x1234 = x123 + (x234 - x123)*t;
            double y1234 = y123 + (y234 - y123)*t;

            // Draw a crude horizontal line from the previous point.
            int newPosition = Math.min((int) x1234, length - 1);
            while (position <= newPosition) {
                audio[position] = (short) y1234;
                position += 1;
            }
        }

        // Finish up anything left.
        while (position <= length - 1) {
            audio[position] = 0;
            position += 1;
        }

        return audio;
    }
}
