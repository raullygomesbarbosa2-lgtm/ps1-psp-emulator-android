package com.raully.psxpsp.ps1;

/**
 * Interface de acesso à memória/barramento para a CPU PS1.
 * Implementações futuras devem fornecer leituras e escritas little-endian
 * coerentes com o mapa de memória do console.
 */
public interface Ps1Bus {
    /** Unmapped address or unimplemented device access on the PS1 bus. */
    final class BusFaultException extends RuntimeException {
        private final int address;
        private final boolean write;

        public BusFaultException(int address, boolean write) {
            super("Unmapped PS1 bus access at 0x" + Integer.toHexString(address));
            this.address = address;
            this.write = write;
        }

        public int getAddress() { return address; }
        public boolean isWrite() { return write; }
    }

    int read8(int address);
    int read16(int address);
    int read32(int address);
    void write8(int address, int value);
    void write16(int address, int value);
    void write32(int address, int value);

    /** True while an enabled external PS1 interrupt source is pending. */
    default boolean isInterruptPending() { return false; }
}
