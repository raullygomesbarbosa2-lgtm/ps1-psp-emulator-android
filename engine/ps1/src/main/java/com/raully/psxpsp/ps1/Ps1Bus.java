package com.raully.psxpsp.ps1;

/**
 * Interface de acesso à memória/barramento para a CPU PS1.
 * Implementações futuras devem fornecer leituras e escritas little-endian
 * coerentes com o mapa de memória do console.
 */
public interface Ps1Bus {
    int read8(int address);
    int read16(int address);
    int read32(int address);
    void write8(int address, int value);
    void write16(int address, int value);
    void write32(int address, int value);
}
