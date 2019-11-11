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

import com.teamten.image.ImageUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps a (bounded) queue of history of recent bits so they can be dumped to an image when
 * a bit can't be decoded.
 */
public class BitHistory {
    private static final Color ZERO_BIT_COLOR = Color.BLACK;
    private static final Color ONE_BIT_COLOR = new Color(50, 50, 50);
    private static final Color START_BIT_COLOR = new Color(20, 150, 20);
    private static final Color BAD_BIT_COLOR = new Color(150, 20, 20);
    private static final Color MISSING_COLOR = new Color(150, 20, 100);
    public static final Color LINE_COLOR = Color.WHITE;
    private final int mMaxSize;
    private final Deque<BitData> mHistory = new ArrayDeque<>();

    /**
     * Creates an history with a max number of bits to remember.
     */
    public BitHistory(int maxSize) {
        mMaxSize = maxSize;
    }

    /**
     * Add a bit to the history.
     */
    public void add(BitData bitData) {
        mHistory.add(bitData);
        while (mHistory.size() > mMaxSize) {
            mHistory.removeFirst();
        }
    }

    /**
     * Dump the last "maxSize" bits to an image.
     * @param samples all samples of the file. The numbers in the BitData objects are indices into this array.
     * @param threshold a Y value where a line should be drawn (both positive and negative). Use 0 for none.
     * @param imagePathname output image pathname.
     */
    public void dump(short[] samples, int threshold, String imagePathname) throws IOException {
        // Find the bounds of the samples to draw.
        int minFrame = Integer.MAX_VALUE;
        int maxFrame = Integer.MIN_VALUE;
        for (BitData bitData : mHistory) {
            minFrame = Math.min(Math.min(bitData.getStartFrame(), bitData.getEndFrame()), minFrame);
            maxFrame = Math.max(Math.max(bitData.getStartFrame(), bitData.getEndFrame()), maxFrame);
        }
        int frameWidth = maxFrame - minFrame + 1;

        // Create image.
        int width = 1200;
        int height = 400;
        BufferedImage image = ImageUtils.make(width, height, MISSING_COLOR);
        Graphics2D g = ImageUtils.createGraphics(image);

        // Draw all backgrounds.
        for (BitData bitData : mHistory) {
            // Compute the background color for this bit.
            Color backgroundColor;
            switch (bitData.getBitType()) {
                case ZERO:
                    backgroundColor = ZERO_BIT_COLOR;
                    break;

                case ONE:
                    backgroundColor = ONE_BIT_COLOR;
                    break;

                case START:
                    backgroundColor = START_BIT_COLOR;
                    break;

                default:
                case BAD:
                    backgroundColor = BAD_BIT_COLOR;
                    break;
            }

            // Map to image.
            int startX = (bitData.getStartFrame() - minFrame)*width/frameWidth;
            startX = clamp(startX, 0, width - 1);
            int endX = (bitData.getEndFrame() - minFrame)*width/frameWidth;
            endX = clamp(endX, 0, width - 1);

            // Draw background.
            g.setColor(backgroundColor);
            g.fillRect(startX, 0, endX - startX, height - 1);
        }

        // Draw signal.
        int lastX = -1;
        int lastY = -1;
        g.setColor(LINE_COLOR);
        for (int frame = minFrame; frame < maxFrame; frame++) {
            // Draw line.
            int x = (frame - minFrame)*width/frameWidth;
            x = clamp(x, 0, width - 1);

            // *Must* cast to int first or -32768 won't negate properly.
            int y = -(int)samples[frame]*(height/2)/32768 + height/2;
            y = clamp(y, 0, height - 1);

            if (lastX != -1) {
                g.drawLine(lastX, lastY, x, y);
            }

            lastX = x;
            lastY = y;
        }

        // Draw grid.
        g.setColor(Color.GRAY);
        int y = height/2;
        g.drawLine(0, y, width - 1, y);
        if (threshold != 0) {
            y = threshold*(height/2)/32768 + height/2;
            g.drawLine(0, y, width - 1, y);
            y = -threshold*(height/2)/32768 + height/2;
            g.drawLine(0, y, width - 1, y);
        }

        ImageUtils.save(image, imagePathname);
    }

    /**
     * Clamp x to min and max inclusive.
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
