package com.raully.psxpsp.ps1;

/** PS1 interrupt controller latches for I_STAT (1F801070h) and I_MASK (1F801074h). */
public final class Ps1InterruptController {
    public static final int SOURCE_COUNT = 11;
    public static final int VALID_BITS = (1 << SOURCE_COUNT) - 1;

    public static final int IRQ_VBLANK = 0;
    public static final int IRQ_GPU = 1;
    public static final int IRQ_CDROM = 2;
    public static final int IRQ_DMA = 3;
    public static final int IRQ_TIMER0 = 4;
    public static final int IRQ_TIMER1 = 5;
    public static final int IRQ_TIMER2 = 6;
    public static final int IRQ_CONTROLLER = 7;
    public static final int IRQ_SIO = 8;
    public static final int IRQ_SPU = 9;
    public static final int IRQ_LIGHTPEN = 10;

    private int status;
    private int mask;

    public int getStatus() { return status; }
    public int getMask() { return mask; }
    public boolean isPending() { return (status & mask) != 0; }

    /** Latch a rising-edge event from one of the 11 PS1 interrupt sources. */
    public void request(int source) {
        if (source < 0 || source >= SOURCE_COUNT) throw new IllegalArgumentException("invalid IRQ source");
        status |= 1 << source;
    }

    /** I_STAT acknowledge: writing zero clears a pending bit; writing one leaves it unchanged. */
    public void acknowledge(int value, int writtenBitMask) {
        int laneMask = writtenBitMask & VALID_BITS;
        status &= (value | ~laneMask);
        status &= VALID_BITS;
    }

    /** I_MASK is a normal read/write enable mask, including partial byte writes. */
    public void writeMask(int value, int writtenBitMask) {
        int laneMask = writtenBitMask & VALID_BITS;
        mask = (mask & ~laneMask) | (value & laneMask);
        mask &= VALID_BITS;
    }

    public void reset() {
        status = 0;
        mask = 0;
    }

    int readByte(int registerOffset) {
        int word = registerOffset < 4 ? status : mask;
        int lane = registerOffset & 3;
        return (word >>> (lane * 8)) & 0xFF;
    }

    void writeByte(int registerOffset, int value) {
        int registerBase = registerOffset & ~3;
        int lane = registerOffset & 3;
        int shift = lane * 8;
        int laneMask = 0xFF << shift;
        int valueBits = (value & 0xFF) << shift;
        if (registerBase == 0) acknowledge(valueBits, laneMask);
        else writeMask(valueBits, laneMask);
    }
}
