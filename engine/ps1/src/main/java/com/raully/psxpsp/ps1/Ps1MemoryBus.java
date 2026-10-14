package com.raully.psxpsp.ps1;

import java.util.Arrays;

/**
 * Initial PS1 address bus: mirrored 2 MiB RAM, 1 KiB scratchpad, selected
 * memory-control registers and the interrupt controller. Other MMIO, ROM and
 * expansion regions raise an explicit bus fault until their devices exist.
 */
public final class Ps1MemoryBus implements Ps1Bus {
    public static final int RAM_SIZE = 2 * 1024 * 1024;
    public static final int RAM_MIRROR_SIZE = 8 * 1024 * 1024;
    public static final int SCRATCHPAD_START = 0x1F800000;
    public static final int SCRATCHPAD_SIZE = 1024;
    public static final int INTERRUPT_STATUS = 0x1F801070;
    public static final int INTERRUPT_MASK = 0x1F801074;

    private final byte[] ram = new byte[RAM_SIZE];
    private final byte[] scratchpad = new byte[SCRATCHPAD_SIZE];
    private final Ps1InterruptController interruptController = new Ps1InterruptController();
    private final Ps1MemoryControl memoryControl = new Ps1MemoryControl();

    public Ps1InterruptController getInterruptController() { return interruptController; }
    public Ps1MemoryControl getMemoryControl() { return memoryControl; }

    @Override
    public boolean isInterruptPending() {
        return interruptController.isPending();
    }

    @Override
    public int read8(int address) {
        int physicalAddress = toPhysicalAddress(address, false);
        if (isInterruptRegister(physicalAddress)) {
            return interruptController.readByte(physicalAddress - INTERRUPT_STATUS);
        }
        if (memoryControl.isMappedAddress(physicalAddress)) return memoryControl.readByte(physicalAddress);
        Region region = mapMemory(address, physicalAddress, false);
        return region.bytes[region.index] & 0xFF;
    }

    @Override
    public int read16(int address) {
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(int address) {
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(int address, int value) {
        int physicalAddress = toPhysicalAddress(address, true);
        if (isInterruptRegister(physicalAddress)) {
            interruptController.writeByte(physicalAddress - INTERRUPT_STATUS, value);
            return;
        }
        if (memoryControl.isMappedAddress(physicalAddress)) {
            memoryControl.writeByte(physicalAddress, value);
            return;
        }
        Region region = mapMemory(address, physicalAddress, true);
        region.bytes[region.index] = (byte) value;
    }

    @Override
    public void write16(int address, int value) {
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    /** Clear writable RAM and scratchpad and reset the two implemented MMIO blocks. */
    public void clear() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(scratchpad, (byte) 0);
        interruptController.reset();
        memoryControl.resetToCommonBiosValues();
    }

    /** Load bytes into physical main RAM; validates the complete destination range. */
    public void loadRam(int physicalOffset, byte[] source, int sourceOffset, int length) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (physicalOffset < 0 || sourceOffset < 0 || length < 0
                || physicalOffset > RAM_SIZE - length || sourceOffset > source.length - length) {
            throw new IndexOutOfBoundsException("RAM image range is outside the buffer");
        }
        System.arraycopy(source, sourceOffset, ram, physicalOffset, length);
    }

    public byte[] copyRam() {
        return ram.clone();
    }

    private boolean isInterruptRegister(int physicalAddress) {
        return physicalAddress >= INTERRUPT_STATUS && physicalAddress < INTERRUPT_MASK + 4;
    }

    private int toPhysicalAddress(int virtualAddress, boolean write) {
        int segment = virtualAddress >>> 29;
        if (segment == 4 || segment == 5) return virtualAddress & 0x1FFFFFFF; // KSEG0/KSEG1
        if (segment == 0) return virtualAddress; // low KUSEG physical window
        throw new Ps1Bus.BusFaultException(virtualAddress, write);
    }

    private Region mapMemory(int virtualAddress, int physicalAddress, boolean write) {
        if (physicalAddress < RAM_MIRROR_SIZE) {
            return new Region(ram, physicalAddress & (RAM_SIZE - 1));
        }
        int scratchOffset = physicalAddress - SCRATCHPAD_START;
        int segment = virtualAddress >>> 29;
        // Scratchpad is mapped in KUSEG and KSEG0, but not in uncached KSEG1.
        if (scratchOffset >= 0 && scratchOffset < SCRATCHPAD_SIZE && segment != 5) {
            return new Region(scratchpad, scratchOffset);
        }
        throw new Ps1Bus.BusFaultException(virtualAddress, write);
    }

    private static final class Region {
        final byte[] bytes;
        final int index;
        Region(byte[] bytes, int index) { this.bytes = bytes; this.index = index; }
    }
}
