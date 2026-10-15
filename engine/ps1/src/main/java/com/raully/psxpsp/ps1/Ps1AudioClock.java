package com.raully.psxpsp.ps1;

/** Distributes output samples across video frames without accumulating NTSC drift. */
public final class Ps1AudioClock {
    private static final long NTSC_SAMPLE_NUMERATOR = Ps1Spu.SAMPLE_RATE * 1001L;
    private static final long NTSC_FRAME_DENOMINATOR = 60000L;
    private boolean pal;
    private long remainder;

    public Ps1AudioClock(boolean pal) {
        this.pal = pal;
    }

    /** Number of 44.1 kHz samples for the next video frame. */
    public int nextFrameSampleCount() {
        if (pal) return Ps1Spu.SAMPLE_RATE / 50;
        long accumulated = remainder + NTSC_SAMPLE_NUMERATOR;
        int sampleCount = (int) (accumulated / NTSC_FRAME_DENOMINATOR);
        remainder = accumulated % NTSC_FRAME_DENOMINATOR;
        return sampleCount;
    }

    public void setPal(boolean pal) {
        if (this.pal != pal) {
            this.pal = pal;
            remainder = 0;
        }
    }

    public void reset() {
        remainder = 0;
    }
}
