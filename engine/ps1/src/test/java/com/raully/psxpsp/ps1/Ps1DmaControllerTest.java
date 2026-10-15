package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public class Ps1DmaControllerTest {
    @Test
    public void cdromChannelWaitsForSectorThenTransfersWordsToRamAndRaisesDmaIrq() throws Exception {
        byte[] imageBytes = new byte[2048];
        Arrays.fill(imageBytes, (byte) 0x5A);
        Ps1DiscImage disc = Ps1DiscImage.open(Ps1CueSheet.forIso("game.iso"), name -> new MemorySource(imageBytes));
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.getCdrom().insertDisc(disc);
        bus.write16(Ps1MemoryBus.INTERRUPT_MASK,
                (1 << Ps1InterruptController.IRQ_CDROM) | (1 << Ps1InterruptController.IRQ_DMA));
        bus.write32(Ps1DmaController.DPCR_ADDRESS, 0x07654321 | (1 << (3 * 4 + 3)));
        bus.write32(Ps1DmaController.DICR_ADDRESS, (1 << 23) | (1 << (16 + 3)));
        enableCdromInt1AndInt3(bus);
        setlocLbaZero(bus);
        acknowledgeCdromResponse(bus, 3);

        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x06); // ReadN.
        acknowledgeCdromResponse(bus, 3);
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 3, 0x80); // BFRD: make sector data available.

        int channel3 = Ps1DmaController.BASE_ADDRESS + 3 * 0x10;
        bus.write32(channel3, 0x1000);
        bus.write32(channel3 + 4, 512);
        bus.write32(channel3 + 8, 0x11000000); // device -> RAM, manual mode, start + busy
        assertTrue(bus.getDma().isChannelActive(3));
        assertEquals(0, bus.read32(0x1000)); // Transfer is waiting for the first sector.

        machine.tickSystemCycles(Ps1CdromController.SINGLE_SPEED_SECTOR_CYCLES);

        assertFalse(bus.getDma().isChannelActive(3));
        assertEquals(0x5A5A5A5A, bus.read32(0x1000));
        assertEquals(0x5A5A5A5A, bus.read32(0x17FC));
        assertEquals(0x1800, bus.read32(channel3));
        assertTrue(bus.isInterruptPending());
        assertTrue((bus.read32(Ps1DmaController.DICR_ADDRESS) & (1 << 27)) != 0);

        bus.write32(Ps1DmaController.DICR_ADDRESS, 1 << 27); // W1C channel 3 completion flag.
        assertEquals(0, bus.read32(Ps1DmaController.DICR_ADDRESS) & (1 << 27));
    }

    @Test
    public void gpuDmaCopiesRamPacketIntoGp0Parser() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(0x1000, 0x020000FF); // GP0 fill-rectangle command and color.
        bus.write32(0x1004, 0x00000000); // xy
        bus.write32(0x1008, 0x00010001); // 1x1 dimensions
        bus.write32(Ps1DmaController.DPCR_ADDRESS, 0x07654321 | (1 << (2 * 4 + 3)));
        int channel2 = Ps1DmaController.BASE_ADDRESS + 2 * 0x10;
        bus.write32(channel2, 0x1000);
        bus.write32(channel2 + 4, 3);
        bus.write32(channel2 + 8, 0x11000001); // RAM -> GPU

        assertEquals(0x001F, bus.getGpu().readVramPixel(0, 0));
        assertFalse(bus.getDma().isChannelActive(2));
    }

    @Test
    public void manualDmaRequiresEnableTriggerAndEnabledDpcrChannel() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(0x1000, 0x020000FF);
        bus.write32(0x1004, 0x00000000);
        bus.write32(0x1008, 0x00010001);
        int channel2 = Ps1DmaController.BASE_ADDRESS + 2 * 0x10;
        bus.write32(channel2, 0x1000);
        bus.write32(channel2 + 4, 3);
        bus.write32(channel2 + 8, 0x01000001); // Enable only: DPCR is still disabled.
        assertEquals(0, bus.getGpu().readVramPixel(0, 0));
        assertFalse(bus.getDma().isChannelActive(2));

        bus.write32(Ps1DmaController.DPCR_ADDRESS, 0x07654321 | (1 << (2 * 4 + 3)));
        assertEquals(0, bus.getGpu().readVramPixel(0, 0)); // Manual mode also needs trigger.
        bus.write32(channel2 + 8, 0x11000001); // Enable + trigger, RAM -> GPU.
        assertEquals(0x001F, bus.getGpu().readVramPixel(0, 0));
        assertEquals(0, bus.read32(channel2 + 8) & ((1 << 24) | (1 << 28)));
    }

    private static void enableCdromInt1AndInt3(Ps1MemoryBus bus) {
        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x05);
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
    }

    private static void setlocLbaZero(Ps1MemoryBus bus) {
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x00);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x02);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x00);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x02); // Setloc 00:02:00 => LBA 0.
    }

    private static void acknowledgeCdromResponse(Ps1MemoryBus bus, int expected) {
        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        assertEquals(1 << (expected - 1), bus.read8(Ps1MemoryBus.CDROM_BASE + 3) & 0x1F);
        bus.read8(Ps1MemoryBus.CDROM_BASE + 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 3, 1 << (expected - 1));
    }

    private static final class MemorySource implements Ps1DiscImage.DataSource {
        private final byte[] data;
        MemorySource(byte[] data) { this.data = data; }
        @Override public long size() { return data.length; }
        @Override public void readFully(long position, byte[] target, int offset, int length) throws IOException {
            if (position < 0 || position + length > data.length) throw new IOException("out of range");
            System.arraycopy(data, (int) position, target, offset, length);
        }
        @Override public void close() { }
    }
}
