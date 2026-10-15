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
        assertEquals(0xFF000000, machine.renderDisplayFrame().getArgb(0, 0));
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
    public void gp0CopyVramMovesPixelsAndRasterizesPolygonPacket() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(10, 20, 0x1234);
        gpu.writeVramPixel(11, 20, 0x5678);
        // Flat triangle header plus three coordinates; this small triangle is rasterized.
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
    public void gp0FlatAndGouraudTrianglesRasterizeToVram() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        bus.write32(Ps1Gpu.DATA_PORT, 0x200000FF); // flat red triangle
        bus.write32(Ps1Gpu.DATA_PORT, xy(10, 10));
        bus.write32(Ps1Gpu.DATA_PORT, xy(14, 10));
        bus.write32(Ps1Gpu.DATA_PORT, xy(10, 14));
        assertEquals(0x001F, gpu.readVramPixel(11, 11));
        assertEquals(0, gpu.readVramPixel(13, 13));

        bus.write32(Ps1Gpu.DATA_PORT, 0x300000FF); // Gouraud RGB triangle
        bus.write32(Ps1Gpu.DATA_PORT, xy(20, 10));
        bus.write32(Ps1Gpu.DATA_PORT, 0x0000FF00); // green vertex
        bus.write32(Ps1Gpu.DATA_PORT, xy(24, 10));
        bus.write32(Ps1Gpu.DATA_PORT, 0x00FF0000); // blue vertex
        bus.write32(Ps1Gpu.DATA_PORT, xy(20, 14));
        int pixel = gpu.readVramPixel(21, 11);
        assertTrue((pixel & 0x1F) > 0);
        assertTrue(((pixel >>> 5) & 0x1F) > 0);
        assertTrue(((pixel >>> 10) & 0x1F) > 0);
    }

    @Test
    public void gp0LinesRectanglesAndDrawAreaOffsetAreApplied() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        bus.write32(Ps1Gpu.DATA_PORT, 0x400000FF); // red line
        bus.write32(Ps1Gpu.DATA_PORT, xy(2, 2));
        bus.write32(Ps1Gpu.DATA_PORT, xy(6, 2));
        assertEquals(0x001F, gpu.readVramPixel(4, 2));

        bus.write32(Ps1Gpu.DATA_PORT, 0xE3000000 | 12 | (12 << 10));
        bus.write32(Ps1Gpu.DATA_PORT, 0xE4000000 | 13 | (13 << 10));
        bus.write32(Ps1Gpu.DATA_PORT, 0xE5000000 | 2 | (2 << 11)); // +2,+2 draw offset
        bus.write32(Ps1Gpu.DATA_PORT, 0x6000FF00); // variable green rectangle
        bus.write32(Ps1Gpu.DATA_PORT, xy(10, 10));
        bus.write32(Ps1Gpu.DATA_PORT, 5 | (5 << 16));
        assertEquals(0x03E0, gpu.readVramPixel(12, 12));
        assertEquals(0x03E0, gpu.readVramPixel(13, 13));
        assertEquals(0, gpu.readVramPixel(14, 13)); // draw-area clipping
    }

    @Test
    public void flatPolylineStopsAtTerminatorAndRendersSegments() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        bus.write32(Ps1Gpu.DATA_PORT, 0x480000FF);
        bus.write32(Ps1Gpu.DATA_PORT, xy(30, 10));
        bus.write32(Ps1Gpu.DATA_PORT, xy(34, 10));
        bus.write32(Ps1Gpu.DATA_PORT, xy(34, 14));
        bus.write32(Ps1Gpu.DATA_PORT, 0x50005000);
        bus.write32(Ps1Gpu.DATA_PORT, 0x0200FF00); // following command must not be swallowed
        bus.write32(Ps1Gpu.DATA_PORT, xy(40, 20));
        bus.write32(Ps1Gpu.DATA_PORT, 1 | (1 << 16));

        assertEquals(0x001F, gpu.readVramPixel(32, 10));
        assertEquals(0x001F, gpu.readVramPixel(34, 12));
        assertEquals(0x03E0, gpu.readVramPixel(40, 20));
    }

    @Test
    public void displayFrameConvertsRgb555AndPackedRgb24FromVram() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(0, 0, 0x001F); // red
        gpu.writeVramPixel(1, 0, 0x03E0); // green
        gpu.writeVramPixel(0, 1, 0x7C00); // blue
        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x07, 2 << 10));
        Ps1VideoFrame rgb555 = gpu.renderDisplayFrame();
        assertEquals(256, rgb555.getWidth());
        assertEquals(2, rgb555.getHeight());
        assertEquals(0xFFFF0000, rgb555.getArgb(0, 0));
        assertEquals(0xFF00FF00, rgb555.getArgb(1, 0));
        assertEquals(0xFF0000FF, rgb555.getArgb(0, 1));

        gpu.writeVramPixel(0, 0, 0x3412); // bytes 12h,34h
        gpu.writeVramPixel(1, 0, 0x0056); // byte 56h
        bus.write32(Ps1Gpu.CONTROL_PORT, gp1(0x08, 1 << 4)); // 24-bit display
        assertEquals(0xFF123456, gpu.renderDisplayFrame().getArgb(0, 0));
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

    @Test
    public void texturedTrianglesSample4BitClutAnd15BitVramPages() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(0, 0, 0x0010); // 4-bit texel index 1 at U=1
        gpu.writeVramPixel(17, 0, 0x001F); // CLUT index 1: red
        writeTexturedTriangle(bus, 0x24808080, 50, 10, 0x00010001, 0x00000001);
        assertEquals(0x001F, gpu.readVramPixel(51, 11));

        Ps1MemoryBus directBus = new Ps1MemoryBus();
        Ps1Gpu directGpu = directBus.getGpu();
        directGpu.writeVramPixel(2, 0, 0x7C00); // direct 15-bit blue texel
        directBus.write32(Ps1Gpu.DATA_PORT, 0xE1000100); // texture depth = 15-bit
        writeTexturedTriangle(directBus, 0x24808080, 70, 10, 0x00000002, 0x01000002);
        assertEquals(0x7C00, directGpu.readVramPixel(71, 11));
    }

    @Test
    public void texturedTriangleSamples8BitClutAndHonorsSemiTransparency() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(0, 0, 0x0002); // 8-bit texel index 2 at U=0
        gpu.writeVramPixel(34, 0, 0x001F); // CLUT index 2: red
        bus.write32(Ps1Gpu.DATA_PORT, 0xE1000080); // texture depth = 8-bit
        writeTexturedTriangle(bus, 0x24808080, 90, 10, 0x00020000, 0x00800000);
        assertEquals(0x001F, gpu.readVramPixel(91, 11));

        Ps1MemoryBus blendBus = new Ps1MemoryBus();
        Ps1Gpu blendGpu = blendBus.getGpu();
        blendGpu.writeVramPixel(1, 0, 0x801F); // STP set: semitransparent red texel
        blendGpu.writeVramPixel(101, 11, 0x7C00); // blue destination
        blendBus.write32(Ps1Gpu.DATA_PORT, 0xE1000100); // direct 15-bit page, average blend
        writeTexturedTriangle(blendBus, 0x26808080, 100, 10, 0x00000001, 0x01000001);
        assertEquals(0xBC0F, blendGpu.readVramPixel(101, 11));
    }

    @Test
    public void texturedVariableRectangleReadsUvClutAndSize() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Gpu gpu = bus.getGpu();
        gpu.writeVramPixel(0, 0, 0x0011); // U=1 and U=2 are both palette index 1
        gpu.writeVramPixel(17, 0, 0x03E0); // CLUT index 1: green
        bus.write32(Ps1Gpu.DATA_PORT, 0x64808080); // variable-size textured rectangle
        bus.write32(Ps1Gpu.DATA_PORT, xy(120, 20));
        bus.write32(Ps1Gpu.DATA_PORT, 0x00010001); // CLUT 1, UV (1,0)
        bus.write32(Ps1Gpu.DATA_PORT, 2 | (2 << 16));

        assertEquals(0x03E0, gpu.readVramPixel(120, 20));
        assertEquals(0x03E0, gpu.readVramPixel(121, 21));
        assertEquals(0, gpu.readVramPixel(122, 21));
    }

    private static void writeTexturedTriangle(Ps1MemoryBus bus, int command,
                                              int x, int y, int uv0, int uv1) {
        bus.write32(Ps1Gpu.DATA_PORT, command);
        bus.write32(Ps1Gpu.DATA_PORT, xy(x, y));
        bus.write32(Ps1Gpu.DATA_PORT, uv0);
        bus.write32(Ps1Gpu.DATA_PORT, xy(x + 4, y));
        bus.write32(Ps1Gpu.DATA_PORT, uv1);
        bus.write32(Ps1Gpu.DATA_PORT, xy(x, y + 4));
        bus.write32(Ps1Gpu.DATA_PORT, uv1);
    }

    private static int xy(int x, int y) { return (x & 0x7FF) | ((y & 0x7FF) << 16); }

    private static int gp1(int command, int parameters) {
        return (command << 24) | (parameters & 0x00FFFFFF);
    }
}
