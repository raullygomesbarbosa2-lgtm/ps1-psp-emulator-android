package com.raully.psxpsp.ps1;

/**
 * Composição mínima: CPU MIPS, barramento, timers, raster e GPU GP0/GP1 parcial.
 * Há um carregador PS-X EXE e apresentação de snapshots, mas sem HLE completo ou precisão de ciclo.
 */
public final class Ps1Machine {
    private final Ps1MemoryBus bus;
    private final MipsR3000A cpu;
    private final Ps1VideoTiming videoTiming;

    public Ps1Machine() {
        bus = new Ps1MemoryBus();
        cpu = new MipsR3000A(bus);
        videoTiming = bus.getVideoTiming();
    }

    public Ps1MemoryBus getBus() { return bus; }
    public MipsR3000A getCpu() { return cpu; }
    public Ps1VideoTiming getVideoTiming() { return videoTiming; }
    public Ps1Gpu getGpu() { return bus.getGpu(); }
    public Ps1VideoFrame renderDisplayFrame() { return bus.getGpu().renderDisplayFrame(); }

    /** Start execution at an explicitly prepared entry point; no BIOS is synthesized. */
    public void startAt(int entryPoint) {
        cpu.reset(entryPoint);
    }

    public void step() {
        cpu.step();
    }

    /** Advance supplied system cycles through the provisional video clock and timers. */
    public void tickSystemCycles(long cycles) { videoTiming.advanceSystemCycles(cycles); }
    public void tickVideoClock(long pulses) { videoTiming.advanceVideoTicks(pulses); }
    public void tickDotClocks(long pulses) { bus.getTimers().tickDotClocks(pulses); }
    public void setHBlank(boolean active) { bus.getTimers().setHBlank(active); }
    public void setVBlank(boolean active) { bus.getTimers().setVBlank(active); }
}
