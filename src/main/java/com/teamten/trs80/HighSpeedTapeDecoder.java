package com.teamten.trs80;

import java.io.ByteArrayOutputStream;

import static com.teamten.trs80.CassetteReader.frameToTimestamp;

public class HighSpeedTapeDecoder implements TapeDecoder {
    private static final int THRESHOLD = 500;
    private TapeDecoderState mState;
    private ByteArrayOutputStream mProgramBytes = new ByteArrayOutputStream();
    private int mOldSign = 0;
    private int mCycleSize = 0;
    private int mRecentBits = 0;
    private int mBitCount = 0;

    public HighSpeedTapeDecoder() {
        mState = TapeDecoderState.UNDECIDED;
    }

    @Override
    public String getName() {
        return "high speed";
    }

    @Override
    public void handleSample(short[] samples, int frame) {
        short sample = samples[frame];
        int newSign = sample > THRESHOLD ? 1
                : sample < -THRESHOLD ? -1
                : 0;

        // Detect zero-crossing.
        if (mOldSign != 0 && newSign != 0 && mOldSign != newSign) {
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
                                System.out.printf("Bad start bit at byte %d, %s, cycle size %d. ******************\n",
                                        mProgramBytes.size(), frameToTimestamp(frame), mCycleSize);
                                mState = TapeDecoderState.ERROR;
                            }
                        }

                        // Got enough bits for a byte (including the start bit).
                        if (mBitCount == 9) {
                            int b = mRecentBits & 0xFF;
                            mProgramBytes.write(b);
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
//                    System.out.printf("End of program found at byte %d, frame %d, %s.\n",
//                            bs.getByteCount(), frame, frameToTimestamp(frame));
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
