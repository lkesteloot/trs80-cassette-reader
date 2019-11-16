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
 * Decodes high-speed (1500 baud) cassettes.
 */
public class HighSpeedTapeDecoder implements TapeDecoder {
    private static final int THRESHOLD = 500;
    // If we go this many frames without any crossing, then we can assume we're done.
    private static final int MIN_SILENCE_FRAMES = 1000;
    private TapeDecoderState mState;
    private ByteArrayOutputStream mProgramBytes = new ByteArrayOutputStream();
    private int mOldSign = 0;
    private int mCycleSize = 0;
    private int mRecentBits = 0;
    private int mBitCount = 0;
    private int mLastCrossingFrame = 0;
    /**
     * Recent history of bits, for debugging.
     */
    private final BitHistory mHistory = new BitHistory(20);

    public HighSpeedTapeDecoder() {
        mState = TapeDecoderState.UNDECIDED;
    }

    @Override
    public String getName() {
        return "high speed";
    }

    @Override
    public void handleSample(Results results, short[] samples, int frame) {
        short sample = samples[frame];
        int newSign = sample > THRESHOLD ? 1
                : sample < -THRESHOLD ? -1
                : 0;

        // Detect zero-crossing.
        if (mOldSign != 0 && newSign != 0 && mOldSign != newSign) {
            mLastCrossingFrame = frame;

            // Detect positive edge. That's the end of the cycle.
            if (mOldSign == -1) {
                // Only consider cycles in the right range of periods.
                if (mCycleSize > 7 && mCycleSize < 44) {
                    // Long cycle is "0", short cycle is "1".
                    boolean bit = mCycleSize < 22;

                    // Bits are MSb to LSb.
                    mRecentBits = (mRecentBits << 1) | (bit ? 1 : 0);

                    // If we're in the program, add the bit to our stream.
                    if (mState == TapeDecoderState.DETECTED) {
                        mBitCount += 1;

                        // Just got a start bit. Must be zero.
                        if (mBitCount == 1) {
                            if (bit) {
                                results.mLog.printf("Bad start bit at byte %d, %s, cycle size %d.\n",
                                        mProgramBytes.size(), AudioUtils.frameToTimestamp(frame), mCycleSize);
                                mState = TapeDecoderState.ERROR;
                                mHistory.add(new BitData(frame - mCycleSize, frame, BitType.BAD));
                                results.addBadSection(mHistory);
                            } else {
                                mHistory.add(new BitData(frame - mCycleSize, frame, BitType.START));
                            }
                        } else {
                            mHistory.add(new BitData(frame - mCycleSize, frame, bit ? BitType.ONE : BitType.ZERO));
                        }

                        // Got enough bits for a byte (including the start bit).
                        if (mBitCount == 9) {
                            mProgramBytes.write(mRecentBits & 0xFF);
                            mBitCount = 0;
                        }
                    } else {
                        // Detect end of header.
                        if ((mRecentBits & 0xFFFF) == 0x557F) {
                            mState = TapeDecoderState.DETECTED;

                            // No start bit on first byte.
                            mBitCount = 1;
                            mRecentBits = 0;
                        }
                    }
                } else if (mState == TapeDecoderState.DETECTED && mProgramBytes.size() > 0 && mCycleSize > 66) {
                    // 1.5 ms gap, end of recording.
                    // TODO pull this out of zero crossing.
                    mState = TapeDecoderState.FINISHED;
                }

                // End of cycle, start a new one.
                mCycleSize = 0;
            }
        } else {
            // Continue current cycle.
            mCycleSize += 1;
        }

        if (newSign != 0) {
            mOldSign = newSign;
        }

        if (mState == TapeDecoderState.DETECTED && frame - mLastCrossingFrame > MIN_SILENCE_FRAMES) {
            mState = TapeDecoderState.FINISHED;
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
