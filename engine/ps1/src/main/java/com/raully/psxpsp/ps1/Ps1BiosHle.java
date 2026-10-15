package com.raully.psxpsp.ps1;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Small software BIOS dispatcher with limited memory, event, and timer services. */
final class Ps1BiosHle {
    private static final int VECTOR_A0 = 0xA0;
    private static final int VECTOR_B0 = 0xB0;
    private static final int VECTOR_C0 = 0xC0;
    private static final int EVENT_COUNT = 16;
    private static final int EVENT_HANDLE_BASE = 0xF1000000;

    private final EventEntry[] events = new EventEntry[EVENT_COUNT];
    private final TreeMap<Integer, Integer> freeHeapBlocks = new TreeMap<>();
    private final Map<Integer, Integer> allocatedHeapBlocks = new HashMap<>();
    private int heapBase;
    private int heapSize;

    Ps1BiosHle() { reset(); }

    void reset() {
        for (int i = 0; i < events.length; i++) events[i] = new EventEntry();
        freeHeapBlocks.clear();
        allocatedHeapBlocks.clear();
        heapBase = 0;
        heapSize = 0;
    }

    /** Intercept a BIOS vector call; returns false when the PC is not a BIOS vector. */
    boolean dispatch(Ps1Machine machine) {
        MipsR3000A cpu = machine.getCpu();
        int vector = normalizeAddress(cpu.getPc());
        if (vector != VECTOR_A0 && vector != VECTOR_B0 && vector != VECTOR_C0) return false;

        int function = cpu.getRegister(9) & 0xFF; // t1 selects a table entry
        int result;
        if (vector == VECTOR_A0) result = dispatchA0(machine, function);
        else if (vector == VECTOR_B0) result = dispatchB0(machine, function);
        else result = dispatchC0(machine, function);
        cpu.returnFromHleCall(result);
        return true;
    }

    private int dispatchA0(Ps1Machine machine, int function) {
        MipsR3000A cpu = machine.getCpu();
        switch (function) {
            case 0x33: // malloc(size)
                return allocate(cpu.getRegister(4));
            case 0x34: // free(buf)
                free(cpu.getRegister(4));
                return 0;
            case 0x37: // calloc(sizeX, sizeY)
                return calloc(machine, cpu.getRegister(4), cpu.getRegister(5));
            case 0x38: // realloc(oldBuf, newSize)
                return realloc(machine, cpu.getRegister(4), cpu.getRegister(5));
            case 0x39: // InitHeap(address, size)
                return initializeHeap(cpu.getRegister(4), cpu.getRegister(5));
            default:
                return -1; // File/stdio, string, and other A0 calls are not implemented.
        }
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

    private int initializeHeap(int address, int size) {
        if (size < 0) return -1;

        long alignedAddress = ((long) address + 3L) & ~3L;
        long skippedBytes = alignedAddress - (long) address;
        long usableSize = (long) size - skippedBytes;
        if (usableSize < 0 || usableSize > Integer.MAX_VALUE) return -1;
        int alignedBase = (int) alignedAddress;
        int physicalStart = physicalRamAddress(alignedBase);
        if (physicalStart < 0 || physicalStart > Ps1MemoryBus.RAM_SIZE
                || (long) physicalStart + usableSize > Ps1MemoryBus.RAM_SIZE) return -1;

        int alignedSize = (int) usableSize & ~3;
        freeHeapBlocks.clear();
        allocatedHeapBlocks.clear();
        heapBase = alignedBase;
        heapSize = alignedSize;
        if (alignedSize > 0) freeHeapBlocks.put(0, alignedSize);
        return 0;
    }

    private int allocate(int requestedSize) {
        int size = alignAllocationSize(requestedSize);
        if (size <= 0 || heapSize == 0) return 0;

        Iterator<Map.Entry<Integer, Integer>> iterator = freeHeapBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> block = iterator.next();
            int available = block.getValue();
            if (available < size) continue;
            int offset = block.getKey();
            iterator.remove();
            if (available > size) freeHeapBlocks.put(offset + size, available - size);
            allocatedHeapBlocks.put(offset, size);
            return heapBase + offset;
        }
        return 0;
    }

    private void free(int pointer) {
        int offset = heapOffset(pointer);
        if (offset < 0) return;
        Integer size = allocatedHeapBlocks.remove(offset);
        if (size != null) addFreeHeapRange(offset, size);
    }

    private int calloc(Ps1Machine machine, int sizeX, int sizeY) {
        if (sizeX < 0 || sizeY < 0) return 0;
        long requested = (long) sizeX * (long) sizeY;
        if (requested <= 0 || requested > Integer.MAX_VALUE) return 0;
        int pointer = allocate((int) requested);
        if (pointer == 0) return 0;
        for (int i = 0; i < requested; i++) machine.getBus().write8(pointer + i, 0);
        return pointer;
    }

    private int realloc(Ps1Machine machine, int oldPointer, int requestedSize) {
        if (requestedSize < 0) return 0;
        if (oldPointer == 0) return allocate(requestedSize);
        if (requestedSize == 0) {
            free(oldPointer);
            return 0;
        }

        int offset = heapOffset(oldPointer);
        if (offset < 0) return 0;
        Integer oldSizeValue = allocatedHeapBlocks.get(offset);
        if (oldSizeValue == null) return 0;
        int oldSize = oldSizeValue;
        int newSize = alignAllocationSize(requestedSize);
        if (newSize <= 0) return 0;
        if (newSize <= oldSize) {
            if (newSize < oldSize) {
                allocatedHeapBlocks.put(offset, newSize);
                addFreeHeapRange(offset + newSize, oldSize - newSize);
            }
            return oldPointer;
        }

        int adjacentOffset = offset + oldSize;
        Integer adjacentSize = freeHeapBlocks.get(adjacentOffset);
        int growth = newSize - oldSize;
        if (adjacentSize != null && adjacentSize >= growth) {
            freeHeapBlocks.remove(adjacentOffset);
            if (adjacentSize > growth) freeHeapBlocks.put(adjacentOffset + growth, adjacentSize - growth);
            allocatedHeapBlocks.put(offset, newSize);
            return oldPointer;
        }

        int newPointer = allocate(requestedSize);
        if (newPointer == 0) return 0;
        for (int i = 0; i < oldSize; i++) {
            machine.getBus().write8(newPointer + i, machine.getBus().read8(oldPointer + i));
        }
        free(oldPointer);
        return newPointer;
    }

    private void addFreeHeapRange(int offset, int size) {
        if (size <= 0) return;
        Map.Entry<Integer, Integer> lower = freeHeapBlocks.lowerEntry(offset);
        if (lower != null && lower.getKey() + lower.getValue() == offset) {
            offset = lower.getKey();
            size += lower.getValue();
            freeHeapBlocks.remove(lower.getKey());
        }
        Map.Entry<Integer, Integer> higher = freeHeapBlocks.ceilingEntry(offset);
        if (higher != null && offset + size == higher.getKey()) {
            size += higher.getValue();
            freeHeapBlocks.remove(higher.getKey());
        }
        freeHeapBlocks.put(offset, size);
    }

    private int heapOffset(int pointer) {
        long offset = (long) pointer - (long) heapBase;
        if (offset < 0 || offset > Integer.MAX_VALUE) return -1;
        return allocatedHeapBlocks.containsKey((int) offset) ? (int) offset : -1;
    }

    private static int alignAllocationSize(int size) {
        if (size <= 0 || size > Integer.MAX_VALUE - 3) return 0;
        return (size + 3) & ~3;
    }

    private static int physicalRamAddress(int address) {
        int segment = address >>> 29;
        if (segment == 0) return address;
        if (segment == 4 || segment == 5) return address & 0x1FFFFFFF;
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
