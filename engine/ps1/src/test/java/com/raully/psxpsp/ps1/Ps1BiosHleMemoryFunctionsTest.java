package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Ps1BiosHleMemoryFunctionsTest {
    private static final int BASE = 0x80012000;

    @Test
    public void copyAndFillServicesHandleOverlapAndReturnConventions() {
        Ps1Machine machine = new Ps1Machine();
        for (int i = 0; i < 8; i++) machine.getBus().write8(BASE + i, i + 1);

        assertEquals(0, invokeA0(machine, 0x27, BASE, BASE + 2, 6)); // bcopy, overlapping
        int[] afterBcopy = {1, 2, 1, 2, 3, 4, 5, 6};
        assertBytes(machine, BASE, afterBcopy);

        assertEquals(BASE + 16, invokeA0(machine, 0x2A, BASE + 16, BASE, 8)); // memcpy
        assertBytes(machine, BASE + 16, new int[]{1, 2, 1, 2, 3, 4, 5, 6});
        assertEquals(BASE + 16, invokeA0(machine, 0x2B, BASE + 16, 0x1AB, 8)); // memset
        assertBytes(machine, BASE + 16, new int[]{0xAB, 0xAB, 0xAB, 0xAB, 0xAB, 0xAB, 0xAB, 0xAB});

        assertEquals(0, invokeA0(machine, 0x28, BASE + 16, 8)); // bzero
        assertBytes(machine, BASE + 16, new int[]{0, 0, 0, 0, 0, 0, 0, 0});
    }

    @Test
    public void memmoveMemcmpAndMemchrUseUnsignedBytes() {
        Ps1Machine machine = new Ps1Machine();
        for (int i = 0; i < 8; i++) machine.getBus().write8(BASE + i, i + 1);

        assertEquals(BASE + 2, invokeA0(machine, 0x2C, BASE + 2, BASE, 6)); // memmove overlap
        assertBytes(machine, BASE, new int[]{1, 2, 1, 2, 3, 4, 5, 6});
        assertEquals(-4, invokeA0(machine, 0x2D, BASE, BASE + 6, 1)); // memcmp
        assertEquals(BASE + 4, invokeA0(machine, 0x2E, BASE, 3, 8)); // memchr
        assertEquals(0, invokeA0(machine, 0x2E, BASE, 0xFF, 8));
    }

    @Test
    public void invalidMemoryPointerReturnsFailureInsteadOfCrashingHle() {
        Ps1Machine machine = new Ps1Machine();
        assertEquals(-1, invokeA0(machine, 0x2A, 0x1F802000, BASE, 1));
    }

    private static void assertBytes(Ps1Machine machine, int address, int[] expected) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte at offset " + i, expected[i], machine.getBus().read8(address + i));
        }
    }

    private static int invokeA0(Ps1Machine machine, int function, int a0, int a1, int a2) {
        MipsR3000A cpu = machine.getCpu();
        cpu.reset(0x800000A0);
        cpu.setRegister(9, function);
        cpu.setRegister(4, a0);
        cpu.setRegister(5, a1);
        cpu.setRegister(6, a2);
        cpu.setRegister(31, 0x80001000);
        machine.step();
        assertEquals(0x80001000, cpu.getPc());
        return cpu.getRegister(2);
    }
}
