package com.raully.psxpsp.ps1;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Small software BIOS dispatcher with selected A0 library and B0 event/timer services. */
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
        try {
            if (vector == VECTOR_A0) result = dispatchA0(machine, function);
            else if (vector == VECTOR_B0) result = dispatchB0(machine, function);
            else result = dispatchC0(machine, function);
        } catch (Ps1Bus.BusFaultException invalidPointer) {
            result = -1;
        }
        cpu.returnFromHleCall(result);
        return true;
    }

    private int dispatchA0(Ps1Machine machine, int function) {
        MipsR3000A cpu = machine.getCpu();
        switch (function) {
            case 0x15: // strcat(dst, src)
                return concatenateStrings(machine, cpu.getRegister(4), cpu.getRegister(5), Ps1MemoryBus.RAM_SIZE);
            case 0x16: // strncat(dst, src, maxLength)
                return concatenateStrings(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6));
            case 0x17: // strcmp(str1, str2)
                return compareStrings(machine, cpu.getRegister(4), cpu.getRegister(5), Ps1MemoryBus.RAM_SIZE, true);
            case 0x18: // strncmp(str1, str2, maxLength)
                return compareStrings(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6), false);
            case 0x19: // strcpy(dst, src)
                return copyString(machine, cpu.getRegister(4), cpu.getRegister(5));
            case 0x1A: // strncpy(dst, src, maxLength)
                return copyStringBounded(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6));
            case 0x1B: // strlen(src)
                return stringLength(machine, cpu.getRegister(4));
            case 0x1C: // index(src, character)
            case 0x1E: // strchr(src, character)
                return findStringCharacter(machine, cpu.getRegister(4), cpu.getRegister(5), false);
            case 0x1D: // rindex(src, character)
            case 0x1F: // strrchr(src, character)
                return findStringCharacter(machine, cpu.getRegister(4), cpu.getRegister(5), true);
            case 0x25: // toupper(character)
                return asciiCase(cpu.getRegister(4), true);
            case 0x26: // tolower(character)
                return asciiCase(cpu.getRegister(4), false);
            case 0x27: { // bcopy(src, dst, length)
                int destination = copyBytes(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6), true);
                return destination == -1 ? -1 : 0;
            }
            case 0x28: { // bzero(dst, length)
                int destination = fillBytes(machine, cpu.getRegister(4), 0, cpu.getRegister(5));
                return destination == -1 ? -1 : 0;
            }
            case 0x2A: // memcpy(dst, src, length)
                return copyBytes(machine, cpu.getRegister(5), cpu.getRegister(4), cpu.getRegister(6), false);
            case 0x2B: // memset(dst, value, length)
                return fillBytes(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6));
            case 0x2C: // memmove(dst, src, length)
                return copyBytes(machine, cpu.getRegister(5), cpu.getRegister(4), cpu.getRegister(6), true);
            case 0x2D: // memcmp(src1, src2, length)
                return compareBytes(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6));
            case 0x2E: // memchr(src, value, length)
                return findByte(machine, cpu.getRegister(4), cpu.getRegister(5), cpu.getRegister(6));
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
                return -1; // File/stdio, stateful string, and other A0 calls are not implemented.
        }
    }

    private int copyBytes(Ps1Machine machine, int source, int destination, int length, boolean overlapSafe) {
        if (!validMemoryOperationLength(length)) return -1;
        if (length == 0) return destination;
        Ps1MemoryBus bus = machine.getBus();
        boolean backwards = overlapSafe && (long) destination > (long) source
                && (long) destination < (long) source + length;
        if (backwards) {
            for (int i = length - 1; i >= 0; i--) bus.write8(destination + i, bus.read8(source + i));
        } else {
            for (int i = 0; i < length; i++) bus.write8(destination + i, bus.read8(source + i));
        }
        return destination;
    }

    private int fillBytes(Ps1Machine machine, int destination, int value, int length) {
        if (!validMemoryOperationLength(length)) return -1;
        Ps1MemoryBus bus = machine.getBus();
        for (int i = 0; i < length; i++) bus.write8(destination + i, value);
        return destination;
    }

    private int compareBytes(Ps1Machine machine, int first, int second, int length) {
        if (!validMemoryOperationLength(length)) return -1;
        Ps1MemoryBus bus = machine.getBus();
        for (int i = 0; i < length; i++) {
            int difference = bus.read8(first + i) - bus.read8(second + i);
            if (difference != 0) return difference;
        }
        return 0;
    }

    private int findByte(Ps1Machine machine, int source, int value, int length) {
        if (!validMemoryOperationLength(length)) return -1;
        Ps1MemoryBus bus = machine.getBus();
        int target = value & 0xFF;
        for (int i = 0; i < length; i++) {
            if (bus.read8(source + i) == target) return source + i;
        }
        return 0;
    }

    private static boolean validMemoryOperationLength(int length) {
        return length >= 0 && length <= Ps1MemoryBus.RAM_SIZE;
    }

    private int concatenateStrings(Ps1Machine machine, int destination, int source, int maxSourceLength) {
        if (maxSourceLength < 0 || maxSourceLength > Ps1MemoryBus.RAM_SIZE) return -1;
        int destinationLength = stringLength(machine, destination);
        if (destinationLength < 0) return -1;
        int sourceLength = 0;
        Ps1MemoryBus bus = machine.getBus();
        while (sourceLength < maxSourceLength && bus.read8(source + sourceLength) != 0) sourceLength++;
        long totalLength = (long) destinationLength + sourceLength + 1L;
        if (totalLength > Ps1MemoryBus.RAM_SIZE) return -1;
        for (int i = 0; i < sourceLength; i++) {
            bus.write8(destination + destinationLength + i, bus.read8(source + i));
        }
        bus.write8(destination + destinationLength + sourceLength, 0);
        return destination;
    }

    private int compareStrings(Ps1Machine machine, int first, int second, int maxLength, boolean requireTerminator) {
        if (maxLength < 0 || maxLength > Ps1MemoryBus.RAM_SIZE) return -1;
        Ps1MemoryBus bus = machine.getBus();
        for (int i = 0; i < maxLength; i++) {
            int a = bus.read8(first + i);
            int b = bus.read8(second + i);
            int difference = a - b;
            if (difference != 0) return difference;
            if (a == 0) return 0;
        }
        return requireTerminator ? -1 : 0;
    }

    private int copyString(Ps1Machine machine, int destination, int source) {
        int length = stringLength(machine, source);
        if (length < 0 || length >= Ps1MemoryBus.RAM_SIZE) return -1;
        copyBytes(machine, source, destination, length + 1, false);
        return destination;
    }

    private int copyStringBounded(Ps1Machine machine, int destination, int source, int maxLength) {
        if (maxLength < 0 || maxLength > Ps1MemoryBus.RAM_SIZE) return -1;
        if (maxLength == 0) return destination;
        Ps1MemoryBus bus = machine.getBus();
        boolean sourceEnded = false;
        for (int i = 0; i < maxLength; i++) {
            int value = sourceEnded ? 0 : bus.read8(source + i);
            bus.write8(destination + i, value);
            if (value == 0) sourceEnded = true;
        }
        return destination;
    }

    private int stringLength(Ps1Machine machine, int source) {
        Ps1MemoryBus bus = machine.getBus();
        for (int i = 0; i < Ps1MemoryBus.RAM_SIZE; i++) {
            if (bus.read8(source + i) == 0) return i;
        }
        return -1;
    }

    private int findStringCharacter(Ps1Machine machine, int source, int character, boolean lastMatch) {
        Ps1MemoryBus bus = machine.getBus();
        int target = character & 0xFF;
        int last = 0;
        for (int i = 0; i < Ps1MemoryBus.RAM_SIZE; i++) {
            int value = bus.read8(source + i);
            if (value == target) {
                if (!lastMatch) return source + i;
                last = source + i;
            }
            if (value == 0) return last;
        }
        return -1;
    }

    private static int asciiCase(int character, boolean upper) {
        int value = character & 0xFF;
        if (upper && value >= 'a' && value <= 'z') return value - ('a' - 'A');
        if (!upper && value >= 'A' && value <= 'Z') return value + ('a' - 'A');
        return value;
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

        long unsignedAddress = Integer.toUnsignedLong(address);
        long alignedAddress = (unsignedAddress + 3L) & ~3L;
        if (alignedAddress > 0xFFFFFFFFL) return -1;
        long skippedBytes = alignedAddress - unsignedAddress;
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
