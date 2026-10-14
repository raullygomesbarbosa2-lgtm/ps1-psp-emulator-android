package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Ps1InterruptControllerTest {
    @Test
    public void maskGatesPendingSourcesAndIStatUsesWriteZeroToClear() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1InterruptController irq = bus.getInterruptController();
        irq.request(Ps1InterruptController.IRQ_VBLANK);

        assertEquals(1, bus.read32(Ps1MemoryBus.INTERRUPT_STATUS));
        assertFalse(bus.isInterruptPending());
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1);
        assertTrue(bus.isInterruptPending());

        bus.write32(Ps1MemoryBus.INTERRUPT_STATUS, 1); // one leaves the bit set
        assertEquals(1, bus.read32(Ps1MemoryBus.INTERRUPT_STATUS));
        bus.write32(Ps1MemoryBus.INTERRUPT_STATUS, 0xFFFFFFFE); // zero clears bit 0
        assertEquals(0, bus.read32(Ps1MemoryBus.INTERRUPT_STATUS));
        assertFalse(bus.isInterruptPending());
    }

    @Test
    public void memoryControlRegistersExposeCommonBiosValuesAndFixedBaseByte() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        assertEquals(0x1F000000, bus.read32(Ps1MemoryControl.EXPANSION1_BASE));
        assertEquals(0x0013243F, bus.read32(Ps1MemoryControl.EXPANSION1_DELAY));
        assertEquals(0x00000B88, bus.read32(Ps1MemoryControl.RAM_SIZE));

        bus.write32(Ps1MemoryControl.EXPANSION1_BASE, 0xFF123456);
        assertEquals(0x1F123456, bus.read32(Ps1MemoryControl.EXPANSION1_BASE));
    }

    @Test
    public void cpuTakesEnabledExternalIrqAndRecordsInterruptException() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_VBLANK);
        bus.getInterruptController().request(Ps1InterruptController.IRQ_VBLANK);
        bus.write32(0, i(0x0D, 0, 1, 0x0401)); // ORI r1,r0, IEc | IM2
        bus.write32(4, mtc0(1, 12));           // MTC0 r1,Status
        bus.write32(8, 0);                     // would execute only if no IRQ
        machine.startAt(0x80000000);

        machine.step();
        machine.step();
        machine.step();

        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, machine.getCpu().getPc());
        assertEquals(0x80000008, machine.getCpu().getCp0Register(14));
        assertEquals(0, (machine.getCpu().getCp0Register(13) >>> 2) & 0x1F);
        assertTrue((machine.getCpu().getCp0Register(13) & (1 << 10)) != 0);
    }

    @Test
    public void pendingIrqWaitsUntilAfterBranchDelaySlot() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1);
        bus.write32(0, i(0x0D, 0, 1, 0x0401)); // ORI r1,r0, IEc | IM2
        bus.write32(4, mtc0(1, 12));           // MTC0 r1,Status
        bus.write32(8, i(0x04, 0, 0, 1));      // BEQ r0,r0,+1
        bus.write32(12, i(0x09, 0, 2, 7));    // required delay slot
        bus.write32(16, 0);                    // target
        machine.startAt(0x80000000);
        machine.step(); machine.step(); machine.step();
        bus.getInterruptController().request(Ps1InterruptController.IRQ_VBLANK);

        machine.step(); // delay slot executes; IRQ is deferred.
        assertEquals(7, machine.getCpu().getRegister(2));
        assertEquals(0x80000010, machine.getCpu().getPc());
        machine.step(); // interrupt is accepted at target boundary.

        assertEquals(0x80000010, machine.getCpu().getCp0Register(14));
        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, machine.getCpu().getPc());
    }

    private static int i(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }

    private static int mtc0(int rt, int rd) {
        return (0x10 << 26) | (4 << 21) | (rt << 16) | (rd << 11);
    }
}
