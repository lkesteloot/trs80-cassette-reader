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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BitStream<T> implements Iterable<Boolean> {
    private byte[] mBytes;
    private int mBitCount;
    private Map<Integer,T> mExtras = new HashMap<>();

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

    public void addBit(boolean value, T extra) {
        if (extra != null) {
            mExtras.put(mBitCount, extra);
        }
        addBit(value);
    }

    public void clear() {
        Arrays.fill(mBytes, (byte) 0);
        mBitCount = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(mBytes, getByteCount());
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

    public T getExtra(int index) {
        return mExtras.get(index);
    }

    public Iterator<Boolean> iterator() {
        return new Iterator<Boolean >() {
            private int mBitIndex = 0;

            @Override
            public boolean hasNext() {
                return mBitIndex < mBitCount;
            }

            @Override
            public Boolean next() {
                return getBit(mBitIndex++);
            }
        };
    }

    /**
     * Deletes bits starting with the one at startBitIndex,
     * and every "stride" bits after that. For example, a stride
     * of 1 deletes every bit. Returns a new BitStream.
     */
    public BitStream<T> deleteBits(int startBitIndex, int stride) {
        BitStream<T> newBitStream = new BitStream<>();

        for (int index = 0; index < mBitCount; index++) {
            if (index < startBitIndex || (index - startBitIndex) % stride != 0) {
                newBitStream.addBit(getBit(index), getExtra(index));
            }
        }

        return newBitStream;
    }
}
