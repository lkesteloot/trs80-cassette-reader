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

import java.io.ByteArrayOutputStream;

/**
 * Decodes low-speed (500 baud) cassettes.
 */
public class LowSpeedTapeDecoder implements TapeDecoder {
    /**
     * Number of samples between the top of the pulse and the bottom of it.
     */
    private static final int PULSE_PEAK_DISTANCE = 7;
    /**
     * Number of samples between start of pulse detection and end of pulse. Once
     * we detect a pulse, we ignore this number of samples.
     */
    private static final int PULSE_WIDTH = 22;
    /**
     * Number of samples that determines a zero (longer) or one (shorter) bit.
     */
    private static final int BIT_DETERMINATOR = 68;
    /**
     * Number of quiet samples that would indicate the end of the program.
     */
    private static final int END_OF_PROGRAM_SILENCE = AudioUtils.HZ/10;
    /**
     * Number of consecutive zero bits we require in the header before we're pretty
     * sure this is a low speed program.
     */
    private static final int MIN_HEADER_ZEROS = 6;
    private TapeDecoderState mState = TapeDecoderState.UNDECIDED;
    private ByteArrayOutputStream mProgramBytes = new ByteArrayOutputStream();
    /**
     * The frame where we last detected a pulse.
     */
    private int mLastPulseFrame = 0;
    private boolean mEatNextPulse = false;
    private int mBitCount = 0;
    private int mRecentBits = 0;
    private boolean mLenientFirstBit = false;
    private int mDetectedZeros = 0;
    /**
     * Height of the previous pulse. We set each pulse's threshold to 1/3 of the previous pulse's height.
     */
    private int mPulseHeight = 0;
    /**
     * Recent history of bits, for debugging.
     */
    private final BitHistory mHistory = new BitHistory(10);

    @Override
    public String getName() {
        return "low speed";
    }

    @Override
    public void handleSample(Results results, short[] samples, int frame) {
        // Differentiate to accentuate a pulse. Pulse go positive, then negative,
        // with a space of PULSE_PEAK_DISTANCE, so subtracting those generates a large
        // positive value at the bottom of the pulse.
        int pulse = frame >= PULSE_PEAK_DISTANCE ? samples[frame - PULSE_PEAK_DISTANCE] - samples[frame] : 0;

        int timeDiff = frame - mLastPulseFrame;
        boolean pulsing = timeDiff > PULSE_WIDTH && pulse >= mPulseHeight/3;

        // Keep track of the height of this pulse, to calibrate for the next one.
        if (timeDiff < PULSE_WIDTH) {
            mPulseHeight = Math.max(mPulseHeight, pulse);
        }

        if (mState == TapeDecoderState.DETECTED && timeDiff > END_OF_PROGRAM_SILENCE) {
            // End of program.
            mState = TapeDecoderState.FINISHED;
        } else if (pulsing) {
            boolean bit = timeDiff < BIT_DETERMINATOR;
            if (mEatNextPulse) {
                if (mState == TapeDecoderState.DETECTED && !bit && !mLenientFirstBit) {
                    results.mLog.println("Warning: At bit of wrong value at " +
                            AudioUtils.frameToTimestamp(frame) + ", diff = " + timeDiff + ", last = " +
                            AudioUtils.frameToTimestamp(mLastPulseFrame));
                    mHistory.add(new BitData(mLastPulseFrame, frame, BitType.BAD));
                    results.addBadSection(mHistory);
                }
                mEatNextPulse = false;
                mLenientFirstBit = false;
            } else {
                // If we see a 1 in the header, reset the count. We want a bunch of consecutive zeros.
                if (bit && mState == TapeDecoderState.UNDECIDED && mDetectedZeros < MIN_HEADER_ZEROS) {
                    // Still not in header. Reset count.
                    mDetectedZeros = 0;
                } else {
                    if (bit) {
                        mEatNextPulse = true;
                    } else {
                        mDetectedZeros += 1;
                    }
                    mRecentBits = (mRecentBits << 1) | (bit ? 1 : 0);
                    mHistory.add(new BitData(mLastPulseFrame, frame, bit ? BitType.ONE : BitType.ZERO));
                    if (mState == TapeDecoderState.UNDECIDED) {
                        // Haven't found end of header yet. Look for it, preceded by zeros.
                        if (mRecentBits == 0x000000A5) {
                            mBitCount = 0;
                            // For some reason we don't get a clock after this last 1.
                            mLenientFirstBit = true;
                            mState = TapeDecoderState.DETECTED;
                        }
                    } else {
                        mBitCount += 1;
                        if (mBitCount == 8) {
                            mProgramBytes.write(mRecentBits & 0xFF);
                            mBitCount = 0;
                        }
                    }
                }
            }
            mLastPulseFrame = frame;
            mPulseHeight = 0;
        }
    }

    @Override
    public TapeDecoderState getState() {
        return mState;
    }

    @Override
    public byte[] getProgram() {
        return mProgramBytes.toByteArray();
    }
}
