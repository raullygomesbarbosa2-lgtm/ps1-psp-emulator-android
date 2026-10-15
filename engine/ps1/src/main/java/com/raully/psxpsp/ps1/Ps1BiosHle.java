package com.raully.psxpsp.ps1;

/** Small software BIOS dispatcher for the PS1 A0/B0/C0 call vectors. */
final class Ps1BiosHle {
    private static final int VECTOR_A0 = 0xA0;
    private static final int VECTOR_B0 = 0xB0;
    private static final int VECTOR_C0 = 0xC0;
    private static final int EVENT_COUNT = 16;
    private static final int EVENT_HANDLE_BASE = 0xF1000000;

    private final EventEntry[] events = new EventEntry[EVENT_COUNT];

    Ps1BiosHle() { reset(); }

    void reset() {
        for (int i = 0; i < events.length; i++) events[i] = new EventEntry();
    }

    /** Intercept a BIOS vector call; returns false when the PC is not a BIOS vector. */
    boolean dispatch(Ps1Machine machine) {
        MipsR3000A cpu = machine.getCpu();
        int vector = normalizeAddress(cpu.getPc());
        if (vector != VECTOR_A0 && vector != VECTOR_B0 && vector != VECTOR_C0) return false;

        int function = cpu.getRegister(9) & 0xFF; // t1 selects a table entry
        int result;
        if (vector == VECTOR_B0) result = dispatchB0(machine, function);
        else if (vector == VECTOR_C0) result = dispatchC0(machine, function);
        else result = -1; // A0 file/stdio and library services are not emulated yet.
        cpu.returnFromHleCall(result);
        return true;
    }

    private int dispatchB0(Ps1Machine machine, int function) {
        MipsR3000A cpu = machine.getCpu();
        int a0 = cpu.getRegister(4);
        switch (function) {
            case 0x03: // GetRCnt
                return a0 >= 0 && a0 < 3 ? machine.getBus().getTimers().getCounter(a0) : -1;
            case 0x06: // ResetRCnt
                if (a0 >= 0 && a0 < 3) {
                    machine.getBus().getTimers().writeRegister(
                            Ps1Timers.BASE + a0 * Ps1Timers.TIMER_STRIDE, 0, 0xFFFF);
                    return 1;
                }
                return 0;
            case 0x07: // DeliverEvent
                deliverEvent(cpu.getRegister(4), cpu.getRegister(5));
                return 1;
            case 0x08: // OpenEvent
                return openEvent(a0, cpu.getRegister(5), cpu.getRegister(6), cpu.getRegister(7));
            case 0x09: // CloseEvent
                closeEvent(a0);
                return 1;
            case 0x0A: // WaitEvent; this HLE never blocks the emulation thread.
            case 0x0B: // TestEvent
                return consumeReadyEvent(a0) ? 1 : 0;
            case 0x0C: // EnableEvent
                setEventEnabled(a0, true);
                return 1;
            case 0x0D: // DisableEvent
                setEventEnabled(a0, false);
                return 1;
            default:
                return -1;
        }
    }

    private int dispatchC0(Ps1Machine machine, int function) {
        if (function == 0x00) { // InitRCnt
            machine.getBus().getTimers().reset();
            return 0;
        }
        return -1;
    }

    private int openEvent(int eventClass, int eventSpec, int mode, int callback) {
        if (mode != 0x1000 && mode != 0x2000) return -1;
        for (int i = 0; i < events.length; i++) {
            EventEntry event = events[i];
            if (!event.allocated) {
                event.allocated = true;
                event.enabled = false;
                event.ready = false;
                event.eventClass = eventClass;
                event.eventSpec = eventSpec;
                event.mode = mode;
                event.callback = callback;
                return EVENT_HANDLE_BASE | i;
            }
        }
        return -1;
    }

    private void closeEvent(int handle) {
        EventEntry event = getEvent(handle);
        if (event != null) event.reset();
    }

    private void setEventEnabled(int handle, boolean enabled) {
        EventEntry event = getEvent(handle);
        if (event == null) return;
        event.enabled = enabled;
        event.ready = false;
    }

    private void deliverEvent(int eventClass, int eventSpec) {
        for (EventEntry event : events) {
            if (!event.allocated || !event.enabled || event.eventClass != eventClass
                    || event.eventSpec != eventSpec) continue;
            if (event.mode == 0x2000) event.ready = true;
            // Callback mode 1000h is recorded but callback execution is not implemented.
        }
    }

    private boolean consumeReadyEvent(int handle) {
        EventEntry event = getEvent(handle);
        if (event == null || !event.enabled || !event.ready) return false;
        event.ready = false;
        return true;
    }

    private EventEntry getEvent(int handle) {
        if ((handle & 0xFFFF0000) != EVENT_HANDLE_BASE) return null;
        int index = handle & 0xFFFF;
        if (index >= events.length || !events[index].allocated) return null;
        return events[index];
    }

    private static int normalizeAddress(int address) {
        int segment = address >>> 29;
        return segment == 4 || segment == 5 ? address & 0x1FFFFFFF : address;
    }

    private static final class EventEntry {
        boolean allocated;
        boolean enabled;
        boolean ready;
        int eventClass;
        int eventSpec;
        int mode;
        int callback;

        void reset() {
            allocated = false;
            enabled = false;
            ready = false;
            eventClass = 0;
            eventSpec = 0;
            mode = 0;
            callback = 0;
        }
    }
}
