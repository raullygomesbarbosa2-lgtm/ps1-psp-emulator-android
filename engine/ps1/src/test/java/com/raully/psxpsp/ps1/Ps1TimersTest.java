package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Ps1TimersTest {
    @Test
    public void targetMatchResetsCounterSetsFlagAndRequestsTimer0Irq() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Timers timers = bus.getTimers();
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_TIMER0);
        bus.write32(Ps1Timers.BASE + 8, 3); // Timer 0 target
        bus.write32(Ps1Timers.BASE + 4, (1 << 3) | (1 << 4) | (1 << 6)); // reset at target, IRQ, repeat

        timers.tickSystemCycles(3);

        assertEquals(0, timers.getCounter(0));
        assertTrue((timers.getModeWithoutSideEffects(0) & (1 << 11)) != 0);
        assertTrue(bus.isInterruptPending());
        assertTrue((bus.read32(Ps1Timers.BASE + 4) & (1 << 11)) != 0); // mode read acknowledges flags
        assertEquals(0, timers.getModeWithoutSideEffects(0) & (1 << 11));

        timers.tickSystemCycles(1);
        assertTrue((timers.getModeWithoutSideEffects(0) & (1 << 10)) != 0); // pulse re-armed
        assertEquals(1, timers.getCounter(0));
        bus.write32(Ps1MemoryBus.INTERRUPT_STATUS, ~(1 << Ps1InterruptController.IRQ_TIMER0));
        assertFalse(bus.isInterruptPending());
    }

    @Test
    public void reachingFFFFSetsOverflowFlagAndCanRequestIrq() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Timers timers = bus.getTimers();
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_TIMER0);
        bus.write32(Ps1Timers.BASE + 4, 1 << 5); // IRQ on FFFF
        bus.write32(Ps1Timers.BASE, 0xFFFE);

        timers.tickSystemCycles(1);

        assertEquals(0, timers.getCounter(0)); // default reset at FFFF
        assertTrue((timers.getModeWithoutSideEffects(0) & (1 << 12)) != 0);
        assertTrue(bus.isInterruptPending());
    }

    @Test
    public void timer2CanUseSystemClockDividedByEight() {
        Ps1Timers timers = new Ps1MemoryBus().getTimers();
        int timer2Mode = Ps1Timers.BASE + 2 * Ps1Timers.TIMER_STRIDE + 4;
        timers.writeRegister(timer2Mode, 2 << 8, 0xFFFF);

        timers.tickSystemCycles(7);
        assertEquals(0, timers.getCounter(2));
        timers.tickSystemCycles(1);
        assertEquals(1, timers.getCounter(2));
    }

    @Test
    public void dotClockAndHBlankCanDriveTheirSelectedCounters() {
        Ps1Timers timers = new Ps1MemoryBus().getTimers();
        // The GPU's default GP1 display range puts the initial raster phase in HBlank.
        timers.setHBlank(false);
        timers.setVBlank(false);
        timers.writeRegister(Ps1Timers.BASE + 4, 1 << 8, 0xFFFF); // Timer 0 dotclock
        timers.writeRegister(Ps1Timers.BASE + Ps1Timers.TIMER_STRIDE + 4, 1 << 8, 0xFFFF); // Timer 1 HBlank

        timers.tickSystemCycles(10);
        timers.tickDotClocks(5);
        timers.setHBlank(true);
        timers.setHBlank(false);
        timers.setHBlank(true);

        assertEquals(5, timers.getCounter(0));
        assertEquals(2, timers.getCounter(1));
    }

    @Test
    public void synchronizationModesPauseAndResumeAtBlankBoundaries() {
        Ps1Timers timers = new Ps1MemoryBus().getTimers();
        // Start from an explicit non-blank state rather than the GP1 default raster phase.
        timers.setHBlank(false);
        timers.setVBlank(false);
        timers.writeRegister(Ps1Timers.BASE + 4, 1, 0xFFFF); // Timer 0: pause during HBlank
        int timer1Mode = Ps1Timers.BASE + Ps1Timers.TIMER_STRIDE + 4;
        timers.writeRegister(timer1Mode, 1 | (2 << 1), 0xFFFF); // Timer 1: reset and count only in VBlank

        timers.tickSystemCycles(2);
        assertEquals(2, timers.getCounter(0));
        assertEquals(0, timers.getCounter(1));
        timers.setHBlank(true);
        timers.setVBlank(true);
        timers.tickSystemCycles(3);
        assertEquals(2, timers.getCounter(0));
        assertEquals(3, timers.getCounter(1));
        timers.setHBlank(false);
        timers.setVBlank(false);
        timers.tickSystemCycles(1);
        assertEquals(3, timers.getCounter(0));
        assertEquals(3, timers.getCounter(1));
    }

    @Test
    public void timerRegistersAreMappedThroughBusAndHighBitsReadAsZero() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        bus.write32(Ps1Timers.BASE, 0xABCD1234);
        assertEquals(0x00001234, bus.read32(Ps1Timers.BASE));
        assertEquals(0x1234, bus.read16(Ps1Timers.BASE));
        assertEquals(0x34, bus.read8(Ps1Timers.BASE));
    }

    @Test
    public void timerIrqReachesTheCpuThroughTheInterruptController() {
        Ps1Machine machine = new Ps1Machine();
        Ps1MemoryBus bus = machine.getBus();
        bus.write32(Ps1MemoryBus.INTERRUPT_MASK, 1 << Ps1InterruptController.IRQ_TIMER0);
        bus.write32(Ps1Timers.BASE + 8, 1);
        bus.write32(Ps1Timers.BASE + 4, (1 << 4) | (1 << 6)); // target IRQ, repeat
        bus.write32(0, (0x0D << 26) | (1 << 16) | 0x0401); // ORI r1,r0, IEc | IM2
        bus.write32(4, (0x10 << 26) | (4 << 21) | (1 << 16) | (12 << 11)); // MTC0 r1,Status
        machine.startAt(0x80000000);
        machine.step();
        machine.step();
        machine.tickSystemCycles(1);

        machine.step();

        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, machine.getCpu().getPc());
        assertEquals(0x80000008, machine.getCpu().getCp0Register(14));
        assertTrue((machine.getCpu().getCp0Register(13) & (1 << 10)) != 0);
    }
}
