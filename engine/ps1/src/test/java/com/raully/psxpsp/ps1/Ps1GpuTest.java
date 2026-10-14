package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Ps1GpuTest {
    @Test
    public void gp1HorizontalRangeFeedsHblankAndTimer1() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        Ps1Gpu gpu = machine.getGpu();
        int x1 = 0;
        int x2 = 100;
        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x06, x1 | (x2 << 12)));
        bus.write32(Ps1Timers.BASE + Ps1Timers.TIMER_STRIDE + 4, 1 << 8); // Timer 1: HBlank

        machine.tickVideoClock(100);

        assertEquals(x1 | (x2 << 12), gpu.getHorizontalRange());
        assertEquals(1, bus.getTimers().getCounter(1));
        assertTrue(machine.getVideoTiming().isHBlank());
    }

    @Test
    public void gp1VerticalRangeControlsVblankEdges() {
        Ps1Machine machine = new Ps1Machine();
        int y1 = 0;
        int y2 = 1;
        machine.getBus().write32(Ps1Gpu.CONTROL_PORT, gp1(0x07, y1 | (y2 << 10)));

        assertFalse(machine.getVideoTiming().isVBlank());
        machine.tickVideoClock(3414);
        assertEquals(1, machine.getVideoTiming().getLine());
        assertTrue(machine.getVideoTiming().isVBlank());
    }

    @Test
    public void gp1DisplayModeChangesResolutionRegionAndStatusBits() {
        Ps1Machine machine = new Ps1Machine();
        int mode = 1 | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5); // 320x480 interlaced PAL
        machine.getBus().write32(Ps1Gpu.CONTROL_PORT, gp1(0x08, mode));
        int status = machine.getBus().read32(Ps1Gpu.CONTROL_PORT);

        assertEquals(320, machine.getGpu().getHorizontalResolution());
        assertEquals(480, machine.getGpu().getVerticalResolution());
        assertTrue(machine.getGpu().isPal());
        assertEquals(Ps1VideoTiming.Region.PAL, machine.getVideoTiming().getRegion());
        assertEquals(314, machine.getVideoTiming().getScanlinesPerFrame());
        assertEquals(1, (status >>> 17) & 3);
        assertEquals(0xF, (status >>> 19) & 0xF);
    }

    @Test
    public void displayEnableAndVramStartCommandsAreVisible() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x03, 1));
        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x05, 17 | (23 << 10)));

        assertTrue(machine.getGpu().isDisplayDisabled());
        assertEquals(17 | (23 << 10), machine.getGpu().getDisplayStart());
        assertTrue((bus.read32(Ps1Gpu.CONTROL_PORT) & (1 << 23)) != 0);
    }

    private static int gp1(int command, int parameters) {
        return (command << 24) | (parameters & 0x00FFFFFF);
    }
}
