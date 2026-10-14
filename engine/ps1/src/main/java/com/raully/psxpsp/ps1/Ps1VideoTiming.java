package com.raully.psxpsp.ps1;

/**
 * PS1 raster clock and blank-signal source. GPU display-range registers can
 * replace the provisional windows; this class does not render pixels.
 */
public final class Ps1VideoTiming {
    public enum Region {
        NTSC(263, 240, 34136, 53693180L, 33868800L),
        PAL(314, 288, 34061, 11L, 7L);

        final int scanlines;
        final int defaultActiveLines;
        final int lineLengthTenths;
        final long videoClockNumerator;
        final long videoClockDenominator;

        Region(int scanlines, int defaultActiveLines, int lineLengthTenths,
               long videoClockNumerator, long videoClockDenominator) {
            this.scanlines = scanlines;
            this.defaultActiveLines = defaultActiveLines;
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
    private int displayStartLine;
    private int displayLineCount;
    private int hblankStartTenths;
    private int hblankDurationTenths;
    private boolean hBlank;
    private boolean vBlank;

    public Ps1VideoTiming(Ps1Timers timers) { this(timers, Region.NTSC); }

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
    public int getVblankStartLine() { return (displayStartLine + displayLineCount) % region.scanlines; }
    public int getDisplayStartLine() { return displayStartLine; }
    public int getDisplayLineCount() { return displayLineCount; }

    /** Change standard and restart raster phase; callers may then restore GPU range registers. */
    public void setRegion(Region newRegion) {
        if (newRegion == null) throw new IllegalArgumentException("region required");
        region = newRegion;
        line = 0;
        phaseTenths = 0;
        frameCount = 0;
        videoClockRemainder = 0;
        displayStartLine = 0;
        displayLineCount = region.defaultActiveLines;
        hblankStartTenths = (region.lineLengthTenths * 7) / 8;
        hblankDurationTenths = region.lineLengthTenths - hblankStartTenths;
        hBlank = false;
        vBlank = false;
        timers.setHBlank(false);
        timers.setVBlank(false);
        updateVblankState();
    }

    /** Change video standard while retaining relative raster position. */
    public void changeRegionPreservingPhase(Region newRegion) {
        if (newRegion == null) throw new IllegalArgumentException("region required");
        if (newRegion == region) return;
        Region oldRegion = region;
        line = (int) ((long) line * newRegion.scanlines / oldRegion.scanlines);
        phaseTenths = (int) ((long) phaseTenths * newRegion.lineLengthTenths / oldRegion.lineLengthTenths);
        videoClockRemainder = 0;
        region = newRegion;
        displayStartLine = Math.floorMod(displayStartLine, region.scanlines);
        displayLineCount = Math.min(displayLineCount, region.scanlines);
        hblankStartTenths = (region.lineLengthTenths * 7) / 8;
        hblankDurationTenths = region.lineLengthTenths - hblankStartTenths;
        updateVblankState();
        updateHblankState();
    }

    /** Configure active lines [0, firstVBlankLine) for tests or a simple profile. */
    public void setVblankStartLine(int firstVBlankLine) {
        if (firstVBlankLine <= 0 || firstVBlankLine >= region.scanlines) {
            throw new IllegalArgumentException("VBlank start must be inside the frame");
        }
        setVerticalDisplayRange(0, firstVBlankLine);
    }

    /** Set GP1(07h)'s display-line range, relative to the start of the frame. */
    public void setVerticalDisplayRange(int startLine, int endLine) {
        displayStartLine = Math.floorMod(startLine, region.scanlines);
        int normalizedEnd = Math.floorMod(endLine, region.scanlines);
        displayLineCount = Math.floorMod(normalizedEnd - displayStartLine, region.scanlines);
        updateVblankState();
    }

    /** Configure an HBlank window; it may wrap across the end of a scanline. */
    public void setHorizontalBlankWindow(int startCycle, int durationCycles) {
        int lineCycles = region.lineLengthTenths / TENTHS_PER_VIDEO_CYCLE;
        if (startCycle < 0 || startCycle >= lineCycles || durationCycles < 0
                || durationCycles > lineCycles) {
            throw new IllegalArgumentException("HBlank window is outside the scanline");
        }
        hblankStartTenths = startCycle * TENTHS_PER_VIDEO_CYCLE;
        hblankDurationTenths = durationCycles * TENTHS_PER_VIDEO_CYCLE;
        updateHblankState();
    }

    /** Convert GP1(06h)'s horizontal range into its complementary HBlank window. */
    public void setHorizontalDisplayRange(int startCycle, int endCycle) {
        int lineCycles = region.lineLengthTenths / TENTHS_PER_VIDEO_CYCLE;
        int start = Math.floorMod(startCycle, lineCycles);
        int end = Math.floorMod(endCycle, lineCycles);
        int activeWidth = Math.floorMod(end - start, lineCycles);
        setHorizontalBlankWindow(end, lineCycles - activeWidth);
    }

    /** Advance by GPU/video clock cycles; blank edges are delivered to the root counters. */
    public void advanceVideoTicks(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must be non-negative");
        for (long i = 0; i < ticks; i++) advanceOneVideoTick();
    }

    /**
     * Advance system-clock cycles and corresponding video clock. Timers are
     * ticked cycle-by-cycle so HBlank/VBlank gates are observed at boundaries.
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
        boolean newState = false;
        if (hblankDurationTenths > 0) {
            int distance = phaseTenths - hblankStartTenths;
            if (distance < 0) distance += region.lineLengthTenths;
            newState = distance < hblankDurationTenths;
        }
        if (newState != hBlank) {
            hBlank = newState;
            timers.setHBlank(hBlank);
        }
    }

    private void updateVblankState() {
        int distance = line - displayStartLine;
        if (distance < 0) distance += region.scanlines;
        boolean newState = distance >= displayLineCount;
        if (newState != vBlank) {
            vBlank = newState;
            timers.setVBlank(vBlank);
        }
    }
}
