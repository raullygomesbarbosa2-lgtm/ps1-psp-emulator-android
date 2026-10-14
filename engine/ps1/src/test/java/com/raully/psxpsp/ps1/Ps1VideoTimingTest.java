package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Ps1VideoTimingTest {
    @Test
    public void regionProfilesExposeExpectedFrameLineCounts() {
        Ps1Machine machine = new Ps1Machine();
        Ps1VideoTiming timing = machine.getVideoTiming();
        assertEquals(Ps1VideoTiming.Region.NTSC, timing.getRegion());
        assertEquals(263, timing.getScanlinesPerFrame());
        assertEquals(240, timing.getVblankStartLine());

        timing.setRegion(Ps1VideoTiming.Region.PAL);
        assertEquals(314, timing.getScanlinesPerFrame());
        assertEquals(288, timing.getVblankStartLine());
        assertEquals(0, timing.getLine());
        assertEquals(0L, timing.getFrameCount());
    }

    @Test
    public void rasterHblankEdgesClockTimer1OncePerScanline() {
        Ps1Machine machine = new Ps1Machine();
        Ps1Timers timers = machine.getBus().getTimers();
        Ps1VideoTiming timing = machine.getVideoTiming();
        timers.writeRegister(Ps1Timers.BASE + Ps1Timers.TIMER_STRIDE + 4, 1 << 8, 0xFFFF);
        timing.setHorizontalBlankWindow(2, 1);

        timing.advanceVideoTicks(7000);

        assertEquals(3, timers.getCounter(1));
        assertFalse(timing.isHBlank());
        assertTrue(timing.getLine() >= 2);
    }

    @Test
    public void vblankGatesTimer1AndIsDrivenBySystemCycles() {
        Ps1Machine machine = new Ps1Machine();
        Ps1VideoTiming timing = machine.getVideoTiming();
        Ps1Timers timers = machine.getBus().getTimers();
        timing.setVblankStartLine(1);
        timers.writeRegister(Ps1Timers.BASE + Ps1Timers.TIMER_STRIDE + 4, 1 | (2 << 1), 0xFFFF);

        machine.tickSystemCycles(2300);

        assertTrue(timing.getLine() >= 1);
        assertTrue(timing.isVBlank());
        assertTrue(timers.getCounter(1) > 0);
        assertTrue(timers.getCounter(1) < 2300);
        machine.tickSystemCycles(100);
        assertTrue(timers.getCounter(1) > 0);
    }

    @Test
    public void systemClockAdvancesTimer2AndVideoRasterTogether() {
        Ps1Machine machine = new Ps1Machine();
        machine.tickSystemCycles(123);
        assertEquals(123, machine.getBus().getTimers().getCounter(2));
        assertEquals(0, machine.getVideoTiming().getLine());

        machine.tickSystemCycles(2200);
        assertTrue(machine.getVideoTiming().getLine() >= 1);
    }
}
