package com.raully.psxpsp.ps1;

/** Initial PS1 DMA channels for CD-ROM, GPU and ordering-table clear. */
public final class Ps1DmaController {
    public static final int BASE_ADDRESS = 0x1F801080;
    public static final int DPCR_ADDRESS = 0x1F8010F0;
    public static final int DICR_ADDRESS = 0x1F8010F4;
    private static final int CHANNEL_COUNT = 7;
    private static final int ENABLE = 1 << 24;
    private static final int TRIGGER = 1 << 28;
    private static final int DICR_CONTROL_MASK = 0x00FF8000;
    private static final int DICR_FLAG_MASK = 0x7F000000;
    private static final int MAX_DMA_WORDS = 16 * 1024 * 1024;
    private static final int MAX_LINKED_LIST_NODES = 1 << 16;

    private final Ps1MemoryBus bus;
    private final Ps1InterruptController interruptController;
    private final Ps1Gpu gpu;
    private final Ps1CdromController cdrom;
    private final int[] channelRegisters = new int[CHANNEL_COUNT * 3];
    private final boolean[] active = new boolean[CHANNEL_COUNT];
    private final long[] wordsRemaining = new long[CHANNEL_COUNT];
    private int dpcr = 0x07654321;
    private int dicrControl;
    private int dicrFlags;
    private boolean servicing;

    Ps1DmaController(Ps1MemoryBus bus, Ps1InterruptController interruptController,
                     Ps1Gpu gpu, Ps1CdromController cdrom) {
        if (bus == null || interruptController == null || gpu == null || cdrom == null) {
            throw new IllegalArgumentException("DMA dependencies are required");
        }
        this.bus = bus;
        this.interruptController = interruptController;
        this.gpu = gpu;
        this.cdrom = cdrom;
    }

    public boolean isMappedAddress(int address) {
        return address >= BASE_ADDRESS && address < BASE_ADDRESS + 0x78;
    }

    public synchronized int readRegister(int offset) {
        int relative = offset & ~3;
        if (relative >= 0 && relative < 0x70) {
            int channel = relative >>> 4;
            int register = (relative & 0x0F) >>> 2;
            if (channel >= CHANNEL_COUNT || register > 2) return 0;
            return channelRegisters[channel * 3 + register];
        }
        if (relative == 0x70) return dpcr;
        if (relative == 0x74) return readDicr();
        return 0;
    }

    public synchronized void writeRegister(int offset, int value, int writtenMask) {
        int relative = offset & ~3;
        if (relative >= 0 && relative < 0x70) {
            int channel = relative >>> 4;
            int register = (relative & 0x0F) >>> 2;
            if (channel >= CHANNEL_COUNT || register > 2) return;
            int slot = channel * 3 + register;
            int oldValue = channelRegisters[slot];
            int merged = (oldValue & ~writtenMask) | (value & writtenMask);
            if (register == 0) merged &= 0x00FFFFFC;
            channelRegisters[slot] = merged;
            if (register == 2) servicePendingTransfers();
            return;
        }
        if (relative == 0x70) {
            dpcr = (dpcr & ~writtenMask) | (value & writtenMask);
            servicePendingTransfers();
        } else if (relative == 0x74) {
            int controlMask = writtenMask & DICR_CONTROL_MASK;
            dicrControl = (dicrControl & ~controlMask) | (value & controlMask);
            dicrFlags &= ~((value & writtenMask) & DICR_FLAG_MASK);
            updateDmaInterrupt();
        }
    }

    /** Retry channel requests after a device (notably CD-ROM) produces data. */
    public synchronized void servicePendingTransfers() {
        if (servicing) return;
        servicing = true;
        try {
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                try {
                    if (!active[channel]) startIfReady(channel);
                    if (active[channel]) serviceChannel(channel);
                } catch (RuntimeException transferFailure) {
                    finishChannel(channel);
                }
            }
        } finally {
            servicing = false;
        }
    }

    public synchronized void reset() {
        for (int i = 0; i < channelRegisters.length; i++) channelRegisters[i] = 0;
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            active[i] = false;
            wordsRemaining[i] = 0;
        }
        dpcr = 0x07654321;
        dicrControl = 0;
        dicrFlags = 0;
        servicing = false;
    }

    public synchronized boolean isChannelActive(int channel) {
        checkChannel(channel);
        return active[channel];
    }

    private void startIfReady(int channel) {
        if (active[channel] || !isEnabled(channel)) return;
        int chcr = channelRegisters[channel * 3 + 2];
        if ((chcr & ENABLE) == 0) return;
        int syncMode = (chcr >>> 9) & 3;
        if (syncMode == 0 && (chcr & TRIGGER) == 0) return;
        active[channel] = true;
        if (syncMode == 2 && channel == 2) {
            serviceGpuLinkedList(channel);
            return;
        }
        long count = transferWordCount(channel, syncMode);
        if (count <= 0 || count > MAX_DMA_WORDS) {
            finishChannel(channel);
            return;
        }
        wordsRemaining[channel] = count;
        serviceChannel(channel);
    }

    private long transferWordCount(int channel, int syncMode) {
        int bcr = channelRegisters[channel * 3 + 1];
        long blockSize = bcr & 0xFFFFL;
        if (blockSize == 0) blockSize = 0x10000L;
        if (syncMode == 0) return blockSize;
        if (syncMode == 1) {
            long blockCount = (bcr >>> 16) & 0xFFFFL;
            if (blockCount == 0) blockCount = 0x10000L;
            return blockSize * blockCount;
        }
        return -1;
    }

    private void serviceChannel(int channel) {
        if (!isEnabled(channel)) return;
        if (channel == 3) {
            serviceCdrom(channel);
        } else if (channel == 2) {
            serviceGpu(channel);
        } else if (channel == 6) {
            serviceOrderingTableClear(channel);
        } else {
            finishChannel(channel);
        }
    }

    private void serviceCdrom(int channel) {
        int chcr = channelRegisters[channel * 3 + 2];
        if ((chcr & 1) != 0 || ((chcr >>> 9) & 3) > 1) {
            finishChannel(channel);
            return;
        }
        while (wordsRemaining[channel] > 0) {
            int word = cdrom.readDmaWord();
            if (word < 0) return; // Wait for a complete sector word in the CD-ROM FIFO.
            int address = channelRegisters[channel * 3] & 0x00FFFFFC;
            bus.write32(address, word);
            advanceAddress(channel);
            wordsRemaining[channel]--;
        }
        finishChannel(channel);
    }

    private void serviceGpu(int channel) {
        int chcr = channelRegisters[channel * 3 + 2];
        boolean fromRam = (chcr & 1) != 0;
        if (((chcr >>> 9) & 3) > 1) {
            finishChannel(channel);
            return;
        }
        while (wordsRemaining[channel] > 0) {
            if (!fromRam && !gpu.hasReadbackData()) return;
            int address = channelRegisters[channel * 3] & 0x00FFFFFC;
            if (fromRam) gpu.writeDataWord(bus.read32(address));
            else bus.write32(address, gpu.readDataWord());
            advanceAddress(channel);
            wordsRemaining[channel]--;
        }
        finishChannel(channel);
    }

    private void serviceGpuLinkedList(int channel) {
        int chcr = channelRegisters[channel * 3 + 2];
        if ((chcr & 1) == 0 || !isEnabled(channel)) {
            finishChannel(channel);
            return;
        }
        int address = channelRegisters[channel * 3] & 0x00FFFFFC;
        for (int node = 0; node < MAX_LINKED_LIST_NODES; node++) {
            int header = bus.read32(address);
            int count = (header >>> 24) & 0xFF;
            int next = header & 0x00FFFFFC;
            address = (address + 4) & 0x00FFFFFC;
            for (int i = 0; i < count; i++) {
                gpu.writeDataWord(bus.read32(address));
                address = (address + 4) & 0x00FFFFFC;
            }
            if ((header & 0x00FFFFFF) == 0x00FFFFFF) {
                channelRegisters[channel * 3] = address;
                finishChannel(channel);
                return;
            }
            address = next;
        }
        finishChannel(channel);
    }

    private void serviceOrderingTableClear(int channel) {
        int chcr = channelRegisters[channel * 3 + 2];
        if ((chcr & 1) == 0 || ((chcr >>> 9) & 3) != 0) {
            finishChannel(channel);
            return;
        }
        int address = channelRegisters[channel * 3] & 0x00FFFFFC;
        long count = wordsRemaining[channel];
        for (long i = 0; i < count; i++) {
            int previous = (address - 4) & 0x00FFFFFC;
            bus.write32(address, i + 1 == count ? 0x00FFFFFF : previous);
            address = previous;
        }
        channelRegisters[channel * 3] = address;
        wordsRemaining[channel] = 0;
        finishChannel(channel);
    }

    private void advanceAddress(int channel) {
        int slot = channel * 3;
        int address = channelRegisters[slot] & 0x00FFFFFC;
        channelRegisters[slot] = (address + (((channelRegisters[slot + 2] & 2) != 0) ? -4 : 4)) & 0x00FFFFFC;
    }

    private void finishChannel(int channel) {
        active[channel] = false;
        wordsRemaining[channel] = 0;
        int slot = channel * 3 + 2;
        channelRegisters[slot] &= ~(ENABLE | TRIGGER);
        dicrFlags |= 1 << (24 + channel);
        updateDmaInterrupt();
    }

    private boolean isEnabled(int channel) {
        return ((dpcr >>> (channel * 4 + 3)) & 1) != 0;
    }

    private int readDicr() {
        int value = (dicrControl & DICR_CONTROL_MASK) | dicrFlags;
        boolean master = (dicrControl & (1 << 15)) != 0
                || (((dicrControl >>> 16) & 0x7F & (dicrFlags >>> 24)) != 0
                && (dicrControl & (1 << 23)) != 0);
        if (master) value |= 1 << 31;
        return value;
    }

    private void updateDmaInterrupt() {
        if ((readDicr() & 0x80000000) != 0) interruptController.request(Ps1InterruptController.IRQ_DMA);
    }

    private static void checkChannel(int channel) {
        if (channel < 0 || channel >= CHANNEL_COUNT) throw new IllegalArgumentException("invalid DMA channel");
    }
}
