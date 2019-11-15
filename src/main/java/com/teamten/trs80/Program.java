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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a program that was read from a cassette.
 */
class Program {
    private final int mTrack;
    private final int mCopy;
    private final int mStartFrame;
    private byte[] mBinary;
    private final List<BitHistory> mBadSections = new ArrayList<>();

    public Program(int track, int copy, int startFrame) {
        mTrack = track;
        mCopy = copy;
        mStartFrame = startFrame;
    }

    public int getTrack() {
        return mTrack;
    }

    public int getCopy() {
        return mCopy;
    }

    public int getStartFrame() {
        return mStartFrame;
    }

    public byte[] getBinary() {
        return mBinary;
    }

    public void setBinary(byte[] binary) {
        mBinary = binary;
    }

    public void addBadSections(List<BitHistory> badSections) {
        mBadSections.addAll(badSections);
    }

    public List<BitHistory> getBadSections() {
        return mBadSections;
    }

    /**
     * Whether the binary represents a Basic program.
     */
    public boolean isProgram() {
        return mBinary != null &&
                mBinary.length >= 3 &&
                mBinary[0] == (byte) 0xD3 &&
                mBinary[1] == (byte) 0xD3 &&
                mBinary[2] == (byte) 0xD3;
    }
}
