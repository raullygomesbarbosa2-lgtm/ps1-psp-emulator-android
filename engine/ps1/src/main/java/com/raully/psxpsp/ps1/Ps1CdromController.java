package com.raully.psxpsp.ps1;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Initial, synchronous-command model of PS1 CD-ROM registers and data-sector delivery. */
public final class Ps1CdromController {
    public static final int BASE_ADDRESS = 0x1F801800;
    public static final long SINGLE_SPEED_SECTOR_CYCLES = 451_584L;

    private static final int STATUS_READING = 1 << 5;
    private static final int IRQ_INT1 = 1;
    private static final int IRQ_INT3 = 3;
    private static final int IRQ_INT5 = 5;

    private final Ps1InterruptController interruptController;
    private final ArrayDeque<Integer> parameters = new ArrayDeque<>();
    private final ArrayDeque<Integer> response = new ArrayDeque<>();
    private final ArrayDeque<Integer> dataFifo = new ArrayDeque<>();
    private int index;
    private int interruptEnable;
    private int interruptFlags;
    private int requestRegister;
    private int driveStatus;
    private int readMode;
    private int setLocationLba;
    private int nextReadLba;
    private boolean hasSetLocation;
    private boolean reading;
    private boolean busy;
    private byte[] pendingSector;
    private long sectorCycleAccumulator;
    private Ps1DiscImage discImage;

    public Ps1CdromController(Ps1InterruptController interruptController) {
        if (interruptController == null) throw new IllegalArgumentException("interrupt controller is required");
        this.interruptController = interruptController;
    }

    public synchronized void insertDisc(Ps1DiscImage image) throws IOException {
        if (image == null) throw new IllegalArgumentException("disc image is required");
        if (discImage != null && discImage != image) discImage.close();
        discImage = image;
        driveStatus = 0x02;
    }

    public synchronized void ejectDisc() throws IOException {
        if (discImage != null) discImage.close();
        discImage = null;
        stopReading();
        driveStatus = 0;
        hasSetLocation = false;
    }

    public synchronized boolean hasDisc() { return discImage != null; }
    public synchronized int getReadMode() { return readMode; }

    public synchronized int readRegister(int registerOffset) {
        int offset = registerOffset & 3;
        if (offset == 0) return readStatusRegister();
        if (offset == 1) return popByte(response);
        if (offset == 2) return popByte(dataFifo); // Data FIFO reads mirror across all indexes.
        if (index == 0 || index == 2) return interruptEnable | 0xE0;
        if (index == 1 || index == 3) return interruptFlags | 0xE0;
        return 0xFF;
    }

    public synchronized void writeRegister(int registerOffset, int value) {
        int offset = registerOffset & 3;
        int data = value & 0xFF;
        if (offset == 0) {
            index = data & 3;
            return;
        }
        if (offset == 1) {
            if (index == 0) executeCommand(data);
            return; // Read-only response FIFO or unimplemented sound-map ports.
        }
        if (offset == 2) {
            if (index == 0) {
                if (parameters.size() < 16) parameters.addLast(data);
            } else if (index == 1) {
                interruptEnable = data & 0x1F;
                requestEnabledInterruptIfPending();
            }
            return;
        }
        if (index == 0) {
            setRequestRegister(data);
        } else if (index == 1) {
            acknowledgeAndControl(data);
        }
    }

    /** Advance the provisional CD sector clock. At most one unread sector is staged at once. */
    public synchronized void tickSystemCycles(long cycles) {
        if (!reading || cycles <= 0) return;
        if (sectorCycleAccumulator > Long.MAX_VALUE - cycles) sectorCycleAccumulator = Long.MAX_VALUE;
        else sectorCycleAccumulator += cycles;
        if (pendingSector != null || !dataFifo.isEmpty() || interruptFlags != 0
                || sectorCycleAccumulator < SINGLE_SPEED_SECTOR_CYCLES) return;
        sectorCycleAccumulator -= SINGLE_SPEED_SECTOR_CYCLES;
        byte[] payload = new byte[2324];
        try {
            int length = discImage.readUserDataSectorAtLba(nextReadLba, payload, 0);
            pendingSector = Arrays.copyOf(payload, length);
            nextReadLba++;
            emitResponse(IRQ_INT1, driveStatus);
        } catch (IOException | RuntimeException failure) {
            stopReading();
            emitResponse(IRQ_INT5, driveStatus, 0x40);
        }
    }

    public synchronized void reset() {
        index = 0;
        parameters.clear();
        response.clear();
        dataFifo.clear();
        interruptEnable = 0;
        interruptFlags = 0;
        requestRegister = 0;
        readMode = 0;
        setLocationLba = 0;
        nextReadLba = 0;
        hasSetLocation = false;
        reading = false;
        busy = false;
        pendingSector = null;
        sectorCycleAccumulator = 0;
        driveStatus = discImage == null ? 0 : 0x02;
    }

    private int readStatusRegister() {
        int value = index;
        if (parameters.isEmpty()) value |= 1 << 3;
        if (parameters.size() < 16) value |= 1 << 4;
        if (!response.isEmpty()) value |= 1 << 5;
        if (!dataFifo.isEmpty()) value |= 1 << 6;
        if (busy) value |= 1 << 7;
        return value;
    }

    private void executeCommand(int command) {
        busy = true;
        try {
            switch (command) {
                case 0x01: // Getstat
                    if (!requireParameterCount(0)) return;
                    emitResponse(IRQ_INT3, driveStatus);
                    break;
                case 0x02: // Setloc (BCD MSF, including the 150-sector lead-in)
                    executeSetloc();
                    break;
                case 0x06: // ReadN
                    executeReadN();
                    break;
                case 0x09: // Pause
                    if (!requireParameterCount(0)) return;
                    stopReading();
                    driveStatus &= ~STATUS_READING;
                    emitResponse(IRQ_INT3, driveStatus);
                    break;
                case 0x0A: // Init
                    if (!requireParameterCount(0)) return;
                    stopReading();
                    hasSetLocation = false;
                    readMode = 0;
                    driveStatus = discImage == null ? 0 : 0x02;
                    emitResponse(IRQ_INT3, driveStatus);
                    break;
                case 0x0E: // Setmode
                    executeSetmode();
                    break;
                default:
                    parameters.clear();
                    emitResponse(IRQ_INT5, driveStatus, 0x40);
                    break;
            }
        } finally {
            busy = false;
        }
    }

    private void executeSetloc() {
        int[] values = takeParameters(3);
        if (values == null) { emitResponse(IRQ_INT5, driveStatus, 0x20); return; }
        int minutes = decodeBcd(values[0]);
        int seconds = decodeBcd(values[1]);
        int frames = decodeBcd(values[2]);
        if (minutes < 0 || seconds < 0 || seconds >= 60 || frames < 0 || frames >= 75) {
            emitResponse(IRQ_INT5, driveStatus, 0x20);
            return;
        }
        long absoluteFrame = ((long) minutes * 60L + seconds) * 75L + frames;
        long lba = absoluteFrame - 150L;
        if (lba < 0 || lba > Integer.MAX_VALUE) {
            emitResponse(IRQ_INT5, driveStatus, 0x20);
            return;
        }
        setLocationLba = (int) lba;
        hasSetLocation = true;
        emitResponse(IRQ_INT3, driveStatus);
    }

    private void executeSetmode() {
        int[] values = takeParameters(1);
        if (values == null) { emitResponse(IRQ_INT5, driveStatus, 0x20); return; }
        readMode = values[0];
        emitResponse(IRQ_INT3, driveStatus);
    }

    private void executeReadN() {
        if (!requireParameterCount(0)) return;
        if (discImage == null) {
            emitResponse(IRQ_INT5, driveStatus, 0x40);
            return;
        }
        if (hasSetLocation) {
            nextReadLba = setLocationLba;
            hasSetLocation = false;
        }
        if (nextReadLba < 0 || nextReadLba >= discImage.getDiscSectorCount()) {
            emitResponse(IRQ_INT5, driveStatus, 0x40);
            return;
        }
        dataFifo.clear();
        pendingSector = null;
        requestRegister &= ~0x80;
        reading = true;
        driveStatus |= STATUS_READING;
        sectorCycleAccumulator = 0;
        emitResponse(IRQ_INT3, driveStatus);
    }

    private boolean requireParameterCount(int expected) {
        if (parameters.size() == expected) return true;
        parameters.clear();
        emitResponse(IRQ_INT5, driveStatus, 0x20);
        return false;
    }

    private int[] takeParameters(int expected) {
        if (parameters.size() != expected) {
            parameters.clear();
            return null;
        }
        int[] values = new int[expected];
        for (int index = 0; index < expected; index++) values[index] = parameters.removeFirst();
        return values;
    }

    private void setRequestRegister(int value) {
        requestRegister = value & 0xE0;
        if ((requestRegister & 0x80) == 0) {
            dataFifo.clear();
            pendingSector = null;
            return;
        }
        if (pendingSector != null && dataFifo.isEmpty()) {
            for (byte data : pendingSector) dataFifo.addLast(data & 0xFF);
            pendingSector = null;
        }
    }

    private void acknowledgeAndControl(int value) {
        if ((value & 0x80) != 0) interruptFlags = 0;
        if ((value & 0x40) != 0) parameters.clear();
    }

    private void emitResponse(int interruptCode, int... bytes) {
        response.clear();
        for (int value : bytes) response.addLast(value & 0xFF);
        interruptFlags = interruptCode & 7;
        requestEnabledInterruptIfPending();
    }

    private void requestEnabledInterruptIfPending() {
        if (interruptFlags > 0 && (interruptEnable & (1 << (interruptFlags - 1))) != 0) {
            interruptController.request(Ps1InterruptController.IRQ_CDROM);
        }
    }

    private void stopReading() {
        reading = false;
        driveStatus &= ~STATUS_READING;
        pendingSector = null;
        dataFifo.clear();
        requestRegister &= ~0x80;
        sectorCycleAccumulator = 0;
    }

    private static int decodeBcd(int value) {
        int low = value & 0x0F;
        int high = (value >>> 4) & 0x0F;
        if (low > 9 || high > 9) return -1;
        return high * 10 + low;
    }

    private static int popByte(ArrayDeque<Integer> fifo) {
        Integer value = fifo.pollFirst();
        return value == null ? 0 : value;
    }
}
