package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Ps1BiosHleStringTest {
    private static final int BASE = 0x80014000;

    @Test
    public void strlenStrcpyAndComparisonsUseNulTerminatedStrings() {
        Ps1Machine machine = new Ps1Machine();
        writeString(machine, BASE, "PS1");
        writeString(machine, BASE + 0x100, "PS1X");

        assertEquals(3, invokeA0(machine, 0x1B, BASE, 0, 0)); // strlen
        assertEquals(BASE + 0x200, invokeA0(machine, 0x19, BASE + 0x200, BASE, 0)); // strcpy
        assertEquals("PS1", readString(machine, BASE + 0x200));
        assertEquals(0, invokeA0(machine, 0x17, BASE, BASE + 0x200, 0)); // strcmp
        assertEquals(0, invokeA0(machine, 0x18, BASE, BASE + 0x100, 3)); // strncmp
        assertEquals(-88, invokeA0(machine, 0x18, BASE, BASE + 0x100, 4));
    }

    @Test
    public void concatenationBoundedCopyAndCharacterSearchWork() {
        Ps1Machine machine = new Ps1Machine();
        writeString(machine, BASE, "Play");
        writeString(machine, BASE + 0x40, "Station");
        assertEquals(BASE, invokeA0(machine, 0x15, BASE, BASE + 0x40, 0)); // strcat
        assertEquals("PlayStation", readString(machine, BASE));

        writeString(machine, BASE + 0x80, "One");
        writeString(machine, BASE + 0xC0, "Portable");
        assertEquals(BASE + 0x80, invokeA0(machine, 0x16, BASE + 0x80, BASE + 0xC0, 3)); // strncat
        assertEquals("OnePor", readString(machine, BASE + 0x80));

        writeString(machine, BASE + 0x100, "PS1");
        int destination = BASE + 0x140;
        assertEquals(destination, invokeA0(machine, 0x1A, destination, BASE + 0x100, 6)); // strncpy
        assertEquals('P', machine.getBus().read8(destination));
        assertEquals('S', machine.getBus().read8(destination + 1));
        assertEquals('1', machine.getBus().read8(destination + 2));
        assertEquals(0, machine.getBus().read8(destination + 3));
        assertEquals(0, machine.getBus().read8(destination + 5));
    }

    @Test
    public void characterSearchCaseConversionAndUnimplementedCallsAreExplicit() {
        Ps1Machine machine = new Ps1Machine();
        writeString(machine, BASE, "Mississippi");
        assertEquals(BASE + 2, invokeA0(machine, 0x1E, BASE, 's', 0)); // strchr
        assertEquals(BASE + 6, invokeA0(machine, 0x1F, BASE, 's', 0)); // strrchr
        assertEquals(BASE + 11, invokeA0(machine, 0x1C, BASE, 0, 0)); // index of NUL
        assertEquals('A', invokeA0(machine, 0x25, 'a', 0, 0)); // toupper
        assertEquals('z', invokeA0(machine, 0x26, 'Z', 0, 0)); // tolower
        assertEquals(-1, invokeA0(machine, 0x23, BASE, BASE, 0)); // strtok needs stateful support
    }

    private static void writeString(Ps1Machine machine, int address, String value) {
        for (int i = 0; i < value.length(); i++) machine.getBus().write8(address + i, value.charAt(i));
        machine.getBus().write8(address + value.length(), 0);
    }

    private static String readString(Ps1Machine machine, int address) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            int character = machine.getBus().read8(address + i);
            if (character == 0) return value.toString();
            value.append((char) character);
        }
        throw new AssertionError("string has no NUL terminator within test limit");
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
