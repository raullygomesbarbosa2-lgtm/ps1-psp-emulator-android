package com.raully.psxpsp.ps1;

/** Minimal digital-pad SIO0 polling endpoint for the first controller port. */
public final class Ps1ControllerPort {
    public static final int BASE_ADDRESS = 0x1F801040;
    public static final int DATA = BASE_ADDRESS;
    public static final int STATUS = BASE_ADDRESS + 4;
    public static final int MODE = BASE_ADDRESS + 8;
    public static final int CONTROL = BASE_ADDRESS + 0xA;
    public static final int BAUD = BASE_ADDRESS + 0xE;

    public static final int SELECT = 1 << 0;
    public static final int L3 = 1 << 1;
    public static final int R3 = 1 << 2;
    public static final int START = 1 << 3;
    public static final int UP = 1 << 4;
    public static final int RIGHT = 1 << 5;
    public static final int DOWN = 1 << 6;
    public static final int LEFT = 1 << 7;
    public static final int L2 = 1 << 8;
    public static final int R2 = 1 << 9;
    public static final int L1 = 1 << 10;
    public static final int R1 = 1 << 11;
    public static final int TRIANGLE = 1 << 12;
    public static final int CIRCLE = 1 << 13;
    public static final int CROSS = 1 << 14;
    public static final int SQUARE = 1 << 15;
    public static final int ALL_BUTTONS = 0xFFFF;

    private volatile int pressedButtons;
    private int status = 1; // TX ready
    private int control;
    private int mode;
    private int baud;
    private int reply = 0xFF;
    private int pollPhase;
    private boolean receiveReady;

    public void setPressedButtons(int mask) { pressedButtons = mask & ALL_BUTTONS; }
    public int getPressedButtons() { return pressedButtons; }

    public synchronized int readRegister(int offset) {
        switch (offset & ~1) {
            case 0:
                int value = receiveReady ? reply : 0xFF;
                receiveReady = false;
                status &= ~2;
                return value;
            case 4: return status;
            case 8: return mode;
            case 10: return control;
            case 14: return baud;
            default: return 0;
        }
    }

    public synchronized void writeRegister(int offset, int value) {
        value &= 0xFF;
        switch (offset) {
            case 0: writeData(value); break;
            case 8: mode = value; break;
            case 10:
                control = value;
                if ((value & 0x40) != 0) resetPort();
                break;
            case 14: baud = value; break;
            default: break;
        }
    }

    public synchronized void reset() {
        pressedButtons = 0;
        resetPort();
    }

    private void resetPort() {
        status = 1;
        reply = 0xFF;
        pollPhase = 0;
        receiveReady = false;
        control = 0;
        mode = 0;
        baud = 0;
    }

    private void writeData(int value) {
        status |= 1;
        if (pollPhase == 0) {
            if (value == 0x01) {
                reply = 0xFF;
                pollPhase = 1;
                readyReply();
            } else {
                pollPhase = 0;
                readyReply();
            }
            return;
        }
        if (pollPhase == 1) {
            if (value == 0x42) {
                reply = 0x41; // digital pad identifier
                pollPhase = 2;
            } else {
                reply = 0xFF;
                pollPhase = 0;
            }
        } else if (pollPhase == 2) {
            reply = 0x5A;
            pollPhase = 3;
        } else if (pollPhase == 3) {
            reply = (~pressedButtons) & 0xFF;
            pollPhase = 4;
        } else if (pollPhase == 4) {
            reply = ((~pressedButtons) >>> 8) & 0xFF;
            pollPhase = 0;
        } else {
            reply = 0xFF;
            pollPhase = 0;
        }
        readyReply();
    }

    private void readyReply() {
        receiveReady = true;
        status |= 2;
    }
}
