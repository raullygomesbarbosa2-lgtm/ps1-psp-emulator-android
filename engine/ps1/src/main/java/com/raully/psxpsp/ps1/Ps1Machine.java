package com.raully.psxpsp.ps1;

/**
 * Composição mínima da máquina: CPU MIPS ligada à RAM e scratchpad do PS1.
 * Ainda não contém HLE de BIOS nem dispositivos MMIO.
 */
public final class Ps1Machine {
    private final Ps1MemoryBus bus;
    private final MipsR3000A cpu;

    public Ps1Machine() {
        bus = new Ps1MemoryBus();
        cpu = new MipsR3000A(bus);
    }

    public Ps1MemoryBus getBus() { return bus; }
    public MipsR3000A getCpu() { return cpu; }

    /** Start execution at an explicitly prepared entry point; no BIOS is synthesized. */
    public void startAt(int entryPoint) {
        cpu.reset(entryPoint);
    }

    public void step() {
        cpu.step();
    }

    /** Advance timer sources; the caller supplies clocks from the future scheduler/GPU. */
    public void tickSystemCycles(long cycles) { bus.getTimers().tickSystemCycles(cycles); }
    public void tickDotClocks(long pulses) { bus.getTimers().tickDotClocks(pulses); }
    public void setHBlank(boolean active) { bus.getTimers().setHBlank(active); }
    public void setVBlank(boolean active) { bus.getTimers().setVBlank(active); }
}
