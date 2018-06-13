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
        int ch;
        int ch2;

        if (b.read() != 0xD3 || b.read() != 0xD3 || b.read() != 0xD3) {
            System.out.println("Basic: missing magic -- not a BASIC file.");
            return null;
        }

        // I don't know what this byte is. It's typically 0x41 (A), 0x44 (D), or 0x4C (L).
        b.read();

        while (true) {
            // Read the address.
            if ((ch = b.read()) == EOF || (ch2 = b.read()) == EOF) {
                System.out.println("Basic: EOF in next line number");
                return null;
            }
            // Zero address indicates end of program.
            if (ch2 == 0 && ch == 0) {
                break;
            }

            // Read current line number.
            if ((ch = b.read()) == EOF || (ch2 = b.read()) == EOF) {
                System.out.println("Basic: EOF in line number");
                return null;
            }
            out.printf("%d ", ch + ch2*256);

            // Read rest of line.
            state = State.NORMAL;
            while ((ch = b.read()) != EOF && ch != 0) {
                if (ch == ':' && state == State.NORMAL) {
                    state = State.COLON;
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
                        }
                        state = State.NORMAL;
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

            if (state == State.COLON || state == State.COLON_REM) {
                out.print(':');
                if (state == State.COLON_REM)
                    out.print("REM");
                state = State.NORMAL;
            }

            out.println();
            if (ch == EOF) {
                System.out.println("Basic: EOF in line");
                return null;
            }
        }

        return stringWriter.toString();
    }

    public static void main(String[] args) throws IOException {
        System.out.println(fromTokenized(Files.toByteArray(new File(args[0]))));
    }
}
