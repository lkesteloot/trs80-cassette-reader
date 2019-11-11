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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the results of reading a cassette.
 */
class Results {
    private final List<Program> mPrograms = new ArrayList<>();
    private final List<BitHistory> mBadSections = new ArrayList<>();
    private final StringWriter mLogWriter;
    final PrintWriter mLog;
    private short[] mOriginalSamples;
    private short[] mFilteredSamples;

    Results() {
        mLogWriter = new StringWriter();
        mLog = new PrintWriter(mLogWriter);
    }

    void addProgram(Program program) {
        mPrograms.add(program);
    }

    public List<Program> getPrograms() {
        return mPrograms;
    }

    void addBadSection(BitHistory bitHistory) {
        mBadSections.add(new BitHistory(bitHistory));
    }

    public List<BitHistory> getBadSections() {
        return mBadSections;
    }

    /**
     * Get all log lines so far.
     */
    String getLog() {
        return mLogWriter.toString();
    }
}
