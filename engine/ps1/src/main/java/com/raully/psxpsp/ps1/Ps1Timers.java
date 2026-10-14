package com.raully.psxpsp.ps1;

/**
 * Three PS1 root counters. CPU/system cycles and GPU blank/dot-clock events are
 * supplied explicitly; the machine scheduler has not yet assigned real cycle
 * costs to MIPS instructions or generated GPU timings.
 */
public final class Ps1Timers {
    public static final int BASE = 0x1F801100;
    public static final int TIMER_STRIDE = 0x10;
    private static final int[] IRQ_SOURCES = {
            Ps1InterruptController.IRQ_TIMER0,
            Ps1InterruptController.IRQ_TIMER1,
            Ps1InterruptController.IRQ_TIMER2
    };

    private final Ps1InterruptController interrupts;
    private final RootCounter[] counters = {new RootCounter(), new RootCounter(), new RootCounter()};
    private boolean hBlank;
    private boolean vBlank;

    public Ps1Timers(Ps1InterruptController interrupts) {
        if (interrupts == null) throw new IllegalArgumentException("interrupt controller required");
        this.interrupts = interrupts;
    }

    public boolean isMappedAddress(int physicalAddress) {
        int offset = physicalAddress - BASE;
        if (offset < 0 || offset >= TIMER_STRIDE * 3) return false;
        int registerOffset = offset % TIMER_STRIDE;
        return registerOffset >= 0 && registerOffset <= 11;
    }

    /** Read a timer register as a 16-bit value; reading Mode clears flags 11 and 12. */
    public int readRegister(int physicalAddress) {
        int offset = physicalAddress - BASE;
        int timerIndex = offset / TIMER_STRIDE;
        int registerOffset = (offset % TIMER_STRIDE) & ~3;
        if (timerIndex < 0 || timerIndex >= 3) throw new IllegalArgumentException("invalid timer address");
        RootCounter timer = counters[timerIndex];
        switch (registerOffset) {
            case 0: return timer.counter & 0xFFFF;
            case 4: {
                int mode = timer.mode & 0xFFFF;
                timer.mode &= ~((1 << 11) | (1 << 12));
                return mode;
            }
            case 8: return timer.target & 0xFFFF;
            default: throw new IllegalArgumentException("unmapped timer register");
        }
    }

    /** Write only the low 16 bits selected by bitMask; high register bits are ignored. */
    public void writeRegister(int physicalAddress, int value, int bitMask) {
        int offset = physicalAddress - BASE;
        int timerIndex = offset / TIMER_STRIDE;
        int registerOffset = (offset % TIMER_STRIDE) & ~3;
        if (timerIndex < 0 || timerIndex >= 3) throw new IllegalArgumentException("invalid timer address");
        RootCounter timer = counters[timerIndex];
        int mask = bitMask & 0xFFFF;
        switch (registerOffset) {
            case 0:
                timer.counter = ((timer.counter & ~mask) | (value & mask)) & 0xFFFF;
                return;
            case 4: {
                int configMask = mask & 0x03FF;
                int config = ((timer.mode & 0x03FF) & ~configMask) | (value & configMask);
                timer.mode = config | (1 << 10); // IRQ request line starts inactive.
                timer.counter = 0;
                timer.irqArmed = true;
                timer.pulseActive = false;
                timer.syncReleased = false;
                timer.divideByEightRemainder = 0;
                return;
            }
            case 8:
                timer.target = ((timer.target & ~mask) | (value & mask)) & 0xFFFF;
                return;
            default: throw new IllegalArgumentException("unmapped timer register");
        }
    }

    /** Advance timers driven by the PS1 system clock. */
    public void tickSystemCycles(long cycles) {
        if (cycles < 0) throw new IllegalArgumentException("cycles must be non-negative");
        if (cycles == 0) return;
        for (int index = 0; index < 2; index++) {
            int source = (counters[index].mode >>> 8) & 3;
            if ((source & 1) == 0) advance(index, cycles);
        }
        RootCounter timer2 = counters[2];
        int source2 = (timer2.mode >>> 8) & 3;
        if (source2 < 2) {
            advance(2, cycles);
        } else {
            long total = timer2.divideByEightRemainder + cycles;
            long pulses = total / 8;
            timer2.divideByEightRemainder = (int) (total % 8);
            advance(2, pulses);
        }
    }

    /** Feed Timer 0 when a GPU dot-clock source is selected. */
    public void tickDotClocks(long pulses) {
        if (pulses < 0) throw new IllegalArgumentException("pulses must be non-negative");
        int source = (counters[0].mode >>> 8) & 3;
        if ((source & 1) != 0) advance(0, pulses);
    }

    /** Update HBlank state; a rising edge may reset/synchronize Timer 0 and clock Timer 1. */
    public void setHBlank(boolean active) {
        boolean rising = active && !hBlank;
        hBlank = active;
        if (!rising) return;
        onBlankStart(0);
        int source1 = (counters[1].mode >>> 8) & 3;
        if ((source1 & 1) != 0) advance(1, 1);
    }

    /** Update VBlank state; a rising edge may reset/synchronize Timer 1. */
    public void setVBlank(boolean active) {
        boolean rising = active && !vBlank;
        vBlank = active;
        if (rising) onBlankStart(1);
    }

    public int getCounter(int timerIndex) { return counter(timerIndex).counter & 0xFFFF; }
    public int getModeWithoutSideEffects(int timerIndex) { return counter(timerIndex).mode & 0xFFFF; }
    public int getTarget(int timerIndex) { return counter(timerIndex).target & 0xFFFF; }

    public void reset() {
        for (RootCounter timer : counters) timer.reset();
        hBlank = false;
        vBlank = false;
    }

    private RootCounter counter(int index) {
        if (index < 0 || index >= counters.length) throw new IllegalArgumentException("invalid timer index");
        return counters[index];
    }

    private void onBlankStart(int index) {
        RootCounter timer = counters[index];
        if ((timer.mode & 1) == 0) return;
        int syncMode = (timer.mode >>> 1) & 3;
        if (syncMode == 1 || syncMode == 2) timer.counter = 0;
        else if (syncMode == 3) timer.syncReleased = true;
    }

    private boolean shouldCount(int index, RootCounter timer) {
        if ((timer.mode & 1) == 0) return true;
        int syncMode = (timer.mode >>> 1) & 3;
        if (index == 2) return syncMode == 1 || syncMode == 2;
        if (timer.syncReleased) return true;
        boolean blank = index == 0 ? hBlank : vBlank;
        switch (syncMode) {
            case 0: return !blank;
            case 1: return true;
            case 2: return blank;
            default: return false; // pause until the first blank edge
        }
    }

    private void advance(int index, long pulses) {
        RootCounter timer = counters[index];
        for (long pulse = 0; pulse < pulses; pulse++) {
            if (timer.pulseActive) {
                timer.mode |= 1 << 10;
                timer.pulseActive = false;
            }
            if (!shouldCount(index, timer)) continue;

            timer.counter = (timer.counter + 1) & 0xFFFF;
            boolean reachedTarget = timer.counter == timer.target;
            boolean reachedFFFF = timer.counter == 0xFFFF;
            if (reachedTarget) timer.mode |= 1 << 11;
            if (reachedFFFF) timer.mode |= 1 << 12;

            boolean irqEvent = (reachedTarget && (timer.mode & (1 << 4)) != 0)
                    || (reachedFFFF && (timer.mode & (1 << 5)) != 0);
            if (irqEvent) requestInterrupt(index, timer);

            boolean resetAtTarget = (timer.mode & (1 << 3)) != 0;
            if (resetAtTarget && reachedTarget) timer.counter = 0;
            else if (!resetAtTarget && reachedFFFF) timer.counter = 0;
        }
    }

    private void requestInterrupt(int index, RootCounter timer) {
        if (!timer.irqArmed) return;
        boolean repeat = (timer.mode & (1 << 6)) != 0;
        boolean toggle = (timer.mode & (1 << 7)) != 0;
        if (toggle) {
            if ((timer.mode & (1 << 10)) != 0) {
                timer.mode &= ~(1 << 10);
                interrupts.request(IRQ_SOURCES[index]);
                if (!repeat) timer.irqArmed = false;
            } else {
                timer.mode |= 1 << 10;
            }
        } else {
            timer.mode &= ~(1 << 10);
            timer.pulseActive = true;
            interrupts.request(IRQ_SOURCES[index]);
            if (!repeat) timer.irqArmed = false;
        }
    }

    private static final class RootCounter {
        int counter;
        int mode;
        int target;
        int divideByEightRemainder;
        boolean irqArmed = true;
        boolean pulseActive;
        boolean syncReleased;

        void reset() {
            counter = 0;
            mode = 0;
            target = 0;
            divideByEightRemainder = 0;
            irqArmed = true;
            pulseActive = false;
            syncReleased = false;
        }
    }
}
