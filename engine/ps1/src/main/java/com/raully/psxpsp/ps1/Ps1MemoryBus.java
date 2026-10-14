package com.raully.psxpsp.ps1;

import java.util.Arrays;

/**
 * Mapa inicial de memória PS1: 2 MiB RAM com espelhos de 8 MiB e scratchpad
 * de 1 KiB. MMIO, ROMs e demais regiões ainda geram bus fault explícito.
 */
public final class Ps1MemoryBus implements Ps1Bus {
    public static final int RAM_SIZE = 2 * 1024 * 1024;
    public static final int RAM_MIRROR_SIZE = 8 * 1024 * 1024;
    public static final int SCRATCHPAD_START = 0x1F800000;
    public static final int SCRATCHPAD_SIZE = 1024;

    private final byte[] ram = new byte[RAM_SIZE];
    private final byte[] scratchpad = new byte[SCRATCHPAD_SIZE];

    @Override
    public int read8(int address) {
        Region region = map(address, false);
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
        Region region = map(address, true);
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

    /** Clear writable RAM and scratchpad, useful when resetting a machine. */
    public void clear() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(scratchpad, (byte) 0);
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

    private Region map(int virtualAddress, boolean write) {
        int segment = virtualAddress >>> 29;
        final int physicalAddress;
        if (segment == 4 || segment == 5) { // KSEG0/KSEG1, uncached alias.
            physicalAddress = virtualAddress & 0x1FFFFFFF;
        } else if (segment == 0) { // KUSEG low physical window.
            physicalAddress = virtualAddress;
        } else {
            throw new Ps1Bus.BusFaultException(virtualAddress, write);
        }

        if (physicalAddress >= 0 && physicalAddress < RAM_MIRROR_SIZE) {
            return new Region(ram, physicalAddress & (RAM_SIZE - 1));
        }
        int scratchOffset = physicalAddress - SCRATCHPAD_START;
        if (scratchOffset >= 0 && scratchOffset < SCRATCHPAD_SIZE) {
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
