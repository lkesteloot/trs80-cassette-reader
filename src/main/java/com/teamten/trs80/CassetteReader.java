package com.teamten.trs80;

import com.google.common.io.LittleEndianDataOutputStream;
import com.google.common.primitives.Shorts;
import com.teamten.image.ImageUtils;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

public class CassetteReader {
    private static final int HZ = 44100;
    private static final int MAX_BIT_HISTORY = 30;
    private static final int LOW_SPEED_PULSE_PEAK_DISTANCE = 7;
    private static final String CASS_DIR = "/Users/lk/Dropbox/Team Ten/Nostalgia/TRS-80 Cassettes";
    private static final String INPUT_PATHNAME = CASS_DIR + "/L-2.wav";
    private static final String OUTPUT_PREFIX = CASS_DIR + "/L-4-";
//    private static final String INPUT_PATHNAME = CASS_DIR + "/B-1-5.wav";
//    private static final String OUTPUT_PREFIX = CASS_DIR + "/B-2-";
    private static final int LOW_SPEED_MIN_PERIOD = 22;
    private static final int LOW_SPEED_BIT_DIFFERENTIATOR = 68;
    private static final int LOW_SPEED_MAX_SILENCE = HZ/10;

    enum Speed { UNDETERMINED, LOW, HIGH }
    enum BitType { ZERO, ONE, START, BAD }

    private static class BitData {
        public final int mStartFrame;
        public final int mEndFrame;
        public final BitType mBitType;

        public BitData(int startFrame, int endFrame, BitType bitType) {
            mStartFrame = startFrame;
            mEndFrame = endFrame;
            mBitType = bitType;
        }

        public int getStartFrame() {
            return mStartFrame;
        }

        public int getEndFrame() {
            return mEndFrame;
        }

        public BitType getBitType() {
            return mBitType;
        }
    }

    public static void main(String[] args) throws Exception {
        File file = new File(INPUT_PATHNAME);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(file);
        AudioFormat format = audioInputStream.getFormat();
        System.out.println(format);
        if (format.isBigEndian()) {
            throw new IllegalStateException("File must be little endian");
        }
        if (format.getChannels() != 1) {
            throw new IllegalStateException("File must be mono");
        }
        if (format.getSampleSizeInBits() != 16) {
            throw new IllegalStateException("File must be 16-bit audio");
        }
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalStateException("File must be PCM_SIGNED");
        }
        if (format.getFrameRate() != HZ || format.getSampleRate() != HZ) {
            throw new IllegalStateException("File must be " + HZ + " Hz");
        }
        if (format.getFrameSize() != 2) {
            throw new IllegalStateException("File must be 2 bytes per frame");
        }

        BitStream<Integer> bs = new BitStream<>();

        // Read entire file as bytes.
        int frameCount = (int) audioInputStream.getFrameLength();
        int byteCount = frameCount*format.getFrameSize();
        byte[] bytes = new byte[byteCount];
        int bytesRead = audioInputStream.read(bytes);
        if (bytesRead != byteCount) {
            throw new IllegalStateException("Wanted to read " + byteCount + " but read " + bytesRead);
        }
        int[] lowSpeedHistogram = new int[128];

        // Convert to samples. Samples are little-endian.
        short[] frames = new short[frameCount];
        int byteIndex = 0;
        int maxValue = 0;
        for (int frame = 0; frame < frameCount; frame++, byteIndex += 2) {
            // Java bytes are signed. They sign-extend when converted to int, which is what
            // you want for the MSB but not the LSB.
            int value = ((int) bytes[byteIndex + 1] << 8) | ((int) bytes[byteIndex] & 0xFF);
            frames[frame] = (short) value;
            maxValue = Math.max(maxValue, value);
        }

        frames = highPassFilter(frames, 50);
//        writeWavFile(frames, new File("/Users/lk/tmp/out.wav"));

        System.out.printf("Total recording time: %f\n", (float) frameCount/HZ);

        int instanceNumber = 1;
        int trackNumber = 0;
        int copyNumber = 1;
        int frame = 0;
        List<Integer> newPrograms = new ArrayList<>();
        short[] pulseFrames = new short[frameCount];
        while (true) {
            System.out.println("--------------------------------------- " + instanceNumber);

            // Pick out the bits.
            Deque<BitData> bitHistory = new ArrayDeque<>();
            int oldSign = 0;
            int cycleSize = 0;
            int previousBitFrame = 0;
            int[] histogram = new int[100];
            int max = 0;
            int threshold = maxValue/10;
            threshold = 500; // XXX hard-code because basing it on max is dangerous.
            boolean first = true;
            int last16Bits = 0;
            ByteArrayOutputStream programBytes = new ByteArrayOutputStream();
            boolean inProgram = false;
            int bitCount = 0;
            byteCount = 0;
            boolean endOfProgram = false;
            boolean skipProgram = false;
            int dump = -1;
            int searchFrameStart = frame;
            int lastSlowSpeedPulseFrame = 0;
            boolean eatNextSlowSpeedBit = false;
            int lowSpeedBitCount = -1;
            int lowSpeedLast16Bits = 0;
            boolean lowSpeedLenientFirstBit = false;
            Speed speed = Speed.UNDETERMINED;
            for (; frame < frameCount && !endOfProgram; frame++) {
                int value = frames[frame];

                // Low speed.
                if (speed != Speed.HIGH) {
                    int pulse = frame >= LOW_SPEED_PULSE_PEAK_DISTANCE ? frames[frame - LOW_SPEED_PULSE_PEAK_DISTANCE] - value : 0;
                    pulseFrames[frame] = (short) clamp(pulse, Short.MIN_VALUE, Short.MAX_VALUE);

                    int timeDiff = frame - lastSlowSpeedPulseFrame;
                    if (timeDiff > LOW_SPEED_MAX_SILENCE && lowSpeedBitCount >= 0) {
                        // End of program.
                        endOfProgram = true;
                        System.out.println("End of low-speed program at " + frameToTimestamp(frame));
                    } else if (pulse > 10000 && timeDiff > LOW_SPEED_MIN_PERIOD) {
                        if (timeDiff < lowSpeedHistogram.length) {
                            lowSpeedHistogram[timeDiff] += 1;
                        }
                        boolean bit = timeDiff < LOW_SPEED_BIT_DIFFERENTIATOR;
                        if (eatNextSlowSpeedBit) {
                            if (!bit && !lowSpeedLenientFirstBit) {
                                System.out.println("Warning: At bit of wrong value at " +
                                        frameToTimestamp(frame) + ", diff = " + timeDiff + ", last = " +
                                        frameToTimestamp(lastSlowSpeedPulseFrame));
                            }
                            eatNextSlowSpeedBit = false;
                            lowSpeedLenientFirstBit = false;
                        } else {
                            if (bit) {
                                eatNextSlowSpeedBit = true;
                            }
                            lowSpeedLast16Bits = (lowSpeedLast16Bits << 1) | (bit ? 1 : 0);
                            if (lowSpeedBitCount == -1) {
                                // Haven't found end of header yet.
                                if ((lowSpeedLast16Bits & 0xFF) == 0xA5) {
                                    double leadTime = (double) (frame - searchFrameStart)/HZ;
                                    if (leadTime > 30 || newPrograms.isEmpty()) {
                                        newPrograms.add(frame);
                                        trackNumber += 1;
                                        copyNumber = 1;
                                    }
                                    System.out.println("Found end of low-speed header at " + frameToTimestamp(frame));
                                    lowSpeedBitCount = 0;
                                    // For some reason we don't get a clock after this last 1.
                                    lowSpeedLenientFirstBit = true;
                                    inProgram = true;
                                    speed = Speed.LOW;
                                }
                            } else {
                                lowSpeedBitCount += 1;
                                if (lowSpeedBitCount == 8) {
                                    programBytes.write(lowSpeedLast16Bits & 0xFF);
                                    lowSpeedBitCount = 0;
                                }
                            }
                        }
                        lastSlowSpeedPulseFrame = frame;
                    }
                }

                // High speed.
                if (speed != Speed.LOW) {
                    int newSign = value > threshold ? 1
                            : value < -threshold ? -1
                            : 0;

                    if (oldSign != 0 && newSign != 0 && oldSign != newSign) {
                        // Zero-crossing.
                        if (oldSign == -1) {
                            if (cycleSize < histogram.length) {
                                histogram[cycleSize] += 1;
                                max = Math.max(max, cycleSize);
                            }
                            if (cycleSize > 7 && cycleSize < 44) {
                                // Long cycle is "0", short cycle is "1".
                                boolean bit = cycleSize < 22;

                                BitType bitType = bit ? BitType.ONE : BitType.ZERO;

                                // Bits are MSb to LSb.
                                if (first) {
                                    first = false;
                                } else {
                                    bs.addBit(bit, frame);
                                }
                                last16Bits = ((last16Bits << 1) | (bit ? 1 : 0)) & 0xFFFF;
                                if (inProgram) {
                                    bitCount += 1;

                                    if (bitCount == 1) {
                                        if (((last16Bits & 0x1) != 0 || (instanceNumber == 4 && byteCount == -1)) && !skipProgram) {
                                            System.out.printf("Bad start bit at byte %d, %s, cycle size %d. ******************\n",
                                                    byteCount, frameToTimestamp(frame), cycleSize);
                                            bitType = BitType.BAD;
                                            dump = 1;
                                            skipProgram = true;
                                        } else {
                                            bitType = BitType.START;
                                        }
                                    }
                                    if (bitCount == 9) {
                                        int b = last16Bits & 0xFF;
                                        programBytes.write(b);
                                        if (byteCount < 3 && b != 0xd3) {
                                            System.out.printf("    Byte %d: 0x%02x\n", byteCount, b);
                                        }
                                        byteCount += 1;
                                        bitCount = 0;
                                    }
                                } else {
                                    if (last16Bits == 0x557F) {
                                        double leadTime = (double) (frame - searchFrameStart)/HZ;
                                        if (leadTime > 30 || newPrograms.isEmpty()) {
                                            newPrograms.add(frame);
                                            trackNumber += 1;
                                            copyNumber = 1;
                                        }
                                        System.out.printf("Found end of header for %d-%d at byte %d, %s, lead time %f.\n",
                                                trackNumber, copyNumber, bs.getByteCount(),
                                                frameToTimestamp(frame), leadTime);
                                        bs.clear();
                                        inProgram = true;
                                        bitCount = 1;
                                        last16Bits = 0;
                                        speed = Speed.HIGH;
                                    }
                                }

                                if (previousBitFrame != 0) {
                                    bitHistory.addLast(new BitData(previousBitFrame, frame, bitType));
                                    while (bitHistory.size() > MAX_BIT_HISTORY) {
                                        bitHistory.removeFirst();
                                    }
                                    if (dump >= 0) {
                                        if (dump == 0) {
                                            dumpBitHistory(bitHistory, frames, threshold, "/Users/lk/tmp/out-" + instanceNumber + ".png");
                                        }
                                        dump -= 1;
                                    }
                                }

                                previousBitFrame = frame;
                            } else if (inProgram && byteCount > 0 && cycleSize > 66) {
                                // 1.5 ms gap, end of recording.
                                // TODO pull this out of zero crossing.
                                System.out.printf("End of program found at byte %d, frame %d, %s.\n",
                                        bs.getByteCount(), frame, frameToTimestamp(frame));
                                endOfProgram = true;
                            }

                            cycleSize = 0;
                        }
                    } else {
                        cycleSize += 1;
                    }

                    if (newSign != 0) {
                        oldSign = newSign;
                    }
                }
            }

            if (!inProgram) {
                System.out.println("Didn't find header.");
                break;
            }

            System.out.printf("Leftover bits: %d\n", bitCount);

            if (!skipProgram) {
                byte[] outputBytes = programBytes.toByteArray();
                System.out.printf("First three bytes: 0x%02x 0x%02x 0x%02x\n",
                        outputBytes[0], outputBytes[1], outputBytes[2]);
                OutputStream fos2 = new FileOutputStream("/Users/lk/tmp/out-" + trackNumber + "-" + copyNumber + ".bin");
                fos2.write(outputBytes);
                fos2.close();

                File wavFile = new File(OUTPUT_PREFIX + trackNumber + "-" + copyNumber + ".wav");
                if (wavFile.exists()) {
                    System.out.println("Not overwriting " + wavFile);
                } else {
                    short[] audio = generateAudio(outputBytes);
                    writeWavFile(audio, wavFile);
                }
            }

            copyNumber += 1;
            instanceNumber += 1;
        }

        System.out.println("New programs at:");
        for (int newProgram : newPrograms) {
            System.out.println("    " + frameToTimestamp(newProgram));
        }

//        writeWavFile(pulseFrames, new File(CASS_DIR + "/pulse.wav"));

        /*
        System.out.println("frames [domain]\tcount");
        for (int i = 0; i < lowSpeedHistogram.length; i++) {
            System.out.printf("%d %d\n", i, lowSpeedHistogram[i]);
        }


        bs = bs.deleteBits(8, 9);

        byte[] outputBytes = bs.toByteArray();

        System.out.printf("First three bytes: 0x%02x 0x%02x 0x%02x\n",
                outputBytes[0], outputBytes[1], outputBytes[2]);

        int firstByte = 0;
        for (int i = 0; i < outputBytes.length; i++) {
            if (outputBytes[i] == (byte) 0xD3) {
                firstByte = i;
                break;
            }
        }
        System.out.println("First byte: " + firstByte + " of " + outputBytes.length);

        // Find places where the header changes.
        if (false) {
            int counter = 0;
            for (int i = 0; i < firstByte; i++) {
                if (outputBytes[i] != outputBytes[i + 1] &&
                        (outputBytes[i] == 0x55 || outputBytes[i] == (byte) 0xAA ||
                                outputBytes[i + 1] == 0x55 || outputBytes[i + 1] == (byte) 0xAA)) {

                    System.out.printf("-------------------- 0x%02x 0x%02x at %d\n",
                            outputBytes[i], outputBytes[i + 1], i);
                    for (int j = i*8; j < i*8 + 16; j++) {
                        int frameNumber = bs.getExtra(j);
                        System.out.printf("    %d   %.4f\n",
                                bs.getBit(j) ? 1 : 0,
                                (double) frameNumber/HZ);
                    }
                    int firstFrame = bs.getExtra(i*8);
                    int lastFrame = bs.getExtra(i*8 + 16);

                    PrintWriter w = new PrintWriter("/Users/lk/tmp/out-" + counter++ + ".txt");
                    w.printf("Time [domain]\tAudio\n");
                    for (int frame = firstFrame; frame < lastFrame; frame++) {
                        w.printf("%f %d\n", (double) frame/HZ, frames[frame]);
                    }
                    w.close();
                }
            }

            bs = bs.deleteBits(firstByte*8 + 8, 9);
            outputBytes = bs.toByteArray();
        }

        // Generate output file.
        OutputStream fos = new FileOutputStream("/Users/lk/tmp/out.bin");
        fos.write(outputBytes);
        fos.close();

        System.out.println("count [domain]\tpositive\tnegative\tcycle");
        for (int i = 0; i <= max; i++) {
            System.out.printf("%d %d %d %d\n", i, positiveHistogram[i], negativeHistogram[i], histogram[i]);
        }
        */
    }

    private static short[] generateAudio(byte[] program) {
        List<short[]> samples = new ArrayList<>();

        // Generate bit patterns.
        short[] zero = generateCycle(32);
        short[] one = generateCycle(15);

        // Start with half a second of silence.
        samples.add(new short[HZ/2]);

        // Half a second of 0x55.
        int cycles = 0;
        while (cycles < HZ/2) {
            cycles += addByte(samples, 0x55, zero, one);
        }
        addByte(samples, 0x7F, zero, one);

        // Write program.
        for (byte b : program) {
            // Start bit.
            samples.add(zero);
            addByte(samples, b, zero, one);
        }

        // End with half a second of silence.
        samples.add(new short[HZ]);

        return Shorts.concat(samples.toArray(new short[0][]));
    }

    private static int addByte(List<short[]> samples, int b, short[] zero, short[] one) {
        int sampleCount = 0;

        // MSb first.
        for (int i = 7; i >= 0; i--) {
            if ((b & (1 << i)) != 0) {
                samples.add(one);
                sampleCount += one.length;
            } else {
                samples.add(zero);
                sampleCount += zero.length;
            }
        }

        return sampleCount;
    }

    private static short[] generateCycle(int length) {
        short[] audio = new short[length];

        for (int i = 0; i < length; i++) {
            double t = 2*Math.PI*i/length;
            double value = Math.sin(t);
            // -0.5 to 0.5, matches recorded audio.
            short shortValue = (short) (value * 16384);
            audio[i] = shortValue;
        }

        return audio;
    }

    private static void writeWavFile(short[] samples, File file) throws IOException {
        System.out.printf("Writing %s, %,d samples\n", file, samples.length);

        // Generate byte array.
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        LittleEndianDataOutputStream littleEndianDataOutputStream = new LittleEndianDataOutputStream(byteArrayOutputStream);
        for (short sample : samples) {
            littleEndianDataOutputStream.writeShort(sample);
        }

        // Write file.
        AudioFormat format = new AudioFormat(HZ,16,1,true,false);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
        AudioInputStream audioInputStream = new AudioInputStream(byteArrayInputStream, format, samples.length);
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, file);
    }

    private static void dumpBitHistory(Collection<BitData> bitHistory, short samples[], int threshold, String pathname) throws IOException {
        // Find width of entire image.
        int minFrame = Integer.MAX_VALUE;
        int maxFrame = Integer.MIN_VALUE;
        for (BitData bitData : bitHistory) {
            minFrame = Math.min(Math.min(bitData.getStartFrame(), bitData.getEndFrame()), minFrame);
            maxFrame = Math.max(Math.max(bitData.getStartFrame(), bitData.getEndFrame()), maxFrame);
        }
        int frameWidth = maxFrame - minFrame + 1;

        // Size of image.
        int width = 1200;
        int height = 800;

        // Colors.
        Color zeroBitColor = Color.BLACK;
        Color oneBitColor = new Color(50, 50, 50);
        Color startBitColor = new Color(20, 150, 20);
        Color badBitColor = new Color(150, 20, 20);

        BufferedImage image = ImageUtils.makeWhite(width, height);

        int lastX = -1;
        int lastY = -1;

        Graphics2D g = ImageUtils.createGraphics(image);
        for (BitData bitData : bitHistory) {
            Color backgroundColor;
            switch (bitData.getBitType()) {
                case ZERO:
                    backgroundColor = zeroBitColor;
                    break;
                case ONE:
                    backgroundColor = oneBitColor;
                    break;
                case START:
                    backgroundColor = startBitColor;
                    break;
                default:
                case BAD:
                    backgroundColor = badBitColor;
                    break;
            }

            int startX = (bitData.getStartFrame() - minFrame) * width / frameWidth;
            startX = Math.max(0, Math.min(startX, width - 1));
            int endX = (bitData.getEndFrame() - minFrame) * width / frameWidth;
            endX = Math.max(0, Math.min(endX, width - 1));
            g.setColor(backgroundColor);
            g.fillRect(startX, 0, endX, height - 1);

            g.setColor(Color.WHITE);

            for (int frame = bitData.getStartFrame(); frame <= bitData.getEndFrame(); frame++) {
                int x = (frame - minFrame) * width / frameWidth;
                x = Math.max(0, Math.min(x, width - 1));

                int y = -samples[frame]*(height/2)/32768 + height/2;
                y = Math.max(0, Math.min(y, height - 1));

                if (lastX != -1) {
                    g.drawLine(lastX, lastY, x, y);
                }

                lastX = x;
                lastY = y;
            }
        }

        g.setColor(Color.GRAY);
        int y = height/2;
        g.drawLine(0, y, width - 1, y);
        y = threshold*(height/2)/32768 + height/2;
        g.drawLine(0, y, width - 1, y);
        y = -threshold*(height/2)/32768 + height/2;
        g.drawLine(0, y, width - 1, y);

        ImageUtils.save(image, pathname);
    }

    private static short[] highPassFilter(short[] samples, int size) {
        short[] out = new short[samples.length];
        long sum = 0;

        for (int i = 0; i < samples.length; i++) {
            sum += samples[i];
            if (i >= size) {
                sum -= samples[i - size];
            }
            out[i] = (short) (samples[i] - sum/size);
        }

        return out;
    }

    private static String frameToTimestamp(int frame) {
        double time = (double) frame/HZ;

        long ms = (long) (time*1000);
        long sec = ms/1000;
        ms -= sec*1000;
        long min = sec/60;
        sec -= min*60;
        long hour = min/60;
        min -= hour*60;

        return String.format("%d:%02d:%02d.%03d (frame %,d)", hour, min, sec, ms, frame);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
// 1ms pause at 11.920
// Long cycle is "0", short cycle is "1".
// Chose long for zero, but there are many more zeros than ones.
// Baud is odd term since symbols take different amounts of time.
