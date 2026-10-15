package com.raully.psxpsp.ps1;

import java.util.Arrays;

/** Initial PS1 Geometry Transformation Engine register file and command subset. */
final class Ps1Gte {
    private final int[] data = new int[32];
    private final int[] control = new int[32];

    void reset() {
        Arrays.fill(data, 0);
        Arrays.fill(control, 0);
    }

    int readData(int index) {
        switch (index & 31) {
            case 15: return data[14]; // SXYP reads the newest SXY FIFO entry.
            case 23: return 0; // Reserved register.
            case 28: return packRgb5(data[9], data[10], data[11]); // IRGB
            case 29: return packRgb5Saturated(data[9], data[10], data[11]); // ORGB
            case 31: return data[31]; // LZCR
            default: return data[index & 31];
        }
    }

    void writeData(int index, int value) {
        switch (index & 31) {
            case 7: data[7] = value & 0xFFFF; return; // OTZ
            case 8: data[8] = value & 0xFFFF; return; // IR0
            case 9: case 10: case 11:
                data[index & 31] = (short) value; // IR1..IR3
                return;
            case 15: // SXYP pushes the screen-coordinate FIFO.
                data[12] = data[13];
                data[13] = data[14];
                data[14] = value;
                return;
            case 16: case 17: case 18: case 19:
                data[index & 31] = value & 0xFFFF; // SZ0..SZ3
                return;
            case 23: case 29: case 31: // Reserved, ORGB, and LZCR are read-only.
                return;
            case 28: // IRGB loads three unsigned 5-bit channels into IR1..IR3.
                data[9] = (value & 0x1F) << 7;
                data[10] = ((value >>> 5) & 0x1F) << 7;
                data[11] = ((value >>> 10) & 0x1F) << 7;
                return;
            case 30: // LZCS updates the leading-sign-bit count register.
                data[30] = value;
                data[31] = value < 0 ? Integer.numberOfLeadingZeros(~value)
                        : Integer.numberOfLeadingZeros(value);
                return;
            default:
                data[index & 31] = value;
        }
    }

    int readControl(int index) {
        return control[index & 31];
    }

    void writeControl(int index, int value) {
        int register = index & 31;
        if (register == 31) control[31] = value & 0x7FFFF000; // FLAG writable bits; summary is hardware-derived.
        else control[register] = value;
    }

    /** Execute one supported command. The command number is instruction bits 5..0. */
    boolean executeCommand(int command) {
        if ((command & 0x3F) != 0x06) return false; // NCLIP is the initial implemented command.
        executeNclip();
        return true;
    }

    private void executeNclip() {
        int x0 = (short) data[12];
        int y0 = (short) (data[12] >>> 16);
        int x1 = (short) data[13];
        int y1 = (short) (data[13] >>> 16);
        int x2 = (short) data[14];
        int y2 = (short) (data[14] >>> 16);

        long area = (long) x0 * y1 + (long) x1 * y2 + (long) x2 * y0
                - (long) x0 * y2 - (long) x1 * y0 - (long) x2 * y1;
        data[24] = (int) area; // MAC0
    }

    private static int packRgb5(int red, int green, int blue) {
        return ((red >>> 7) & 0x1F)
                | (((green >>> 7) & 0x1F) << 5)
                | (((blue >>> 7) & 0x1F) << 10);
    }

    private static int packRgb5Saturated(int red, int green, int blue) {
        return saturate5(red >> 7)
                | (saturate5(green >> 7) << 5)
                | (saturate5(blue >> 7) << 10);
    }

    private static int saturate5(int value) {
        return Math.max(0, Math.min(31, value));
    }
}
