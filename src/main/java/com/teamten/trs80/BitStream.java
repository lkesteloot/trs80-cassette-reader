package com.teamten.trs80;

import java.util.Arrays;

public class BitStream {
    private byte[] mBytes;
    private int mBitCount;

    public BitStream() {
        mBytes = new byte[1024];
        mBitCount = 0;
    }

    /**
     * Add a bit, MSb first.
     */
    public void addBit(boolean value) {
        int byteIndex = mBitCount/8;
        if (byteIndex == mBytes.length) {
            mBytes = Arrays.copyOf(mBytes, mBytes.length*2);
        }

        if (value) {
            int bitIndex = 7 - mBitCount%8;
            mBytes[byteIndex] |= 1 << bitIndex;
        }

        mBitCount += 1;
    }

    public byte[] getBytes() {
        return mBytes;
    }

    public int getByteCount() {
        return (mBitCount + 7)/8;
    }

    public int getBitCount() {
        return mBitCount;
    }

    public byte getByte(int index) {
        return mBytes[index];
    }

    public boolean getBit(int index) {
        int byteIndex = index/8;
        int bitIndex = 7 - index%8;

        return (mBytes[byteIndex] & (1 << bitIndex)) != 0;
    }
}
