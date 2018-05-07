package com.teamten.trs80;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class CassetteReader {
    private static final int HZ = 44100;

    public static void main(String[] args) throws Exception {
//        File file = new File("/Users/lk/Dropbox/Team Ten/Nostalgia/TRS-80 Cassettes/E3.wav");
        File file = new File("/Users/lk/Downloads/E3-1.wav");
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

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        // Read entire file.
        long frameCount = audioInputStream.getFrameLength();
        int byteCount = (int) (frameCount*format.getFrameSize());
        byte[] bytes = new byte[byteCount];
        int bytesRead = audioInputStream.read(bytes);
        if (bytesRead != byteCount) {
            throw new IllegalStateException("Wanted to read " + byteCount + " but read " + bytesRead);
        }

        // Put out the part we want.
        int frameNumber = 0*HZ;
        int length = (int) audioInputStream.getFrameLength();
        int oldSign = 0;
        int halfCycleSize = 0;
        int cycleSize = 0;
        int[] positiveHistogram = new int[100];
        int[] negativeHistogram = new int[100];
        int[] histogram = new int[100];
        int max = 0;
        byte currentByte = 0;
        int bitCount = 0;
        for (int frame = frameNumber; frame < frameNumber + length; frame++) {
            // Java bytes are signed. They sign-extend when converted to int, which is what
            // you want for the MSB but not the LSB.
            int byteIndex = frame*format.getFrameSize();
            int value = ((int) bytes[byteIndex + 1] << 8) | ((int) bytes[byteIndex] & 0xFF);
//            System.out.println(value);
            int newSign = value > 5000 ? 1
                    : value < -5000 ? -1
                    : 0;

            if (oldSign != 0 && newSign != 0 && oldSign != newSign) {
                // Zero-crossing.
                if (oldSign == 1) {
//                    System.out.println("P" + halfCycleSize);
                    if (halfCycleSize < positiveHistogram.length) {
                        positiveHistogram[halfCycleSize] += 1;
                        max = Math.max(max, halfCycleSize);
                    }
                } else {
//                    System.out.println("        N" + halfCycleSize);
                    if (halfCycleSize < negativeHistogram.length) {
                        negativeHistogram[halfCycleSize] += 1;
                        max = Math.max(max, halfCycleSize);
                    }
                    if (cycleSize < histogram.length) {
                        histogram[cycleSize] += 1;
                        max = Math.max(max, cycleSize);
                        if (cycleSize > 7 && cycleSize < 44) {
                            // Long cycle is "0", short cycle is "1".
                            int bit = cycleSize < 22 ? 1 : 0;
                            // Bits are MSb to LSb.
                            currentByte |= (byte) (bit << (7 - bitCount));
                            bitCount += 1;
                            if (bitCount == 8) {
                                os.write(currentByte);
                                currentByte = 0;
                                bitCount = 0;
                            }
                        }
                    }
                    cycleSize = 0;
                }
                halfCycleSize = 0;
            } else {
                halfCycleSize += 1;
                cycleSize += 1;
            }

            if (newSign != 0) {
                oldSign = newSign;
            }
        }

        if (bitCount > 0) {
            System.out.println("Warning: Had " + bitCount + " bits left over");
        }

        os.close();
        byte[] outputBytes = os.toByteArray();

        int firstByte = 0;
        for (int i = 0; i < outputBytes.length; i++) {
            if (outputBytes[i] == (byte) 0xD3) {
                firstByte = i;
                break;
            }
        }
        System.out.println("First byte: " + firstByte);

        {
            int highlight = -1;
            int i = 0;
            outer: while (true) {
                byte b = outputBytes[0360 + i];
                for (int bit = 0x80; bit != 0; bit >>= 1) {
                    if (highlight == 0) {
                        System.out.print("(");
                    }
                    int value = (b & bit) != 0 ? 1 : 0;
                    System.out.print(value);
                    if (highlight == 0) {
                        System.out.print(")");
                        if (value == 1) {
                            break outer;
                        }
                        highlight = 9;
                    }
                    highlight -= 1;
                }
                System.out.print(" ");
                if (0360 + i == firstByte) {
                    highlight = 0;
                }
                i += 1;
            }
            System.out.println();
            System.out.println(i);
            System.out.println(outputBytes.length);
        }

        // Find shift by seeing where the start bit might be.
        int[] zeroCount = new int[9];
        for (int shift = 0; shift < zeroCount.length; shift++) {
            System.out.print(shift + ": ");
            for (int i = firstByte; i < outputBytes.length; i++) {
                int bit = 7 - (shift + i)%9;
                if (bit >= 0) {
                    int value = 1 << bit;
                    int bitValue = (outputBytes[i] & value) == 0 ? 0 : 1;
                    if (i < firstByte + 200) {
                        System.out.print(bitValue);
                    }
                    if (bitValue == 0) {
                        zeroCount[shift] += 1;
                    }
                }
            }
            System.out.println();
        }
        for (int i = 0; i < zeroCount.length; i++) {
            System.out.printf("%d: %d zeros\n", i, zeroCount[i]);
        }

        // Generate output files.
        for (int shift = 0; shift < 8; shift++) {
            OutputStream fos = new FileOutputStream("/Users/lk/tmp/out-" + shift + ".bin");
            for (int i = 0; i < outputBytes.length - 1; i++) {
                byte thisByte = (byte) (outputBytes[i] << shift);
                byte nextByte = (byte) (((int) outputBytes[i + 1] & 0xFF) >>> (8 - shift));
                byte b = (byte) (thisByte | nextByte);
                fos.write(b);
            }
            fos.close();
        }

        System.out.println("count [domain]\tpositive\tnegative\tcycle");
        for (int i = 0; i <= max; i++) {
            System.out.printf("%d %d %d %d\n", i, positiveHistogram[i], negativeHistogram[i], histogram[i]);
        }
    }
}
// 1ms pause at 11.920
