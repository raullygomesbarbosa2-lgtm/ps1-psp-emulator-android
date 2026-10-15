package com.raully.psxpsp.ps1;

import java.util.Arrays;

/**
 * Early PS1 GPU model: GP1 display control, a 1024x512 15-bit VRAM, basic GP0
 * VRAM operations, and packet consumption for common primitive commands.
 * Polygon/line/texture rasterization and a timed FIFO are not implemented.
 */
public final class Ps1Gpu {
    public static final int DATA_PORT = 0x1F801810;
    public static final int CONTROL_PORT = 0x1F801814;
    public static final int VRAM_WIDTH = 1024;
    public static final int VRAM_HEIGHT = 512;
    private static final int VRAM_PIXELS = VRAM_WIDTH * VRAM_HEIGHT;
    private static final int MAX_PACKET_WORDS = 16;
    private static final int POLYLINE_END = 0x50005000;

    private final Ps1VideoTiming videoTiming;
    private final Ps1InterruptController interruptController;
    private final short[] vram = new short[VRAM_PIXELS];
    private final int[] packet = new int[MAX_PACKET_WORDS];

    private int controlWriteLatch;
    private int dataWriteLatch;
    private int displayMode;
    private int displayStart;
    private int horizontalRange;
    private int verticalRange;
    private int dmaDirection;
    private int drawMode;
    private int textureWindow;
    private int drawAreaTopLeft;
    private int drawAreaBottomRight;
    private int drawOffset;
    private int maskSettings;
    private boolean displayDisabled;
    private boolean gpuInterrupt;

    private int activeCommand = -1;
    private int pendingWords;
    private int packetWordsReceived;
    private boolean discardPacket;
    private boolean polylineActive;
    private boolean imageUploadActive;
    private int imageX;
    private int imageY;
    private int imageWidth;
    private int imageHeight;
    private int imagePixelsRemaining;
    private int imagePixelIndex;

    private int[] readbackWords = new int[0];
    private int readbackIndex;
    private int readbackLatch;
    private int readbackByteMask;
    private boolean readbackLatchValid;
    private int lastReadDataWord;

    public Ps1Gpu(Ps1VideoTiming videoTiming) { this(videoTiming, null); }

    public Ps1Gpu(Ps1VideoTiming videoTiming, Ps1InterruptController interruptController) {
        if (videoTiming == null) throw new IllegalArgumentException("video timing required");
        this.videoTiming = videoTiming;
        this.interruptController = interruptController;
        reset();
    }

    public int getDisplayMode() { return displayMode; }
    public int getDisplayStart() { return displayStart; }
    public int getHorizontalRange() { return horizontalRange; }
    public int getVerticalRange() { return verticalRange; }
    public boolean isDisplayDisabled() { return displayDisabled; }
    public int getDmaDirection() { return dmaDirection; }
    public int getHorizontalResolution() {
        if ((displayMode & 0x40) != 0) return 368;
        int[] widths = {256, 320, 512, 640};
        return widths[displayMode & 3];
    }
    public int getVerticalResolution() {
        return (displayMode & 0x24) == 0x24 ? 480 : 240;
    }
    public boolean isPal() { return (displayMode & 0x08) != 0; }
    public boolean isImageUploadActive() { return imageUploadActive; }
    public boolean hasReadbackData() { return readbackLatchValid || readbackIndex < readbackWords.length; }
    public int getVramWidth() { return VRAM_WIDTH; }
    public int getVramHeight() { return VRAM_HEIGHT; }

    /** Return one unsigned 15-bit VRAM pixel; coordinates wrap like the VRAM address. */
    public int readVramPixel(int x, int y) {
        return vram[vramIndex(x, y)] & 0xFFFF;
    }

    /** Write one pixel directly for tests and future device integration. */
    public void writeVramPixel(int x, int y, int value) {
        vram[vramIndex(x, y)] = (short) (value & 0xFFFF);
    }

    /** A defensive snapshot; VRAM is 1 MiB of 16-bit pixels. */
    public short[] copyVram() { return vram.clone(); }

    /** GPUSTAT fields currently modeled, including readiness for the synchronous GP0 path. */
    public int readStatus() {
        int status = 0;
        status |= drawMode & 0x7FF;             // GPUSTAT.0-10
        if ((maskSettings & 1) != 0) status |= 1 << 11;
        if ((maskSettings & 2) != 0) status |= 1 << 12;
        status |= (displayMode & 0x80) << 7;   // GPUSTAT.14 reverse flag
        status |= (displayMode & 0x40) << 10;  // GPUSTAT.16
        status |= (displayMode & 0x03) << 17;  // GPUSTAT.17-18
        status |= (displayMode & 0x3C) << 17;  // GPUSTAT.19-22
        if (displayDisabled) status |= 1 << 23;
        if (gpuInterrupt) status |= 1 << 24;
        if ((dmaDirection == 1 || dmaDirection == 2)
                || (dmaDirection == 3 && hasReadbackData())) status |= 1 << 25;
        status |= 1 << 26;                     // GP0 accepts command/data words in this synchronous model
        if (hasReadbackData()) status |= 1 << 27;
        if (dmaDirection == 1 || dmaDirection == 2) status |= 1 << 28;
        status |= (dmaDirection & 3) << 29;
        return status;
    }

    public void writeControlByte(int lane, int value) {
        if (lane < 0 || lane > 3) throw new IllegalArgumentException("invalid register lane");
        int shift = lane * 8;
        int mask = 0xFF << shift;
        controlWriteLatch = (controlWriteLatch & ~mask) | ((value & 0xFF) << shift);
        if (lane == 3) writeControlWord(controlWriteLatch);
    }

    public void writeDataByte(int lane, int value) {
        if (lane < 0 || lane > 3) throw new IllegalArgumentException("invalid register lane");
        int shift = lane * 8;
        int mask = 0xFF << shift;
        dataWriteLatch = (dataWriteLatch & ~mask) | ((value & 0xFF) << shift);
        if (lane == 3) writeDataWord(dataWriteLatch);
    }

    public int readDataByte(int lane) {
        if (lane < 0 || lane > 3) throw new IllegalArgumentException("invalid register lane");
        int word = peekReadDataWord();
        if (readbackLatchValid) consumeReadDataBytes(1 << lane);
        return (word >>> (lane * 8)) & 0xFF;
    }

    public int readDataHalfWord(int lane) {
        if (lane != 0 && lane != 2) throw new IllegalArgumentException("invalid half-word lane");
        int word = peekReadDataWord();
        if (readbackLatchValid) consumeReadDataBytes(lane == 0 ? 0x3 : 0xC);
        return (word >>> (lane * 8)) & 0xFFFF;
    }

    public int readDataWord() {
        int word = peekReadDataWord();
        if (readbackLatchValid) consumeReadDataBytes(0xF);
        lastReadDataWord = word;
        return word;
    }

    public void writeControlWord(int value) {
        controlWriteLatch = value;
        int command = (value >>> 24) & 0xFF;
        int parameters = value & 0x00FFFFFF;
        switch (command) {
            case 0x00: reset(); return;
            case 0x01: resetGp0CommandState(); return; // reset command FIFO/parser
            case 0x02: gpuInterrupt = false; return;
            case 0x03: displayDisabled = (parameters & 1) != 0; return;
            case 0x04: dmaDirection = parameters & 3; return;
            case 0x05:
                displayStart = parameters & 0x0007FFFF;
                return;
            case 0x06:
                horizontalRange = parameters & 0x00FFFFFF;
                applyHorizontalRange();
                return;
            case 0x07:
                verticalRange = parameters & 0x000FFFFF;
                applyVerticalRange();
                return;
            case 0x08:
                displayMode = parameters & 0xFF;
                videoTiming.changeRegionPreservingPhase(isPal() ? Ps1VideoTiming.Region.PAL : Ps1VideoTiming.Region.NTSC);
                applyHorizontalRange();
                applyVerticalRange();
                return;
            default:
                // Remaining GP1 commands are not modeled in this milestone.
        }
    }

    /** Send one GP0 word to the command parser or active image upload. */
    public void writeDataWord(int value) {
        if (imageUploadActive) {
            acceptImageUploadWord(value);
            return;
        }
        if (polylineActive) {
            if (value == POLYLINE_END) polylineActive = false;
            return;
        }
        if (pendingWords > 0) {
            packet[packetWordsReceived++] = value;
            pendingWords--;
            if (pendingWords == 0) finishPacket();
            return;
        }
        beginCommand(value);
    }

    public void reset() {
        controlWriteLatch = 0;
        dataWriteLatch = 0;
        displayMode = 0;
        displayStart = 0;
        displayDisabled = false;
        dmaDirection = 0;
        drawMode = 0;
        textureWindow = 0;
        drawAreaTopLeft = 0;
        drawAreaBottomRight = 0;
        drawOffset = 0;
        maskSettings = 0;
        gpuInterrupt = false;
        resetGp0CommandState();
        clearReadback();
        // Common initial display range: 256 pixels wide and 240 lines, centered by GP1.
        horizontalRange = 0x200 | ((0x200 + 256 * 10) << 12);
        verticalRange = 0x10 | ((0x10 + 240) << 10);
        videoTiming.setRegion(Ps1VideoTiming.Region.NTSC);
        applyHorizontalRange();
        applyVerticalRange();
    }

    /** Reset device state and clear VRAM (used by the containing machine's cold reset). */
    public void clear() {
        reset();
        Arrays.fill(vram, (short) 0);
    }

    private void beginCommand(int word) {
        int opcode = (word >>> 24) & 0xFF;
        activeCommand = opcode;
        packetWordsReceived = 1;
        packet[0] = word;
        discardPacket = false;

        if (opcode == 0x02 || opcode == 0x80 || opcode == 0xA0 || opcode == 0xC0) {
            int operands = opcode == 0x02 ? 2 : opcode == 0x80 ? 3 : 2;
            pendingWords = operands;
            return;
        }
        if (opcode >= 0x20 && opcode <= 0x3F) {
            int vertices = (opcode & 0x08) != 0 ? 4 : 3;
            boolean textured = (opcode & 0x04) != 0;
            boolean shaded = (opcode & 0x10) != 0;
            pendingWords = vertices * (textured ? 2 : 1) + (shaded ? vertices - 1 : 0);
            discardPacket = true;
            return;
        }
        if (opcode >= 0x40 && opcode <= 0x5F) {
            if ((opcode & 0x08) != 0) {
                polylineActive = true;
                activeCommand = -1;
                return;
            }
            pendingWords = (opcode & 0x10) != 0 ? 3 : 2;
            discardPacket = true;
            return;
        }
        if (opcode >= 0x60 && opcode <= 0x7F) {
            boolean textured = (opcode & 0x04) != 0;
            boolean variableSize = (opcode & 0x18) == 0;
            pendingWords = 1 + (textured ? 1 : 0) + (variableSize ? 1 : 0);
            discardPacket = true;
            return;
        }
        if (opcode >= 0xE1 && opcode <= 0xE6) {
            executeImmediateState(word);
            activeCommand = -1;
            return;
        }
        if (opcode == 0x1F) {
            gpuInterrupt = true;
            if (interruptController != null) interruptController.request(Ps1InterruptController.IRQ_GPU);
        }
        activeCommand = -1;
    }

    private void finishPacket() {
        int command = activeCommand;
        activeCommand = -1;
        switch (command) {
            case 0x02:
                fillRectangle(packet[0], packet[1], packet[2]);
                break;
            case 0x80:
                copyRectangle(packet[1], packet[2], packet[3]);
                break;
            case 0xA0:
                beginImageUpload(packet[1], packet[2]);
                break;
            case 0xC0:
                beginImageReadback(packet[1], packet[2]);
                break;
            default:
                if (!discardPacket) executeImmediateState(packet[0]);
        }
        packetWordsReceived = 0;
        discardPacket = false;
    }

    private void executeImmediateState(int word) {
        int opcode = (word >>> 24) & 0xFF;
        int parameters = word & 0x00FFFFFF;
        switch (opcode) {
            case 0xE1: drawMode = parameters & 0x7FF; break;
            case 0xE2: textureWindow = parameters & 0x000FFFFF; break;
            case 0xE3: drawAreaTopLeft = parameters & 0x0007FFFF; break;
            case 0xE4: drawAreaBottomRight = parameters & 0x0007FFFF; break;
            case 0xE5: drawOffset = parameters & 0x003FFFFF; break;
            case 0xE6: maskSettings = parameters & 3; break;
            default: break;
        }
    }

    private void fillRectangle(int command, int coordinate, int size) {
        int color = rgb24To15(command & 0x00FFFFFF);
        int x = ((coordinate & 0x3FF) & ~15);
        int y = (coordinate >>> 16) & 0x1FF;
        int width = decodeWidth(size);
        width = Math.min(VRAM_WIDTH, (width + 15) & ~15);
        int height = decodeHeight(size);
        for (int row = 0; row < height; row++) {
            int destY = (y + row) & 0x1FF;
            int rowBase = destY * VRAM_WIDTH;
            for (int column = 0; column < width; column++) {
                vram[rowBase + ((x + column) & 0x3FF)] = (short) color;
            }
        }
    }

    private void copyRectangle(int sourceCoordinate, int destinationCoordinate, int size) {
        int sourceX = sourceCoordinate & 0x3FF;
        int sourceY = (sourceCoordinate >>> 16) & 0x1FF;
        int destX = destinationCoordinate & 0x3FF;
        int destY = (destinationCoordinate >>> 16) & 0x1FF;
        int width = decodeWidth(size);
        int height = decodeHeight(size);
        short[] snapshot = new short[width * height];
        int cursor = 0;
        for (int row = 0; row < height; row++) {
            int y = (sourceY + row) & 0x1FF;
            for (int column = 0; column < width; column++) {
                snapshot[cursor++] = vram[y * VRAM_WIDTH + ((sourceX + column) & 0x3FF)];
            }
        }
        cursor = 0;
        for (int row = 0; row < height; row++) {
            int y = (destY + row) & 0x1FF;
            for (int column = 0; column < width; column++) {
                vram[y * VRAM_WIDTH + ((destX + column) & 0x3FF)] = snapshot[cursor++];
            }
        }
    }

    private void beginImageUpload(int coordinate, int size) {
        imageX = coordinate & 0x3FF;
        imageY = (coordinate >>> 16) & 0x1FF;
        imageWidth = decodeWidth(size);
        imageHeight = decodeHeight(size);
        imagePixelsRemaining = imageWidth * imageHeight;
        imagePixelIndex = 0;
        imageUploadActive = imagePixelsRemaining > 0;
    }

    private void acceptImageUploadWord(int word) {
        writeImagePixel(word & 0xFFFF);
        if (imagePixelsRemaining > 0) writeImagePixel((word >>> 16) & 0xFFFF);
        if (imagePixelsRemaining == 0) imageUploadActive = false;
    }

    private void writeImagePixel(int pixel) {
        int row = imagePixelIndex / imageWidth;
        int column = imagePixelIndex % imageWidth;
        int x = (imageX + column) & 0x3FF;
        int y = (imageY + row) & 0x1FF;
        vram[y * VRAM_WIDTH + x] = (short) pixel;
        imagePixelIndex++;
        imagePixelsRemaining--;
    }

    private void beginImageReadback(int coordinate, int size) {
        int x = coordinate & 0x3FF;
        int y = (coordinate >>> 16) & 0x1FF;
        int width = decodeWidth(size);
        int height = decodeHeight(size);
        int pixels = width * height;
        int[] result = new int[(pixels + 1) / 2];
        int pixelIndex = 0;
        for (int wordIndex = 0; wordIndex < result.length; wordIndex++) {
            int low = readImagePixel(x, y, width, pixelIndex++);
            int high = pixelIndex < pixels ? readImagePixel(x, y, width, pixelIndex++) : 0;
            result[wordIndex] = low | (high << 16);
        }
        readbackWords = result;
        readbackIndex = 0;
        readbackLatchValid = false;
        readbackByteMask = 0;
    }

    private int readImagePixel(int x, int y, int width, int pixelIndex) {
        int row = pixelIndex / width;
        int column = pixelIndex % width;
        return vram[((y + row) & 0x1FF) * VRAM_WIDTH + ((x + column) & 0x3FF)] & 0xFFFF;
    }

    private void clearReadback() {
        readbackWords = new int[0];
        readbackIndex = 0;
        readbackLatch = 0;
        readbackByteMask = 0;
        readbackLatchValid = false;
        lastReadDataWord = 0;
    }

    private int peekReadDataWord() {
        if (!readbackLatchValid && readbackIndex < readbackWords.length) {
            readbackLatch = readbackWords[readbackIndex];
            readbackByteMask = 0;
            readbackLatchValid = true;
        }
        return readbackLatchValid ? readbackLatch : lastReadDataWord;
    }

    private void consumeReadDataBytes(int mask) {
        readbackByteMask |= mask & 0xF;
        if (readbackByteMask == 0xF) {
            lastReadDataWord = readbackLatch;
            readbackIndex++;
            readbackLatchValid = false;
            readbackByteMask = 0;
        }
    }

    private void resetGp0CommandState() {
        activeCommand = -1;
        pendingWords = 0;
        packetWordsReceived = 0;
        discardPacket = false;
        polylineActive = false;
        imageUploadActive = false;
        imageX = imageY = imageWidth = imageHeight = 0;
        imagePixelsRemaining = imagePixelIndex = 0;
        Arrays.fill(packet, 0);
    }

    private int decodeWidth(int size) {
        int width = size & 0x3FF;
        return width == 0 ? VRAM_WIDTH : width;
    }

    private int decodeHeight(int size) {
        int height = (size >>> 16) & 0x1FF;
        return height == 0 ? VRAM_HEIGHT : height;
    }

    private int vramIndex(int x, int y) {
        return ((y & 0x1FF) * VRAM_WIDTH) + (x & 0x3FF);
    }

    private static int rgb24To15(int color) {
        int red = color & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = (color >>> 16) & 0xFF;
        return ((red >>> 3) & 0x1F) | (((green >>> 3) & 0x1F) << 5)
                | (((blue >>> 3) & 0x1F) << 10);
    }

    private void applyHorizontalRange() {
        int start = horizontalRange & 0xFFF;
        int end = (horizontalRange >>> 12) & 0xFFF;
        videoTiming.setHorizontalDisplayRange(start, end);
    }

    private void applyVerticalRange() {
        int start = verticalRange & 0x3FF;
        int end = (verticalRange >>> 10) & 0x3FF;
        videoTiming.setVerticalDisplayRange(start, end);
    }
}
