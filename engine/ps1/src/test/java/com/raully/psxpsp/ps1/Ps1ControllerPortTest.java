package com.raully.psxpsp.ps1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class Ps1ControllerPortTest {
    @Test public void digitalPadPollReturnsIdAndActiveLowButtons() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.getControllerPort().setPressedButtons(Ps1ControllerPort.START | Ps1ControllerPort.CROSS);

        bus.write8(Ps1ControllerPort.DATA, 0x01);
        assertEquals(0xFF, bus.read8(Ps1ControllerPort.DATA));
        bus.write8(Ps1ControllerPort.DATA, 0x42);
        assertEquals(0x41, bus.read8(Ps1ControllerPort.DATA));
        bus.write8(Ps1ControllerPort.DATA, 0x00);
        assertEquals(0x5A, bus.read8(Ps1ControllerPort.DATA));
        bus.write8(Ps1ControllerPort.DATA, 0x00);
        assertEquals(0xF7, bus.read8(Ps1ControllerPort.DATA));
        bus.write8(Ps1ControllerPort.DATA, 0x00);
        assertEquals(0xBF, bus.read8(Ps1ControllerPort.DATA));
    }

    @Test public void touchAndPhysicalMasksCanBeCombinedAndReset() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.getControllerPort().setPressedButtons(Ps1ControllerPort.UP | Ps1ControllerPort.CIRCLE);
        assertEquals(Ps1ControllerPort.UP | Ps1ControllerPort.CIRCLE,
                bus.getControllerPort().getPressedButtons());
        bus.clear();
        assertEquals(0, bus.getControllerPort().getPressedButtons());
        assertEquals(0xFF, bus.read8(Ps1ControllerPort.DATA));
    }

    @Test public void dataRegisterWordAccessPollsLowByte() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.getControllerPort().setPressedButtons(Ps1ControllerPort.SELECT);
        bus.write32(Ps1ControllerPort.DATA, 0x00000001);
        assertEquals(0xFF, bus.read32(Ps1ControllerPort.DATA));
        bus.write32(Ps1ControllerPort.DATA, 0x00000042);
        assertEquals(0x41, bus.read32(Ps1ControllerPort.DATA));
        bus.write32(Ps1ControllerPort.DATA, 0);
        assertEquals(0x5A, bus.read32(Ps1ControllerPort.DATA));
        bus.write32(Ps1ControllerPort.DATA, 0);
        assertEquals(0xFE, bus.read32(Ps1ControllerPort.DATA));
    }
}
