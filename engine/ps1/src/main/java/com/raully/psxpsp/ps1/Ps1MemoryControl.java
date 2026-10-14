package com.raully.psxpsp.ps1;

/**
 * Initial implementation of the PS1 memory-control register block.
 * Defaults are commonly BIOS-programmed values, not silicon reset guarantees.
 * Timing values are latched for software visibility; this class does not yet
 * model bus wait states or expansion hardware.
 */
public final class Ps1MemoryControl {
    public static final int EXPANSION1_BASE = 0x1F801000;
    public static final int EXPANSION2_BASE = 0x1F801004;
    public static final int EXPANSION1_DELAY = 0x1F801008;
    public static final int EXPANSION3_DELAY = 0x1F80100C;
    public static final int BIOS_DELAY = 0x1F801010;
    public static final int SPU_DELAY = 0x1F801014;
    public static final int CDROM_DELAY = 0x1F801018;
    public static final int EXPANSION2_DELAY = 0x1F80101C;
    public static final int COMMON_DELAY = 0x1F801020;
    public static final int RAM_SIZE = 0x1F801060;

    private static final int[] ADDRESSES = {
            EXPANSION1_BASE, EXPANSION2_BASE, EXPANSION1_DELAY, EXPANSION3_DELAY,
            BIOS_DELAY, SPU_DELAY, CDROM_DELAY, EXPANSION2_DELAY, COMMON_DELAY, RAM_SIZE
    };
    private static final int[] COMMON_BIOS_VALUES = {
            0x1F000000, 0x1F802000, 0x0013243F, 0x00003022,
            0x0013243F, 0x200931E1, 0x00020843, 0x00070777,
            0x00031125, 0x00000B88
    };

    private final int[] values = COMMON_BIOS_VALUES.clone();

    public boolean isMappedAddress(int physicalAddress) {
        return registerIndex(physicalAddress & ~3) >= 0;
    }

    int readByte(int physicalAddress) {
        int aligned = physicalAddress & ~3;
        int index = registerIndex(aligned);
        if (index < 0) throw new IllegalArgumentException("unmapped memory-control register");
        return (values[index] >>> ((physicalAddress & 3) * 8)) & 0xFF;
    }

    void writeByte(int physicalAddress, int value) {
        int aligned = physicalAddress & ~3;
        int index = registerIndex(aligned);
        if (index < 0) throw new IllegalArgumentException("unmapped memory-control register");
        int shift = (physicalAddress & 3) * 8;
        int laneMask = 0xFF << shift;
        if (index == 0 || index == 1) laneMask &= 0x00FFFFFF; // upper byte is fixed.
        values[index] = (values[index] & ~laneMask) | ((value & 0xFF) << shift & laneMask);
        if (index == 0 || index == 1) values[index] = (values[index] & 0x00FFFFFF) | 0x1F000000;
    }

    public int readRegister(int address) {
        int index = registerIndex(address);
        if (index < 0) throw new IllegalArgumentException("unmapped memory-control register");
        return values[index];
    }

    public void resetToCommonBiosValues() {
        System.arraycopy(COMMON_BIOS_VALUES, 0, values, 0, values.length);
    }

    private int registerIndex(int address) {
        for (int i = 0; i < ADDRESSES.length; i++) if (ADDRESSES[i] == address) return i;
        return -1;
    }
}
