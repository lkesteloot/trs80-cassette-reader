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

import com.google.common.io.ByteStreams;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class CassetteReaderTest {
    @Test
    void main() throws Exception {
        for (String prefix : new String[] { "low-1", "high-1" }) {
            testReading(prefix);
        }
    }

    private void testReading(String prefix) throws Exception {
        String wavPathname = prefix + ".wav";
        String binPathname = prefix + ".bin" ;

        InputStream is = getClass().getClassLoader().getResourceAsStream(wavPathname);
        if (is == null) {
            fail("Can't find WAV file " + wavPathname);
        }
        short[] samples = AudioUtils.readWavFile(is, wavPathname);

        is = getClass().getClassLoader().getResourceAsStream(binPathname);
        if (is == null) {
            fail("Can't find BIN file " + binPathname);
        }
        byte[] refBinary = ByteStreams.toByteArray(is);

        Results results = new CassetteReader().parsePrograms(samples);
        System.out.print(results.getLog());
        assertEquals(1, results.getPrograms().size());
        Program program = results.getPrograms().get(0);
        assertEquals(1, program.getTrack());
        assertEquals(1, program.getCopy());
        assertArrayEquals(refBinary, program.getBinary());
    }
}