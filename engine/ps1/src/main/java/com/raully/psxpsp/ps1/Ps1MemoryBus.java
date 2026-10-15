package com.raully.psxpsp.ps1;

import java.util.Arrays;

/**
 * Initial PS1 address bus: mirrored 2 MiB RAM, 1 KiB scratchpad, selected
 * memory-control registers, interrupt controller, video timing, digital SIO0 pad,
 * CD-ROM registers and partial GP0/GP1 GPU ports. Other MMIO, ROM and expansion regions fault explicitly.
 */
public final class Ps1MemoryBus implements Ps1Bus {
    public static final int RAM_SIZE = 2 * 1024 * 1024;
    public static final int RAM_MIRROR_SIZE = 8 * 1024 * 1024;
    public static final int SCRATCHPAD_START = 0x1F800000;
    public static final int SCRATCHPAD_SIZE = 1024;
    public static final int INTERRUPT_STATUS = 0x1F801070;
    public static final int INTERRUPT_MASK = 0x1F801074;
    public static final int CDROM_BASE = Ps1CdromController.BASE_ADDRESS;
    public static final int CONTROLLER_BASE = Ps1ControllerPort.BASE_ADDRESS;
    public static final int DMA_BASE = Ps1DmaController.BASE_ADDRESS;
    public static final int GPU_CONTROL = 0x1F801814;

    private final byte[] ram = new byte[RAM_SIZE];
    private final byte[] scratchpad = new byte[SCRATCHPAD_SIZE];
    private final Ps1InterruptController interruptController = new Ps1InterruptController();
    private final Ps1MemoryControl memoryControl = new Ps1MemoryControl();
    private final Ps1Timers timers = new Ps1Timers(interruptController);
    private final Ps1VideoTiming videoTiming = new Ps1VideoTiming(timers);
    private final Ps1Gpu gpu = new Ps1Gpu(videoTiming, interruptController);
    private final Ps1CdromController cdrom = new Ps1CdromController(interruptController);
    private final Ps1ControllerPort controllerPort = new Ps1ControllerPort();
    private final Ps1DmaController dma;

    public Ps1MemoryBus() {
        dma = new Ps1DmaController(this, interruptController, gpu, cdrom);
    }

    public Ps1InterruptController getInterruptController() { return interruptController; }
    public Ps1MemoryControl getMemoryControl() { return memoryControl; }
    public Ps1Timers getTimers() { return timers; }
    public Ps1VideoTiming getVideoTiming() { return videoTiming; }
    public Ps1Gpu getGpu() { return gpu; }
    public Ps1CdromController getCdrom() { return cdrom; }
    public Ps1ControllerPort getControllerPort() { return controllerPort; }
    public Ps1DmaController getDma() { return dma; }

    @Override
    public boolean isInterruptPending() {
        return interruptController.isPending();
    }

    @Override
    public int read8(int address) {
        int physicalAddress = toPhysicalAddress(address, false);
        if (isCdromAddress(physicalAddress)) {
            return cdrom.readRegister(physicalAddress - CDROM_BASE);
        }
        if (isControllerAddress(physicalAddress)) {
            return readControllerByte(physicalAddress - CONTROLLER_BASE);
        }
        if (dma.isMappedAddress(physicalAddress)) {
            int word = dma.readRegister(physicalAddress - DMA_BASE);
            return (word >>> ((physicalAddress & 3) * 8)) & 0xFF;
        }
        if (timers.isMappedAddress(physicalAddress)) {
            int word = timers.readRegister(physicalAddress & ~3);
            return (word >>> ((physicalAddress & 3) * 8)) & 0xFF;
        }
        if (isGpuDataRegister(physicalAddress)) {
            return gpu.readDataByte(physicalAddress - Ps1Gpu.DATA_PORT);
        }
        if (isGpuControlRegister(physicalAddress)) {
            return (gpu.readStatus() >>> ((physicalAddress - GPU_CONTROL) * 8)) & 0xFF;
        }
        if (isInterruptRegister(physicalAddress)) {
            return interruptController.readByte(physicalAddress - INTERRUPT_STATUS);
        }
        if (memoryControl.isMappedAddress(physicalAddress)) return memoryControl.readByte(physicalAddress);
        Region region = mapMemory(address, physicalAddress, false);
        return region.bytes[region.index] & 0xFF;
    }

    @Override
    public int read16(int address) {
        int physicalAddress = toPhysicalAddress(address, false);
        if (isControllerAddress(physicalAddress)) {
            return readControllerValue(physicalAddress - CONTROLLER_BASE, 2);
        }
        if (timers.isMappedAddress(physicalAddress)) {
            int word = timers.readRegister(physicalAddress & ~3);
            return (word >>> ((physicalAddress & 2) * 8)) & 0xFFFF;
        }
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(int address) {
        int physicalAddress = toPhysicalAddress(address, false);
        if (isControllerAddress(physicalAddress)) {
            return readControllerValue(physicalAddress - CONTROLLER_BASE, 4);
        }
        if (timers.isMappedAddress(physicalAddress)) return timers.readRegister(physicalAddress & ~3) & 0xFFFF;
        if (dma.isMappedAddress(physicalAddress) && (physicalAddress & 3) == 0) {
            return dma.readRegister(physicalAddress - DMA_BASE);
        }
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(int address, int value) {
        int physicalAddress = toPhysicalAddress(address, true);
        if (isCdromAddress(physicalAddress)) {
            cdrom.writeRegister(physicalAddress - CDROM_BASE, value);
            return;
        }
        if (isControllerAddress(physicalAddress)) {
            writeControllerByte(physicalAddress - CONTROLLER_BASE, value);
            return;
        }
        if (dma.isMappedAddress(physicalAddress)) {
            int shift = (physicalAddress & 3) * 8;
            dma.writeRegister(physicalAddress - DMA_BASE, (value & 0xFF) << shift, 0xFF << shift);
            return;
        }
        if (timers.isMappedAddress(physicalAddress)) {
            int shift = (physicalAddress & 3) * 8;
            int mask = 0xFF << shift;
            if ((mask & 0xFFFF) != 0) timers.writeRegister(physicalAddress & ~3, (value & 0xFF) << shift, mask);
            return;
        }
        if (isGpuDataRegister(physicalAddress)) {
            gpu.writeDataByte(physicalAddress - Ps1Gpu.DATA_PORT, value);
            return;
        }
        if (isGpuControlRegister(physicalAddress)) {
            gpu.writeControlByte(physicalAddress - GPU_CONTROL, value);
            return;
        }
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
        int physicalAddress = toPhysicalAddress(address, true);
        if (isControllerAddress(physicalAddress)) {
            writeControllerValue(physicalAddress - CONTROLLER_BASE, value, 2);
            return;
        }
        if (timers.isMappedAddress(physicalAddress)) {
            int shift = (physicalAddress & 2) * 8;
            int mask = 0xFFFF << shift;
            if ((mask & 0xFFFF) != 0) timers.writeRegister(physicalAddress & ~3, (value & 0xFFFF) << shift, mask);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        int physicalAddress = toPhysicalAddress(address, true);
        if (isControllerAddress(physicalAddress)) {
            writeControllerValue(physicalAddress - CONTROLLER_BASE, value, 4);
            return;
        }
        if (physicalAddress == Ps1Gpu.DATA_PORT) {
            gpu.writeDataWord(value);
            return;
        }
        if (physicalAddress == GPU_CONTROL) {
            gpu.writeControlWord(value);
            return;
        }
        if (dma.isMappedAddress(physicalAddress) && (physicalAddress & 3) == 0) {
            dma.writeRegister(physicalAddress - DMA_BASE, value, -1);
            return;
        }
        if (timers.isMappedAddress(physicalAddress)) {
            timers.writeRegister(physicalAddress & ~3, value, 0xFFFF);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    /** Clear RAM/scratchpad and reset implemented control, IRQ, timer, CD-ROM, video and GPU blocks. */
    public void clear() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(scratchpad, (byte) 0);
        interruptController.reset();
        memoryControl.resetToCommonBiosValues();
        timers.reset();
        cdrom.reset();
        controllerPort.reset();
        dma.reset();
        gpu.clear();
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

    private boolean isCdromAddress(int physicalAddress) {
        return physicalAddress >= CDROM_BASE && physicalAddress < CDROM_BASE + 4;
    }

    private boolean isControllerAddress(int physicalAddress) {
        return physicalAddress >= CONTROLLER_BASE && physicalAddress < CONTROLLER_BASE + 0x10;
    }

    private int readControllerByte(int offset) {
        if (offset == 0) return controllerPort.readRegister(0) & 0xFF;
        int registerOffset;
        int lane;
        if (offset >= 4 && offset <= 7) { registerOffset = 4; lane = offset - 4; }
        else if (offset >= 8 && offset <= 9) { registerOffset = 8; lane = offset - 8; }
        else if (offset >= 10 && offset <= 11) { registerOffset = 10; lane = offset - 10; }
        else if (offset >= 14 && offset <= 15) { registerOffset = 14; lane = offset - 14; }
        else return 0;
        return (controllerPort.readRegister(registerOffset) >>> (lane * 8)) & 0xFF;
    }

    private int readControllerValue(int offset, int size) {
        if (offset == 0) return controllerPort.readRegister(0) & 0xFF;
        if (offset == 4) return controllerPort.readRegister(4) & (size == 2 ? 0xFFFF : -1);
        if (offset == 8) return controllerPort.readRegister(8) & (size == 2 ? 0xFFFF : -1);
        if (offset == 10) return controllerPort.readRegister(10) & (size == 2 ? 0xFFFF : -1);
        if (offset == 14) return controllerPort.readRegister(14) & (size == 2 ? 0xFFFF : -1);
        return readControllerByte(offset) | (size >= 2 ? readControllerByte(offset + 1) << 8 : 0)
                | (size >= 4 ? readControllerByte(offset + 2) << 16 | readControllerByte(offset + 3) << 24 : 0);
    }

    private void writeControllerByte(int offset, int value) {
        if (offset == 0) { controllerPort.writeRegister(0, value); return; }
        int registerOffset;
        int lane;
        if (offset >= 8 && offset <= 9) { registerOffset = 8; lane = offset - 8; }
        else if (offset >= 10 && offset <= 11) { registerOffset = 10; lane = offset - 10; }
        else if (offset >= 14 && offset <= 15) { registerOffset = 14; lane = offset - 14; }
        else return;
        if (lane == 0) controllerPort.writeRegister(registerOffset, value);
    }

    private void writeControllerValue(int offset, int value, int size) {
        if (offset == 0) { controllerPort.writeRegister(0, value); return; }
        if (offset == 8 || offset == 10 || offset == 14) controllerPort.writeRegister(offset, value);
        else for (int i = 0; i < size; i++) writeControllerByte(offset + i, value >>> (i * 8));
    }

    private boolean isGpuDataRegister(int physicalAddress) {
        return physicalAddress >= Ps1Gpu.DATA_PORT && physicalAddress < Ps1Gpu.DATA_PORT + 4;
    }

    private boolean isGpuControlRegister(int physicalAddress) {
        return physicalAddress >= GPU_CONTROL && physicalAddress < GPU_CONTROL + 4;
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
