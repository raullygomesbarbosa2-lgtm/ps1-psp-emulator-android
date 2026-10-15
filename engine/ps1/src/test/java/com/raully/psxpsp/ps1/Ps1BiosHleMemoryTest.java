package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class Ps1BiosHleMemoryTest {
    private static final int HEAP_BASE = 0x80010000;

    @Test
    public void initHeapMallocAndFreeReuseAlignedMemory() {
        Ps1Machine machine = new Ps1Machine();
        assertEquals(0, invokeA0(machine, 0x39, HEAP_BASE + 1, 0x400, 0, 0));

        int first = invokeA0(machine, 0x33, 7, 0, 0, 0);
        assertEquals(HEAP_BASE + 4, first);
        machine.getBus().write32(first, 0x12345678);
        assertEquals(0, invokeA0(machine, 0x34, first, 0, 0, 0));
        int reused = invokeA0(machine, 0x33, 8, 0, 0, 0);
        assertEquals(first, reused);
        assertEquals(0x12345678, machine.getBus().read32(reused));
    }

    @Test
    public void callocZeroesMemoryAndReallocPreservesContents() {
        Ps1Machine machine = new Ps1Machine();
        assertEquals(0, invokeA0(machine, 0x39, HEAP_BASE, 0x100, 0, 0));
        machine.getBus().write8(HEAP_BASE, 0x55);

        int zeroed = invokeA0(machine, 0x37, 3, 5, 0, 0);
        assertEquals(HEAP_BASE, zeroed);
        for (int i = 0; i < 15; i++) assertEquals(0, machine.getBus().read8(zeroed + i));

        int data = invokeA0(machine, 0x33, 8, 0, 0, 0);
        assertNotEquals(0, data);
        for (int i = 0; i < 8; i++) machine.getBus().write8(data + i, i + 1);
        int expanded = invokeA0(machine, 0x38, data, 32, 0, 0);
        assertEquals(data, expanded);
        for (int i = 0; i < 8; i++) assertEquals(i + 1, machine.getBus().read8(expanded + i));
    }

    @Test
    public void invalidHeapRangeAndUnimplementedA0CallFailExplicitly() {
        Ps1Machine machine = new Ps1Machine();
        assertEquals(0, invokeA0(machine, 0x39, HEAP_BASE, 0x100, 0, 0));
        assertEquals(-1, invokeA0(machine, 0x39, 0x801FF000, 0x2000, 0, 0));
        assertEquals(-1, invokeA0(machine, 0x39, 0xFFFFFFFF, 0x20, 0, 0));
        assertEquals(HEAP_BASE, invokeA0(machine, 0x33, 16, 0, 0, 0));
        assertEquals(-1, invokeA0(machine, 0x00, 0, 0, 0, 0)); // FileOpen needs an unimplemented file system.
    }

    private static int invokeA0(Ps1Machine machine, int function, int a0, int a1, int a2, int a3) {
        MipsR3000A cpu = machine.getCpu();
        cpu.reset(0x800000A0); // KSEG0 alias of the BIOS A0 vector
        cpu.setRegister(9, function); // t1
        cpu.setRegister(4, a0);
        cpu.setRegister(5, a1);
        cpu.setRegister(6, a2);
        cpu.setRegister(7, a3);
        cpu.setRegister(31, 0x80001000); // ra
        machine.step();
        assertEquals(0x80001000, cpu.getPc());
        return cpu.getRegister(2); // v0
    }
}
