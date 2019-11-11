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

import com.google.common.io.Files;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Basic {
    // Starts at 0x80.
    private static final String[] TOKENS = {
            "END", "FOR", "RESET", "SET", "CLS", "CMD", "RANDOM", "NEXT", // 0x80
            "DATA", "INPUT", "DIM", "READ", "LET", "GOTO", "RUN", "IF", // 0x88
            "RESTORE", "GOSUB", "RETURN", "REM", "STOP", "ELSE", "TRON", "TROFF", // 0x90
            "DEFSTR", "DEFINT", "DEFSNG", "DEFDBL", "LINE", "EDIT", "ERROR", "RESUME", // 0x98
            "OUT", "ON", "OPEN", "FIELD", "GET", "PUT", "CLOSE", "LOAD", // 0xA0
            "MERGE", "NAME", "KILL", "LSET", "RSET", "SAVE", "SYSTEM", "LPRINT", // 0xA8
            "DEF", "POKE", "PRINT", "CONT", "LIST", "LLIST", "DELETE", "AUTO", // 0xB0
            "CLEAR", "CLOAD", "CSAVE", "NEW", "TAB(", "TO", "FN", "USING", // 0xB8
            "VARPTR", "USR", "ERL", "ERR", "STRING", "INSTR", "POINT", "TIME$", // 0xC0
            "MEM", "INKEY$", "THEN", "NOT", "STEP", "+", "-", "*", // 0xC8
            "/", "[", "AND", "OR", ">", "=", "<", "SGN", // 0xD0
            "INT", "ABS", "FRE", "INP", "POS", "SQR", "RND", "LOG", // 0xD8
            "EXP", "COS", "SIN", "TAN", "ATN", "PEEK", "CVI", "CVS",// 0xE0
            "CVD", "EOF", "LOC", "LOF", "MKI", "MKS$", "MKD$", "CINT", // 0xE8
            "CSNG", "CDBL", "FIX", "LEN", "STR$", "VAL", "ASC", "CHR$", // 0xF0
            "LEFT$", "RIGHT$", "MID$" // 0xF8
    };
    private static final int REM = 0x93;
    private static final int DATA= 0x88;
    private static final int REMQUOT = 0xFB;
    private static final int ELSE = 0x95;
    private static final int EOF = -1;

    private enum State {
        /**
         * Normal part of line.
         */
        NORMAL,

        /**
         * Inside string literal.
         */
        STRING_LITERAL,

        /**
         * After REM or DATA statement to end of line.
         */
        RAW,

        /**
         * Just ate a colon.
         */
        COLON,

        /**
         * Just ate a colon and a REM.
         */
        COLON_REM
    }

    public static String fromTokenized(byte[] bytes) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter out = new PrintWriter(stringWriter);
        ByteArrayInputStream b = new ByteArrayInputStream(bytes);
        State state;

        if (b.read() != 0xD3 || b.read() != 0xD3 || b.read() != 0xD3) {
            System.out.println("Basic: missing magic -- not a BASIC file.");
            return null;
        }

        // One-byte ASCII program name.
        b.read();

        while (true) {
            // Read the address of the next line. We ignore this (as does Basic when
            // loading programs), only using it to detect end of program. (In the real
            // Basic these are regenerated after loading.)
            int address = readShort(b, true);
            if (address == EOF) {
                System.out.println("Basic: EOF in next line's address");
                out.println("[EOF in next line's address]");
                break;
            }
            // Zero address indicates end of program.
            if (address == 0) {
                break;
            }

            // Read current line number.
            int lineNumber = readShort(b, false);
            if (lineNumber == EOF) {
                System.out.println("Basic: EOF in line number");
                out.println("[EOF in line number]");
                break;
            }
            out.printf("%d ", lineNumber);

            // Read rest of line.
            int ch;
            state = State.NORMAL;
            while ((ch = b.read()) != EOF && ch != 0) {
                // Detect the ":REM'" sequence (colon, REM, single quote), because
                // that translates to a single quote. Must be a backward-compatible
                // way to add a single quote as a comment.
                if (ch == ':' && state == State.NORMAL) {
                    state = State.COLON;
                } else if (ch == ':' && state == State.COLON) {
                    out.print(':');
                } else if (ch == REM && state == State.COLON) {
                    state = State.COLON_REM;
                } else if (ch == REMQUOT && state == State.COLON_REM) {
                    out.print('\'');
                    state = State.RAW;
                } else if (ch == ELSE && state == State.COLON) {
                    out.print("ELSE");
                    state = State.NORMAL;
                } else {
                    if (state == State.COLON || state == State.COLON_REM) {
                        out.print(':');
                        if (state == State.COLON_REM) {
                            out.print("REM");
                            state = State.RAW;
                        } else {
                            state = State.NORMAL;
                        }
                    }

                    switch (state) {
                        case NORMAL:
                            if (ch >= 128 && ch < 128 + TOKENS.length) {
                                out.printf("%s", TOKENS[ch - 128]);
                            } else {
                                out.print((char) ch);
                            }

                            if (ch == DATA || ch == REM) {
                                state = State.RAW;
                            } else if (ch == '"') {
                                state = State.STRING_LITERAL;
                            }
                            break;

                        case STRING_LITERAL:
                            if (ch == '\r') {
                                out.print("\\n");
                            } else if (ch == '\\') {
                                out.printf("\\%03o", ch);
                            } else if (ch >= ' ' && ch < 128) {
                                out.print((char) ch);
                            } else {
                                out.printf("\\%03o", ch);
                            }
                            if (ch == '"') {
                                // End of string.
                                state = State.NORMAL;
                            }
                            break;

                        case RAW:
                            out.print((char) ch);
                            break;
                    }
                }
            }
            if (ch == EOF) {
                System.out.println("Basic: EOF in line");
                out.println("[EOF in line]");
                break;
            }

            // Deal with eaten tokens.
            if (state == State.COLON || state == State.COLON_REM) {
                out.print(':');
                if (state == State.COLON_REM)
                    out.print("REM");
                /// state = State.NORMAL;
            }

            out.println();
        }

        return stringWriter.toString();
    }

    /**
     * Reads a little-endian short (two-byte) integer.
     *
     * @return the integer, or EOF on end of file.
     */
    private static int readShort(ByteArrayInputStream is, boolean allowEofAfterFirstByte) {
        int low = is.read();
        if (low == EOF) {
            return EOF;
        }

        int high = is.read();
        if (high == EOF) {
            return allowEofAfterFirstByte ? low : EOF;
        }

        return low + high*256;
    }

    public static void main(String[] args) throws IOException {
        System.out.println(fromTokenized(Files.toByteArray(new File(args[0]))));
    }
}
