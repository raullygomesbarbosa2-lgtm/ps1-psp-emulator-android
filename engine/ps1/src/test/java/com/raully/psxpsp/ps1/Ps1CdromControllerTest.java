package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public class Ps1CdromControllerTest {
    @Test
    public void cdromRegistersReturnCommandResponseAndRaiseEnabledSystemInterrupt() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write16(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_CDROM);
        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 1 << 2); // Enable CD-ROM INT3.
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x01); // Getstat.

        assertTrue(bus.isInterruptPending());
        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        assertEquals(1, bus.read8(Ps1MemoryBus.CDROM_BASE) & 3);
        assertEquals(1 << 2, bus.read8(Ps1MemoryBus.CDROM_BASE + 3) & 0x1F);
        assertEquals(0, bus.read8(Ps1MemoryBus.CDROM_BASE + 1)); // No disc inserted.
        bus.write8(Ps1MemoryBus.CDROM_BASE + 3, 1 << 2); // W1C INT3.
        assertEquals(0, bus.read8(Ps1MemoryBus.CDROM_BASE + 3) & 7);
    }

    @Test
    public void setlocReadNAndDataRequestDeliverDiscSectorThroughMappedPorts() throws Exception {
        byte[] imageBytes = new byte[3 * 2048];
        Arrays.fill(imageBytes, 0, 2048, (byte) 0x44);
        Arrays.fill(imageBytes, 2048, 4096, (byte) 0x55);
        Arrays.fill(imageBytes, 4096, 6144, (byte) 0x66);
        MemorySource source = new MemorySource(imageBytes);
        Ps1DiscImage disc = Ps1DiscImage.open(Ps1CueSheet.forIso("game.iso"), name -> source);
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.getCdrom().insertDisc(disc);
        bus.write16(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_CDROM);

        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x05); // Enable INT1 and INT3.
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x00); // Setloc 00:02:00 -> LBA 0.
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x02);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 2, 0x00);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x02); // Setloc.
        acknowledgeCurrentResponse(bus, 3);

        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x06); // ReadN.
        assertEquals(0x20, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x20);
        acknowledgeCurrentResponse(bus, 3);

        machine.tickSystemCycles(Ps1CdromController.SINGLE_SPEED_SECTOR_CYCLES);
        assertEquals(1, bus.read8(Ps1MemoryBus.CDROM_BASE + 3) & 0x1F);
        assertTrue(bus.isInterruptPending());
        acknowledgeCurrentResponse(bus, 1);
        assertEquals(0, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x40); // Data is gated until BFRD.

        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 3, 0x80);
        assertEquals(0x40, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x40);
        for (int i = 0; i < 2048; i++) {
            assertEquals(0x44, bus.read8(Ps1MemoryBus.CDROM_BASE + 2));
        }
        assertEquals(0, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x40);

        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 1, 0x06); // ReadN continues at the next LBA.
        acknowledgeCurrentResponse(bus, 3);
        machine.tickSystemCycles(Ps1CdromController.SINGLE_SPEED_SECTOR_CYCLES);
        acknowledgeCurrentResponse(bus, 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE, 0);
        assertEquals(0x40, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x40);
        for (int i = 0; i < 2048; i++) {
            assertEquals(0x55, bus.read8(Ps1MemoryBus.CDROM_BASE + 2));
        }
        assertEquals(0, bus.read8(Ps1MemoryBus.CDROM_BASE) & 0x40);
        assertFalse(source.closed);
        bus.getCdrom().ejectDisc();
        assertTrue(source.closed);
    }

    private static void acknowledgeCurrentResponse(Ps1MemoryBus bus, int expectedCode) {
        bus.write8(Ps1MemoryBus.CDROM_BASE, 1);
        assertEquals(1 << (expectedCode - 1), bus.read8(Ps1MemoryBus.CDROM_BASE + 3) & 0x1F);
        bus.read8(Ps1MemoryBus.CDROM_BASE + 1);
        bus.write8(Ps1MemoryBus.CDROM_BASE + 3, 1 << (expectedCode - 1));
    }

    private static final class MemorySource implements Ps1DiscImage.DataSource {
        private final byte[] bytes;
        private boolean closed;
        MemorySource(byte[] bytes) { this.bytes = bytes; }
        @Override public long size() { return bytes.length; }
        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            if (closed || position < 0 || position + length > bytes.length) throw new IOException("invalid read");
            System.arraycopy(bytes, (int) position, destination, offset, length);
        }
        @Override public void close() { closed = true; }
    }
}
