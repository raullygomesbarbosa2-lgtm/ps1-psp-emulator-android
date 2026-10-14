package com.raully.psxpsp.ps1;

/** Partial GP1 display-control register model; GP0 rendering commands are not implemented. */
public final class Ps1Gpu {
    public static final int DATA_PORT = 0x1F801810;
    public static final int CONTROL_PORT = 0x1F801814;

    private final Ps1VideoTiming videoTiming;
    private int controlWriteLatch;
    private int displayMode;
    private int displayStart;
    private int horizontalRange;
    private int verticalRange;
    private int dmaDirection;
    private boolean displayDisabled;
    private boolean gpuInterrupt;

    public Ps1Gpu(Ps1VideoTiming videoTiming) {
        if (videoTiming == null) throw new IllegalArgumentException("video timing required");
        this.videoTiming = videoTiming;
        reset();
    }

    public int getDisplayMode() { return displayMode; }
    public int getDisplayStart() { return displayStart; }
    public int getHorizontalRange() { return horizontalRange; }
    public int getVerticalRange() { return verticalRange; }
    public boolean isDisplayDisabled() { return displayDisabled; }
    public int getDmaDirection() { return dmaDirection; }
    public int getHorizontalResolution() {
        if ((displayMode & 0x40) != 0) return 368;
        int[] widths = {256, 320, 512, 640};
        return widths[displayMode & 3];
    }
    public int getVerticalResolution() {
        return (displayMode & 0x24) == 0x24 ? 480 : 240;
    }
    public boolean isPal() { return (displayMode & 0x08) != 0; }

    /** Status fields implemented from GP1(08h), GP1(03h), and the IRQ latch. */
    public int readStatus() {
        int status = 0;
        status |= (displayMode & 0x40) << 10; // GPUSTAT.16
        status |= (displayMode & 0x03) << 17; // GPUSTAT.17-18
        status |= (displayMode & 0x3C) << 17; // GPUSTAT.19-22
        status |= (displayMode & 0x80) << 7;  // GPUSTAT.14 reverse flag
        if (displayDisabled) status |= 1 << 23;
        if (gpuInterrupt) status |= 1 << 24;
        return status;
    }

    public void writeControlByte(int lane, int value) {
        if (lane < 0 || lane > 3) throw new IllegalArgumentException("invalid register lane");
        int shift = lane * 8;
        int mask = 0xFF << shift;
        controlWriteLatch = (controlWriteLatch & ~mask) | ((value & 0xFF) << shift);
        if (lane == 3) writeControlWord(controlWriteLatch);
    }

    public void writeControlWord(int value) {
        controlWriteLatch = value;
        int command = (value >>> 24) & 0xFF;
        int parameters = value & 0x00FFFFFF;
        switch (command) {
            case 0x00: reset(); return;
            case 0x01: return; // reset command buffer; GP0 FIFO is not modeled yet
            case 0x02: gpuInterrupt = false; return;
            case 0x03: displayDisabled = (parameters & 1) != 0; return;
            case 0x04: dmaDirection = parameters & 3; return;
            case 0x05:
                displayStart = parameters & 0x0007FFFF;
                return;
            case 0x06:
                horizontalRange = parameters & 0x00FFFFFF;
                applyHorizontalRange();
                return;
            case 0x07:
                verticalRange = parameters & 0x000FFFFF;
                applyVerticalRange();
                return;
            case 0x08:
                displayMode = parameters & 0xFF;
                videoTiming.changeRegionPreservingPhase(isPal() ? Ps1VideoTiming.Region.PAL : Ps1VideoTiming.Region.NTSC);
                applyHorizontalRange();
                applyVerticalRange();
                return;
            default:
                // Unimplemented GP1 commands are ignored without corrupting timing state.
        }
    }

    public void reset() {
        controlWriteLatch = 0;
        displayMode = 0;
        displayStart = 0;
        displayDisabled = false;
        dmaDirection = 0;
        gpuInterrupt = false;
        // Common initial display range: 256 pixels wide and 240 lines, centered by GP1.
        horizontalRange = 0x200 | ((0x200 + 256 * 10) << 12);
        verticalRange = 0x10 | ((0x10 + 240) << 10);
        videoTiming.setRegion(Ps1VideoTiming.Region.NTSC);
        applyHorizontalRange();
        applyVerticalRange();
    }

    private void applyHorizontalRange() {
        int start = horizontalRange & 0xFFF;
        int end = (horizontalRange >>> 12) & 0xFFF;
        videoTiming.setHorizontalDisplayRange(start, end);
    }

    private void applyVerticalRange() {
        int start = verticalRange & 0x3FF;
        int end = (verticalRange >>> 10) & 0x3FF;
        videoTiming.setVerticalDisplayRange(start, end);
    }
}
