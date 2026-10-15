package com.raully.psxpsp.ps1;

/** Minimal loader for standalone PS-X EXE payloads; disc images are not supported here. */
public final class Ps1ExecutableLoader {
    public static final int HEADER_SIZE = 0x800;
    public static final int MAX_FILE_SIZE = HEADER_SIZE + Ps1MemoryBus.RAM_SIZE;
    private static final byte[] MAGIC = {'P', 'S', '-', 'X', ' ', 'E', 'X', 'E'};

    private Ps1ExecutableLoader() { }

    /** Validate and load one PS-X EXE into RAM, then initialize the entry, GP and stack registers. */
    public static ExecutableInfo load(Ps1Machine machine, byte[] file) {
        if (machine == null) throw new IllegalArgumentException("machine required");
        if (file == null || file.length < HEADER_SIZE) throw new IllegalArgumentException("PS-X EXE header is incomplete");
        if (file.length > MAX_FILE_SIZE) throw new IllegalArgumentException("PS-X EXE is larger than supported RAM");
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) throw new IllegalArgumentException("file is not a PS-X EXE");
        }

        int entryPoint = readLe32(file, 0x10);
        int globalPointer = readLe32(file, 0x14);
        int loadAddress = readLe32(file, 0x18);
        long textSizeLong = Integer.toUnsignedLong(readLe32(file, 0x1C));
        int stackAddress = readLe32(file, 0x30);
        long stackSize = Integer.toUnsignedLong(readLe32(file, 0x34));
        if (textSizeLong == 0 || textSizeLong > Ps1MemoryBus.RAM_SIZE) {
            throw new IllegalArgumentException("PS-X EXE text size is invalid");
        }
        int textSize = (int) textSizeLong;
        if (file.length < HEADER_SIZE + textSize) {
            throw new IllegalArgumentException("PS-X EXE payload is truncated");
        }
        if ((entryPoint & 3) != 0 || (loadAddress & 3) != 0) {
            throw new IllegalArgumentException("PS-X EXE entry or load address is unaligned");
        }

        int ramOffset = ramOffset(loadAddress, "load address");
        if (textSize > Ps1MemoryBus.RAM_SIZE - ramOffset) {
            throw new IllegalArgumentException("PS-X EXE payload crosses the end of RAM");
        }
        int entryOffset = ramOffset(entryPoint, "entry point");
        if (entryOffset < ramOffset || entryOffset >= ramOffset + textSize) {
            throw new IllegalArgumentException("PS-X EXE entry point is outside the loaded payload");
        }

        int initialStack = 0;
        if (stackAddress != 0 || stackSize != 0) {
            long stackValue = Integer.toUnsignedLong(stackAddress) + stackSize;
            if (stackValue > 0xFFFFFFFFL) throw new IllegalArgumentException("PS-X EXE stack address overflows");
            initialStack = (int) stackValue;
            ramOffset(initialStack, "stack pointer");
        }

        machine.getBus().clear();
        machine.getBus().loadRam(ramOffset, file, HEADER_SIZE, textSize);
        machine.startAt(entryPoint);
        machine.getCpu().setRegister(28, globalPointer);
        if (initialStack != 0) {
            machine.getCpu().setRegister(29, initialStack);
            machine.getCpu().setRegister(30, initialStack);
        }
        return new ExecutableInfo(entryPoint, globalPointer, loadAddress, textSize, initialStack);
    }

    private static int ramOffset(int address, String fieldName) {
        long unsigned = Integer.toUnsignedLong(address);
        long physical;
        int segment = (int) (unsigned >>> 29);
        if (segment == 4 || segment == 5) {
            physical = unsigned & 0x1FFFFFFFL;
        } else if (unsigned < Ps1MemoryBus.RAM_MIRROR_SIZE) {
            physical = unsigned;
        } else {
            throw new IllegalArgumentException("PS-X EXE " + fieldName + " is outside mapped RAM");
        }
        if (physical >= Ps1MemoryBus.RAM_MIRROR_SIZE) {
            throw new IllegalArgumentException("PS-X EXE " + fieldName + " is outside mapped RAM");
        }
        return (int) (physical & (Ps1MemoryBus.RAM_SIZE - 1));
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    public static final class ExecutableInfo {
        public final int entryPoint;
        public final int globalPointer;
        public final int loadAddress;
        public final int textSize;
        public final int initialStack;

        private ExecutableInfo(int entryPoint, int globalPointer, int loadAddress,
                               int textSize, int initialStack) {
            this.entryPoint = entryPoint;
            this.globalPointer = globalPointer;
            this.loadAddress = loadAddress;
            this.textSize = textSize;
            this.initialStack = initialStack;
        }
    }
}
