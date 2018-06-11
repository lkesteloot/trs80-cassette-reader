package com.teamten.trs80;

import java.io.ByteArrayOutputStream;

import static com.teamten.trs80.CassetteReader.frameToTimestamp;

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
    private static final int END_OF_PROGRAM_SILENCE = CassetteReader.HZ/10;
    /**
     * Number of consecutive zero bits we require in the header before we're pretty
     * sure this is a low speed program.
     */
    private static final int MIN_HEADER_ZEROS = 6;
    /**
     * Value of differentiated sample that indicates a pulse. Had to go down to
     * 2000 for the low-speed binary strings in L tape. Perhaps this should
     * be time-adaptable, since it seems to change a lot over the length of
     * a tape.
     */
    private static final int PULSE_THRESHOLD = 2000;
    private TapeDecoderState mState = TapeDecoderState.UNDECIDED;
    private ByteArrayOutputStream mProgramBytes = new ByteArrayOutputStream();
    /**
     * The frame where we last detected a pulse.
     */
    private int mLastPulseFrame = 0;
    private boolean mEatNextBit = false;
    private int mBitCount = 0;
    private int mRecentBits = 0;
    private boolean mLenientFirstBit = false;
    private int mDetectedZeros = 0;

    @Override
    public String getName() {
        return "low speed";
    }

    @Override
    public void handleSample(short[] samples, int frame) {
        // Differentiate to accentuate a pulse. Pulse go positive, then negative,
        // with a space of PULSE_PEAK_DISTANCE, so subtracting those generates a large
        // positive value at the bottom of the pulse.
        int pulse = frame >= PULSE_PEAK_DISTANCE ? samples[frame - PULSE_PEAK_DISTANCE] - samples[frame] : 0;

        int timeDiff = frame - mLastPulseFrame;
        boolean pulsing = pulse >= PULSE_THRESHOLD && timeDiff > PULSE_WIDTH;

        if (mState == TapeDecoderState.DETECTED && timeDiff > END_OF_PROGRAM_SILENCE) {
            // End of program.
            mState = TapeDecoderState.FINISHED;
        } else if (pulsing) {
            boolean bit = timeDiff < BIT_DETERMINATOR;
            if (mEatNextBit) {
                if (mState == TapeDecoderState.DETECTED && !bit && !mLenientFirstBit) {
                    System.out.println("Warning: At bit of wrong value at " +
                            frameToTimestamp(frame) + ", diff = " + timeDiff + ", last = " +
                            frameToTimestamp(mLastPulseFrame));
                }
                mEatNextBit = false;
                mLenientFirstBit = false;
            } else {
                // Look for a bunch of consecutive zeros.
                if (bit && mState == TapeDecoderState.UNDECIDED && mDetectedZeros < MIN_HEADER_ZEROS) {
                    // Still not in header. Reset count.
                    mDetectedZeros = 0;
                } else {
                    if (bit) {
                        mEatNextBit = true;
                    } else {
                        mDetectedZeros += 1;
                    }
                    mRecentBits = (mRecentBits << 1) | (bit ? 1 : 0);
                    if (mState == TapeDecoderState.UNDECIDED) {
                        // Haven't found end of header yet.
                        if ((mRecentBits & 0xFF) == 0xA5) {
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
