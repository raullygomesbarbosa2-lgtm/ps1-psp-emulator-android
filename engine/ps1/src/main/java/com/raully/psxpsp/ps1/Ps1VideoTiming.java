package com.raully.psxpsp.ps1;

/**
 * Initial PS1 raster timing source. It generates scanline, HBlank and VBlank
 * events and forwards them to the root counters. It is not a pixel renderer;
 * horizontal blank width and active-line boundary are configurable estimates
 * until the GPU display-range registers are implemented.
 */
public final class Ps1VideoTiming {
    public enum Region {
        NTSC(263, 240, 34136, 53693180L, 33868800L),
        PAL(314, 288, 34061, 11L, 7L);

        final int scanlines;
        final int defaultVblankStartLine;
        final int lineLengthTenths;
        final long videoClockNumerator;
        final long videoClockDenominator;

        Region(int scanlines, int defaultVblankStartLine, int lineLengthTenths,
               long videoClockNumerator, long videoClockDenominator) {
            this.scanlines = scanlines;
            this.defaultVblankStartLine = defaultVblankStartLine;
            this.lineLengthTenths = lineLengthTenths;
            this.videoClockNumerator = videoClockNumerator;
            this.videoClockDenominator = videoClockDenominator;
        }
    }

    private static final int TENTHS_PER_VIDEO_CYCLE = 10;
    private final Ps1Timers timers;
    private Region region;
    private int line;
    private int phaseTenths;
    private long frameCount;
    private long videoClockRemainder;
    private int vblankStartLine;
    private int hblankStartTenths;
    private int hblankDurationTenths;
    private boolean hBlank;
    private boolean vBlank;

    public Ps1VideoTiming(Ps1Timers timers) {
        this(timers, Region.NTSC);
    }

    public Ps1VideoTiming(Ps1Timers timers, Region initialRegion) {
        if (timers == null) throw new IllegalArgumentException("timers required");
        this.timers = timers;
        setRegion(initialRegion);
    }

    public Region getRegion() { return region; }
    public int getLine() { return line; }
    public int getScanlinesPerFrame() { return region.scanlines; }
    public long getFrameCount() { return frameCount; }
    public boolean isHBlank() { return hBlank; }
    public boolean isVBlank() { return vBlank; }
    public int getVblankStartLine() { return vblankStartLine; }

    /** Change the video standard and restart raster phase without resetting counters. */
    public void setRegion(Region newRegion) {
        if (newRegion == null) throw new IllegalArgumentException("region required");
        region = newRegion;
        line = 0;
        phaseTenths = 0;
        frameCount = 0;
        videoClockRemainder = 0;
        vblankStartLine = region.defaultVblankStartLine;
        hblankStartTenths = (region.lineLengthTenths * 7) / 8;
        hblankDurationTenths = region.lineLengthTenths - hblankStartTenths;
        hBlank = false;
        vBlank = false;
        timers.setHBlank(false);
        timers.setVBlank(false);
    }

    /**
     * Set the first VBlank scanline. The defaults (240 NTSC, 288 PAL) are a
     * provisional display profile and can be replaced by GPU display settings.
     */
    public void setVblankStartLine(int startLine) {
        if (startLine <= 0 || startLine >= region.scanlines) {
            throw new IllegalArgumentException("VBlank start must be inside the frame");
        }
        vblankStartLine = startLine;
        updateVblankState();
    }

    /** Configure the HBlank window in whole video-clock cycles within one line. */
    public void setHorizontalBlankWindow(int startCycle, int durationCycles) {
        int lineCycles = region.lineLengthTenths / TENTHS_PER_VIDEO_CYCLE;
        if (startCycle < 0 || durationCycles <= 0
                || startCycle + durationCycles > lineCycles) {
            throw new IllegalArgumentException("HBlank window must fit inside the scanline");
        }
        hblankStartTenths = startCycle * TENTHS_PER_VIDEO_CYCLE;
        hblankDurationTenths = durationCycles * TENTHS_PER_VIDEO_CYCLE;
        updateHblankState();
    }

    /** Advance by GPU/video clock cycles; blank edges are delivered to the timers. */
    public void advanceVideoTicks(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must be non-negative");
        for (long i = 0; i < ticks; i++) advanceOneVideoTick();
    }

    /**
     * Advance system-clock cycles and their corresponding video clock. This also
     * clocks the root counters one system cycle at a time, preserving blank gates.
     */
    public void advanceSystemCycles(long cycles) {
        if (cycles < 0) throw new IllegalArgumentException("cycles must be non-negative");
        for (long i = 0; i < cycles; i++) {
            timers.tickSystemCycles(1);
            videoClockRemainder += region.videoClockNumerator;
            long videoTicks = videoClockRemainder / region.videoClockDenominator;
            videoClockRemainder %= region.videoClockDenominator;
            for (long tick = 0; tick < videoTicks; tick++) advanceOneVideoTick();
        }
    }

    private void advanceOneVideoTick() {
        phaseTenths += TENTHS_PER_VIDEO_CYCLE;
        if (phaseTenths >= region.lineLengthTenths) {
            phaseTenths -= region.lineLengthTenths;
            line++;
            if (line >= region.scanlines) {
                line = 0;
                frameCount++;
            }
            updateVblankState();
        }
        updateHblankState();
    }

    private void updateHblankState() {
        boolean newState = phaseTenths >= hblankStartTenths
                && phaseTenths < hblankStartTenths + hblankDurationTenths;
        if (newState != hBlank) {
            hBlank = newState;
            timers.setHBlank(hBlank);
        }
    }

    private void updateVblankState() {
        boolean newState = line >= vblankStartLine;
        if (newState != vBlank) {
            vBlank = newState;
            timers.setVBlank(vBlank);
        }
    }
}
