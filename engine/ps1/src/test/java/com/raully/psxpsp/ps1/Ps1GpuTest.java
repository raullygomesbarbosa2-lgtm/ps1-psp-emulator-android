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

    @Test
    public void gp0FillRectangleWritesRgb555PixelsToVram() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        bus.write32(Ps1Gpu.DATA_PORT, 0x020000FF); // fill, red (R in low byte)
        bus.write32(Ps1Gpu.DATA_PORT, 18 | (4 << 16)); // X,Y
        bus.write32(Ps1Gpu.DATA_PORT, 5 | (2 << 16)); // width,height

        assertEquals(0x001F, gpu.readVramPixel(16, 4)); // X aligns down to 16
        assertEquals(0x001F, gpu.readVramPixel(31, 5)); // width rounds up to 16
        assertEquals(0, gpu.readVramPixel(32, 4));
        assertEquals(0, gpu.readVramPixel(16, 6));
    }

    @Test
    public void gp0ImageUploadAndReadbackTransferPackedPixels() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        bus.write32(Ps1Gpu.DATA_PORT, 0xA0000000);
        bus.write32(Ps1Gpu.DATA_PORT, 3 | (2 << 16)); // destination
        bus.write32(Ps1Gpu.DATA_PORT, 3 | (1 << 16)); // 3x1 pixels (odd pixel count)
        bus.write32(Ps1Gpu.DATA_PORT, 0x22221111);
        bus.write32(Ps1Gpu.DATA_PORT, 0x00003333);

        assertFalse(gpu.isImageUploadActive());
        assertEquals(0x1111, gpu.readVramPixel(3, 2));
        assertEquals(0x2222, gpu.readVramPixel(4, 2));
        assertEquals(0x3333, gpu.readVramPixel(5, 2));
        assertEquals(0, gpu.readVramPixel(3, 3));

        bus.write32(Ps1Gpu.DATA_PORT, 0xC0000000);
        bus.write32(Ps1Gpu.DATA_PORT, 3 | (2 << 16)); // source
        bus.write32(Ps1Gpu.DATA_PORT, 3 | (1 << 16)); // 3x1 pixels
        assertTrue((bus.read32(Ps1Gpu.CONTROL_PORT) & (1 << 27)) != 0);
        assertEquals(0x22221111, bus.read32(Ps1Gpu.DATA_PORT));
        assertEquals(0x00003333, bus.read32(Ps1Gpu.DATA_PORT));
        assertFalse(gpu.hasReadbackData());
        assertEquals(0, gpu.readVramPixel(6, 2));
    }

    @Test
    public void gp0CopyVramMovesPixelsAndUnknownPrimitivePacketsStaySynchronized() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(10, 20, 0x1234);
        gpu.writeVramPixel(11, 20, 0x5678);
        // Flat triangle header plus three coordinates; rasterization is still pending.
        bus.write32(Ps1Gpu.DATA_PORT, 0x20000000);
        bus.write32(Ps1Gpu.DATA_PORT, 0x00010001);
        bus.write32(Ps1Gpu.DATA_PORT, 0x00020002);
        bus.write32(Ps1Gpu.DATA_PORT, 0x00030003);
        // Copy two pixels from (10,20) to (30,40).
        bus.write32(Ps1Gpu.DATA_PORT, 0x80000000);
        bus.write32(Ps1Gpu.DATA_PORT, 10 | (20 << 16));
        bus.write32(Ps1Gpu.DATA_PORT, 30 | (40 << 16));
        bus.write32(Ps1Gpu.DATA_PORT, 2 | (1 << 16));

        assertEquals(0x1234, gpu.readVramPixel(30, 40));
        assertEquals(0x5678, gpu.readVramPixel(31, 40));
    }

    @Test
    public void gp0IrqRequestIsLatchedAndAcknowledgedByGp1() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(Ps1Gpu.DATA_PORT, 0x1F000000);
        assertTrue((bus.read32(Ps1Gpu.CONTROL_PORT) & (1 << 24)) != 0);
        assertTrue((bus.getInterruptController().getStatus()
                & (1 << Ps1InterruptController.IRQ_GPU)) != 0);

        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x02, 0));
        assertEquals(0, bus.read32(Ps1Gpu.CONTROL_PORT) & (1 << 24));
        assertTrue((bus.getInterruptController().getStatus()
                & (1 << Ps1InterruptController.IRQ_GPU)) != 0); // I_STAT has its own acknowledge
    }

    private static int gp1(int command, int parameters) {
        return (command << 24) | (parameters & 0x00FFFFFF);
    }
}
