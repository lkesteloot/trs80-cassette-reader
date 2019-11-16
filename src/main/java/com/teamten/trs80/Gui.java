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

import java.awt.Canvas;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.TextArea;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphical interface for browsing the results of cassette decoding.
 */
class Gui {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1000;
    private static final int TITLE_BAR_HEIGHT = 20;
    private final TextArea mTextArea;
    private final BitHistoryCanvas mBitHistoryOriginalCanvas;
    private final BitHistoryCanvas mBitHistoryFilteredCanvas;

    /**
     * Each row of the menu has a type.
     */
    private enum InfoType { METADATA, BINARY, BASIC, BAD_SECTION };

    /**
     * For each row of the menu.
     */
    private static class InfoSelector {
        private final Program mProgram;
        private final InfoType mInfoType;
        private final int mBadSectionIndex;

        public InfoSelector(Program program, InfoType infoType, int badSectionIndex) {
            mProgram = program;
            mInfoType = infoType;
            mBadSectionIndex = badSectionIndex;
        }

        public InfoSelector(Program program, InfoType infoType) {
            this(program, infoType, -1);
        }

        public Program getProgram() {
            return mProgram;
        }

        public InfoType getInfoType() {
            return mInfoType;
        }

        public BitHistory getBadSection() {
            return mProgram.getBadSections().get(mBadSectionIndex);
        }

        /**
         * String to display in the menu.
         */
        public String getMenuString() {
            switch (mInfoType) {
                case METADATA:
                    return String.format("Track %d, copy %d", mProgram.getTrack(), mProgram.getCopy());

                case BINARY:
                    return "    Binary";

                case BASIC:
                    return "    Basic program";

                case BAD_SECTION:
                    return String.format("    Bad section %d", mBadSectionIndex + 1);

                default:
                    throw new IllegalStateException();
            }
        }
    }

    public Gui(Results results) {
        Frame frame = new Frame();

        // Create our UI list.
        List<InfoSelector> infoSelectorList = new ArrayList<>();
        java.awt.List list = new java.awt.List(10, false);
        list.setBounds(0, TITLE_BAR_HEIGHT, 200, HEIGHT - TITLE_BAR_HEIGHT);
        list.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                int index = (Integer) e.getItem();
                showProgramInfo(infoSelectorList.get(index));
            }
        });

        // Create all the entries in our list chooser.
        for (Program program : results.getPrograms()) {
            infoSelectorList.add(new InfoSelector(program, InfoType.METADATA));
            infoSelectorList.add(new InfoSelector(program, InfoType.BINARY));
            if (program.isProgram()) {
                infoSelectorList.add(new InfoSelector(program, InfoType.BASIC));
            }
            for (int i = 0; i < program.getBadSections().size(); i++) {
                infoSelectorList.add(new InfoSelector(program, InfoType.BAD_SECTION, i));
            }
        }
        // Add the entries to the list.
        for (InfoSelector infoSelector : infoSelectorList) {
            list.add(infoSelector.getMenuString());
        }
        frame.add(list);

        // Text area for metadata, binary, and Basic program.
        mTextArea = new TextArea();
        mTextArea.setBounds(200, TITLE_BAR_HEIGHT, WIDTH - 200, HEIGHT - TITLE_BAR_HEIGHT);
        mTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        frame.add(mTextArea);

        // Canvas for drawing bit history.
        int y = TITLE_BAR_HEIGHT;
        int margin = 2;
        int height = (HEIGHT - TITLE_BAR_HEIGHT - margin)/2;
        mBitHistoryOriginalCanvas = new BitHistoryCanvas(results.getOriginalSamples());
        mBitHistoryOriginalCanvas.setBounds(200, y, WIDTH - 200, height);
        frame.add(mBitHistoryOriginalCanvas);
        y += height + margin;
        mBitHistoryFilteredCanvas = new BitHistoryCanvas(results.getFilteredSamples());
        mBitHistoryFilteredCanvas.setBounds(200, y, WIDTH - 200, HEIGHT - y);
        frame.add(mBitHistoryFilteredCanvas);

        // To quit the program.
        frame.addWindowListener(new WindowAdapter(){
            public void windowClosing(WindowEvent e) {
                frame.dispose();
            }
        });

        frame.setSize(WIDTH, HEIGHT);
        frame.setLayout(null);
        frame.setVisible(true);

        // Pre-select first track.
        if (!infoSelectorList.isEmpty()) {
            list.select(0);
            showProgramInfo(infoSelectorList.get(0));
        }
    }

    /**
     * Update the right-hand pane with info about the selected object.
     */
    private void showProgramInfo(InfoSelector infoSelector) {
        switch (infoSelector.getInfoType()) {
            case METADATA:
                mTextArea.setText(getMetadataString(infoSelector.getProgram()));
                break;

            case BINARY:
                mTextArea.setText(getBinaryString(infoSelector.getProgram()));
                break;

            case BASIC:
                mTextArea.setText(getBasicString(infoSelector.getProgram()));
                break;

            case BAD_SECTION:
                BitHistory bitHistory = infoSelector.getBadSection();
                mBitHistoryOriginalCanvas.setBitHistory(bitHistory);
                mBitHistoryFilteredCanvas.setBitHistory(bitHistory);
                break;
        }

        switch (infoSelector.getInfoType()) {
            case METADATA:
            case BINARY:
            case BASIC:
                mTextArea.setCaretPosition(0);
                mTextArea.setVisible(true);
                mBitHistoryOriginalCanvas.setVisible(false);
                mBitHistoryFilteredCanvas.setVisible(false);
                break;

            case BAD_SECTION:
                mTextArea.setVisible(false);
                mBitHistoryOriginalCanvas.setVisible(true);
                mBitHistoryFilteredCanvas.setVisible(true);
                break;
        }
    }

    /**
     * The metadata for this program.
     */
    private String getMetadataString(Program program) {
        return String.format("Track %d\nCopy %d\nStarts at %s\nBinary is %,d bytes",
                program.getTrack(),
                program.getCopy(),
                AudioUtils.frameToTimestamp(program.getStartFrame()),
                program.getBinary().length);
    }

    /**
     * Text (hex and ASCII) version of the binary.
     */
    private String getBinaryString(Program program) {
        StringBuilder sb = new StringBuilder();

        byte[] binary = program.getBinary();
        for (int addr = 0; addr < binary.length; addr += 16) {
            sb.append(String.format("%04X   ", addr));

            // Hex.
            int subAddr;
            for (subAddr = addr; subAddr < binary.length && subAddr < addr + 16; subAddr++) {
                sb.append(String.format("%02X ", binary[subAddr]));
            }
            for (; subAddr < addr + 16; subAddr++) {
                sb.append("   ");
            }

            sb.append("  ");

            // ASCII.
            for (subAddr = addr; subAddr < binary.length && subAddr < addr + 16; subAddr++) {
                char c = (char) binary[subAddr];
                sb.append(c >= 20 && c < 256 ? c : '.');
            }
            for (; subAddr < addr + 16; subAddr++) {
                sb.append(' ');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Decode the Basic program.
     */
    private String getBasicString(Program program) {
        if (!program.isProgram()) {
            throw new IllegalStateException();
        }

        return Basic.fromTokenized(program.getBinary());
    }

    /**
     * Canvas for drawing a bit history.
     */
    private static class BitHistoryCanvas extends Canvas {
        private short[] mSamples;
        private BitHistory mBitHistory;

        public BitHistoryCanvas(short[] samples) {
            mSamples = samples;
            mBitHistory = null;
        }

        public void setBitHistory(BitHistory bitHistory) {
            mBitHistory = bitHistory;
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            Graphics2D g2d = (Graphics2D) g;

            if (mBitHistory != null) {
                mBitHistory.draw(mSamples, 0, getWidth(), getHeight(), g2d);
            }
        }
    }
}