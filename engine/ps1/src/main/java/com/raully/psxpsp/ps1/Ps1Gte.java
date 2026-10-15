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
        if (register == 31) {
            control[31] = value & 0x7FFFF000;
            updateFlagSummary();
        } else control[register] = value;
    }

    /** Execute one supported command using its full instruction word. */
    boolean executeCommand(int instruction) {
        int command = instruction & 0x3F;
        control[31] = 0;
        boolean shiftFraction = (instruction & (1 << 19)) != 0;
        if (command == 0x06) {
            executeNclip();
        } else if (command == 0x01) {
            executeRtps(shiftFraction, 0, true);
        } else if (command == 0x30) {
            executeRtps(shiftFraction, 0, false);
            executeRtps(shiftFraction, 1, false);
            executeRtps(shiftFraction, 2, true);
        } else if (command == 0x12) {
            if (!executeMvmva(instruction, shiftFraction)) return false;
        } else {
            return false;
        }
        updateFlagSummary();
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
        storeMac0(area);
    }

    private void executeRtps(boolean shiftFraction, int vectorIndex, boolean calculateDepthCue) {
        int packedXy = data[vectorIndex * 2];
        int vx = (short) packedXy;
        int vy = (short) (packedXy >>> 16);
        int vz = (short) data[vectorIndex * 2 + 1];

        long rawMac1 = transformRow(0, vx, vy, vz, control[5]);
        long rawMac2 = transformRow(1, vx, vy, vz, control[6]);
        long rawMac3 = transformRow(2, vx, vy, vz, control[7]);
        checkMacOverflow(rawMac1, 30, 27);
        checkMacOverflow(rawMac2, 29, 26);
        checkMacOverflow(rawMac3, 28, 25);

        long shift = shiftFraction ? 12 : 0;
        int mac1 = (int) (rawMac1 >> shift);
        int mac2 = (int) (rawMac2 >> shift);
        int mac3 = (int) (rawMac3 >> shift);
        data[25] = mac1;
        data[26] = mac2;
        data[27] = mac3;
        data[9] = saturateIr(mac1, 1);
        data[10] = saturateIr(mac2, 2);
        data[11] = saturateIr(mac3, 3);

        int depth = saturateUnsigned16(rawMac3 >> 12, 18);
        data[16] = data[17];
        data[17] = data[18];
        data[18] = data[19];
        data[19] = depth;

        int quotient = perspectiveQuotient(control[26] & 0xFFFF, depth);
        int screenXMac = storeMac0((long) control[24] + (long) data[9] * quotient);
        int screenYMac = storeMac0((long) control[25] + (long) data[10] * quotient);
        int screenX = saturateScreen(screenXMac >> 16, 14);
        int screenY = saturateScreen(screenYMac >> 16, 13);
        writeData(15, ((screenY & 0xFFFF) << 16) | (screenX & 0xFFFF));

        if (calculateDepthCue) {
            int depthCueMac = storeMac0((long) (short) control[27] * quotient + control[28]);
            data[8] = saturateIr0(depthCueMac >> 12);
        }
    }

    private boolean executeMvmva(int instruction, boolean shiftFraction) {
        int matrixSelector = (instruction >>> 17) & 3;
        if (matrixSelector == 3) return false; // Reserved matrix selection is not modeled.
        int matrixBase = matrixSelector * 8;
        int vectorSelector = (instruction >>> 15) & 3;
        int translationSelector = (instruction >>> 13) & 3;
        boolean limitMode = (instruction & (1 << 10)) != 0;
        int vx;
        int vy;
        int vz;
        switch (vectorSelector) {
            case 0:
                vx = (short) data[0]; vy = (short) (data[0] >>> 16); vz = (short) data[1];
                break;
            case 1:
                vx = (short) data[2]; vy = (short) (data[2] >>> 16); vz = (short) data[3];
                break;
            case 2:
                vx = (short) data[4]; vy = (short) (data[4] >>> 16); vz = (short) data[5];
                break;
            default:
                vx = (short) data[9]; vy = (short) data[10]; vz = (short) data[11];
                break;
        }
        int translationBase = translationSelector == 0 ? 5
                : translationSelector == 1 ? 13 : translationSelector == 2 ? 21 : -1;
        int shift = shiftFraction ? 12 : 0;
        for (int row = 0; row < 3; row++) {
            int translation = translationBase < 0 ? 0 : control[translationBase + row];
            int m0 = matrixElement(matrixBase, row, 0);
            int m1 = matrixElement(matrixBase, row, 1);
            int m2 = matrixElement(matrixBase, row, 2);
            long raw;
            if (translationSelector == 2) {
                long firstPart = ((long) translation << 12) + (long) m0 * vx;
                checkMacOverflow(firstPart, 30 - row, 27 - row);
                saturateIr(firstPart >> shift, row + 1, false);
                raw = (long) m1 * vy + (long) m2 * vz;
            } else {
                raw = ((long) translation << 12)
                        + (long) m0 * vx + (long) m1 * vy + (long) m2 * vz;
            }
            checkMacOverflow(raw, 30 - row, 27 - row);
            int mac = (int) (raw >> shift);
            data[25 + row] = mac;
            data[9 + row] = saturateIr(mac, row + 1, limitMode);
        }
        return true;
    }

    private long transformRow(int row, int vx, int vy, int vz, int translation) {
        return ((long) translation << 12)
                + (long) matrixElement(0, row, 0) * vx
                + (long) matrixElement(0, row, 1) * vy
                + (long) matrixElement(0, row, 2) * vz;
    }

    private int matrixElement(int base, int row, int column) {
        int element = row * 3 + column;
        int packed = control[base + element / 2];
        return (element & 1) == 0 ? (short) packed : (short) (packed >>> 16);
    }

    private int perspectiveQuotient(int h, int depth) {
        if (depth == 0 || h >= (long) depth * 2L) {
            setFlag(1 << 17);
            return 0x1FFFF;
        }
        return (int) ((((long) h * 0x20000L / depth) + 1L) / 2L);
    }

    private int saturateIr(int value, int register) {
        return saturateIr(value, register, false);
    }

    private int saturateIr(int value, int register, boolean limitMode) {
        return saturateIr((long) value, register, limitMode);
    }

    private int saturateIr(long value, int register, boolean limitMode) {
        long minimum = limitMode ? 0 : -0x8000;
        if (value > 0x7FFF) {
            setFlag(1 << (25 - register));
            return 0x7FFF;
        }
        if (value < minimum) {
            setFlag(1 << (25 - register));
            return (int) minimum;
        }
        return (int) value;
    }

    private int saturateIr0(int value) {
        if (value < 0) { setFlag(1 << 12); return 0; }
        if (value > 0x1000) { setFlag(1 << 12); return 0x1000; }
        return value;
    }

    private int saturateUnsigned16(long value, int flagBit) {
        if (value < 0) { setFlag(1 << flagBit); return 0; }
        if (value > 0xFFFF) { setFlag(1 << flagBit); return 0xFFFF; }
        return (int) value;
    }

    private int saturateScreen(int value, int flagBit) {
        if (value < -0x400) { setFlag(1 << flagBit); return -0x400; }
        if (value > 0x3FF) { setFlag(1 << flagBit); return 0x3FF; }
        return value;
    }

    private int storeMac0(long value) {
        if (value > Integer.MAX_VALUE) setFlag(1 << 16);
        else if (value < Integer.MIN_VALUE) setFlag(1 << 15);
        data[24] = (int) value;
        return data[24];
    }

    private void checkMacOverflow(long value, int positiveBit, int negativeBit) {
        long max = (1L << 43) - 1L;
        long min = -(1L << 43);
        if (value > max) setFlag(1 << positiveBit);
        else if (value < min) setFlag(1 << negativeBit);
    }

    private void setFlag(int mask) {
        control[31] |= mask;
    }

    private void updateFlagSummary() {
        int flags = control[31] & 0x7FFFF000;
        control[31] = flags | (flags == 0 ? 0 : 0x80000000);
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
