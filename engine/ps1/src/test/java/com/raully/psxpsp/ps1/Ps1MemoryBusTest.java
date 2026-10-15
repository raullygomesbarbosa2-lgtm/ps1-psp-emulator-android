package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class Ps1MemoryBusTest {
    @Test
    public void ramMirrorsAcrossPhysicalKseg0AndKseg1Aliases() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(0x00000000, 0x13579BDF);

        assertEquals(0x13579BDF, bus.read32(0x00200000));
        assertEquals(0x13579BDF, bus.read32(0x80000000));
        assertEquals(0x13579BDF, bus.read32(0xA0000000));
    }

    @Test
    public void scratchpadIsAccessibleThroughKusegAndKseg0ButNotKseg1() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(Ps1MemoryBus.SCRATCHPAD_START, 0xCAFEBABE);

        assertEquals(0xCAFEBABE, bus.read32(0x1F800000));
        assertEquals(0xCAFEBABE, bus.read32(0x9F800000));
        try {
            bus.read32(0xBF800000);
            fail("scratchpad is not mapped in KSEG1");
        } catch (Ps1Bus.BusFaultException expected) {
            assertEquals(false, expected.isWrite());
        }
    }

    @Test
    public void unimplementedHardwareAddressReportsExplicitBusFault() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        try {
            bus.read32(0x1F801018); // unmapped hardware register
            fail("expected an explicit bus fault");
        } catch (Ps1Bus.BusFaultException fault) {
            assertEquals(0x1F801018, fault.getAddress());
            assertEquals(false, fault.isWrite());
        }
    }

    @Test
    public void machineRunsCpuInstructionsDirectlyFromMirroredRam() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.write32(0, i(0x09, 0, 1, 0x1234));       // ADDIU r1,r0,0x1234
        bus.write32(4, i(0x2B, 0, 1, 0x100));        // SW r1,0x100(r0)
        bus.write32(8, i(0x23, 0, 2, 0x100));        // LW r2,0x100(r0)
        bus.write32(12, r(2, 0, 3, 0, 0x21));       // load-delay slot
        bus.write32(16, r(2, 0, 4, 0, 0x21));       // sees loaded value
        machine.startAt(0x80000000);

        for (int n = 0; n < 5; n++) machine.step();

        assertEquals(0x1234, machine.getCpu().getRegister(4));
        assertEquals(0x1234, bus.read32(0xA0000100));
    }

    @Test
    public void cpuTurnsUnmappedDataReadIntoBusErrorException() {
        Ps1Machine machine = new Ps1Machine();
        machine.getBus().write32(0, i(0x23, 1, 2, 0)); // LW r2,0(r1)
        machine.startAt(0x80000000);
        machine.getCpu().setRegister(1, 0x1F801018);

        machine.step();

        assertEquals(6, (machine.getCpu().getCp0Register(13) >>> 2) & 0x1F);
        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, machine.getCpu().getPc());
    }

    @Test
    public void cpuTurnsUnmappedDataWriteIntoBusErrorException() {
        Ps1Machine machine = new Ps1Machine();
        machine.getBus().write32(0, i(0x2B, 1, 2, 0)); // SW r2,0(r1)
        machine.startAt(0x80000000);
        machine.getCpu().setRegister(1, 0x1F801018);

        machine.step();

        assertEquals(7, (machine.getCpu().getCp0Register(13) >>> 2) & 0x1F);
        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, machine.getCpu().getPc());
    }

    private static int i(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }

    private static int r(int rs, int rt, int rd, int shamt, int function) {
        return (rs << 21) | (rt << 16) | (rd << 11) | (shamt << 6) | function;
    }
}
