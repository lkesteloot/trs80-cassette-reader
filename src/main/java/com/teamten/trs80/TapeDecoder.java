package com.teamten.trs80;

public interface TapeDecoder {
    /**
     * The name of the decoder. An all-lower case string.
     */
    String getName();

    /**
     * Handle the sample at "frame".
     */
    void handleSample(short[] samples, int frame);

    /**
     * Get the state of the decoder. See the enum for valid state transitions.
     */
    TapeDecoderState getState();

    /**
     * Get the bytes of the decoded program. Only called if the state is FINISHED.
     */
    byte[] getProgram();
}
