package com.teamten.trs80;

import com.google.common.primitives.Shorts;

import java.util.ArrayList;
import java.util.List;

public class HighSpeedEncoder {
    /**
     * Samples representing a zero bit.
     */
    private static final short[] ZERO = generateCycle(32);
    /**
     * Samples representing a one bit.
     */
    private static final short[] ONE = generateCycle(15);

    /**
     * Encode the sequence of bytes as an array of audio samples.
     */
    public static short[] encode(byte[] bytes) {
        List<short[]> samplesList = new ArrayList<>();

        // Start with half a second of silence.
        samplesList.add(new short[CassetteReader.HZ/2]);

        // Half a second of 0x55.
        int cycles = 0;
        while (cycles < CassetteReader.HZ/2) {
            cycles += addByte(samplesList, 0x55);
        }
        addByte(samplesList, 0x7F);

        // Write program.
        for (byte b : bytes) {
            // Start bit.
            samplesList.add(ZERO);
            addByte(samplesList, b);
        }

        // End with half a second of silence.
        samplesList.add(new short[CassetteReader.HZ/2]);

        return Shorts.concat(samplesList.toArray(new short[0][]));
    }

    /**
     * Adds the byte "b" to the samples list, most significant bit first.
     * @param samplesList list of samples we're adding to.
     * @param b byte to generate.
     * @return the number of samples added.
     */
    private static int addByte(List<short[]> samplesList, int b) {
        int sampleCount = 0;

        // MSb first.
        for (int i = 7; i >= 0; i--) {
            if ((b & (1 << i)) != 0) {
                samplesList.add(ONE);
                sampleCount += ONE.length;
            } else {
                samplesList.add(ZERO);
                sampleCount += ZERO.length;
            }
        }

        return sampleCount;
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
}
