package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Ps1BiosHleTest {
    @Test
    public void b0EventCallsOpenEnableDeliverAndConsumeEvent() {
        Ps1Machine machine = new Ps1Machine();
        int event = invokeB0(machine, 0x08, 0xF0000001, 0x1234, 0x2000, 0);
        assertEquals(0xF1000000, event);

        assertEquals(1, invokeB0(machine, 0x0C, event, 0, 0, 0)); // EnableEvent
        assertEquals(1, invokeB0(machine, 0x07, 0xF0000001, 0x1234, 0, 0)); // DeliverEvent
        assertEquals(1, invokeB0(machine, 0x0B, event, 0, 0, 0)); // TestEvent consumes readiness
        assertEquals(0, invokeB0(machine, 0x0B, event, 0, 0, 0));
        assertEquals(1, invokeB0(machine, 0x09, event, 0, 0, 0)); // CloseEvent
        assertEquals(0, invokeB0(machine, 0x0B, event, 0, 0, 0));
    }

    @Test
    public void unsupportedBiosFunctionReturnsMinusOneAndReturnsToCaller() {
        Ps1Machine machine = new Ps1Machine();
        int result = invokeB0(machine, 0xFE, 0, 0, 0, 0);
        assertEquals(-1, result);
        assertEquals(0x80001000, machine.getCpu().getPc());
    }

    private static int invokeB0(Ps1Machine machine, int function, int a0, int a1, int a2, int a3) {
        MipsR3000A cpu = machine.getCpu();
        cpu.reset(0x800000B0); // KSEG0 alias of the BIOS B0 vector
        cpu.setRegister(9, function); // t1
        cpu.setRegister(4, a0);
        cpu.setRegister(5, a1);
        cpu.setRegister(6, a2);
        cpu.setRegister(7, a3);
        cpu.setRegister(31, 0x80001000); // ra
        machine.step();
        return cpu.getRegister(2); // v0
    }
}
