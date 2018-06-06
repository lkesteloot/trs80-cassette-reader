
This is a Java program that reads WAV files of high-speed (1500 baud) TRS-80
Model III cassettes, parses the bits, and generates a clean version that can be
read by an [emulator](https://github.com/lkesteloot/trs80).

The WAV file must be mono, 44.1 kHz, little-endian, and 16 bits per
sample.

# 1500 baud

The TRS-80 Model III high-speed (1500 baud) format is as follows:

* Each bit is one cycle of a sine wave (positive half-cycle, then negative
  half-cycle).
* A 0 bit is encoded as a full cycle taking 725 µs, or 32 samples at 44.1 kHz.
* A 1 bit is encoded as a full cycle taking 340 µs, or 15 samples at 44.1 kHz.
* Each byte is written with its most-significant bit first.
* The header is 256 instances of the byte 0x55, followed by a single 0x7F.
* The program is then written as a sequence of bytes, each starting with a
  start bit of value 0 followed by the byte value.
* Between the header and the program is a 1 ms pause. This is probably not intentional,
  but a result of some processing the ROM had to do when writing the program.
* A 1.5 ms silence indicates the end of the program. There's no special end-of-file marker.

# 1500 baud mystery

Note that a 0 bit is about twice as long as a 1 bit. This is a strange
design, since 0 bits appear far more often in programs:
all space characters (0x20) have 0 for 7 of their 8 bits; all ASCII
characters (comments, strings) have their most significant bit as 0;
every start bit is a zero.

In one program I analyzed, there were 15,472 zero bits and 2960 one bits. That's
a recording time of 12.2 seconds, or 1508 baud. Had they swapped the meaning of
the two cycle times, that would have been reduced to 7.4 seconds, or 2489 baud.
Instead of a jump from 500 baud (on the Model I) to 1500 baud, they could have
claimed nearly 2500 baud! If anyone knows why they made this decision, please
let me know.


