package com.raully.psxpsp.ps1;

import java.util.Arrays;

/**
 * Initial software model of the PS1 SPU. It maps the voice/global registers,
 * 512 KiB sample RAM, ADPCM block decoding, key-on/off and a deterministic PCM
 * renderer. ADSR, interpolation, reverb, noise, CD audio and exact SPU timing
 * are not modeled yet.
 */
public final class Ps1Spu {
    public static final int BASE_ADDRESS = 0x1F801C00;
    public static final int REGISTER_BYTES = 0x200;
    public static final int VOICE_COUNT = 24;
    public static final int RAM_BYTES = 512 * 1024;
    public static final int SAMPLE_RATE = 44100;
    private static final int RAM_MASK = RAM_BYTES - 1;
    private static final int ADPCM_BLOCK_BYTES = 16;
    private static final int SAMPLES_PER_BLOCK = 28;
    private static final int SPU_MAIN_VOLUME_LEFT = 0x180;
    private static final int SPU_MAIN_VOLUME_RIGHT = 0x182;
    private static final int SPU_KEY_ON_LOW = 0x188;
    private static final int SPU_KEY_ON_HIGH = 0x18A;
    private static final int SPU_KEY_OFF_LOW = 0x18C;
    private static final int SPU_KEY_OFF_HIGH = 0x18E;
    private static final int SPU_TRANSFER_ADDRESS = 0x1A6;
    private static final int SPU_TRANSFER_FIFO = 0x1A8;
    private static final int SPU_CONTROL = 0x1AA;
    private static final int SPU_TRANSFER_CONTROL = 0x1AC;
    private static final int SPU_STATUS = 0x1AE;

    private static final int[] FILTER_1 = {0, 60, 115, 98, 122};
    private static final int[] FILTER_2 = {0, 0, -52, -55, -60};

    private final byte[] ram = new byte[RAM_BYTES];
    private final Voice[] voices = new Voice[VOICE_COUNT];
    private final int[] registers = new int[REGISTER_BYTES / 2];
    private int transferAddress;
    private int transferCurrentAddress;
    private int status;
    private int fifoReadLatch;
    private int fifoReadMask;
    private boolean fifoReadLatched;
    private int fifoWriteLatch;
    private int fifoWriteMask;

    public Ps1Spu() {
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
        reset();
    }

    public boolean isMappedAddress(int physicalAddress) {
        return physicalAddress >= BASE_ADDRESS && physicalAddress < BASE_ADDRESS + REGISTER_BYTES;
    }

    public void reset() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(registers, 0);
        for (Voice voice : voices) voice.reset();
        transferAddress = transferCurrentAddress = 0;
        status = 0;
        fifoReadLatch = fifoReadMask = fifoWriteLatch = fifoWriteMask = 0;
        fifoReadLatched = false;
        // Leave SPU disabled until the program enables it via SPUCNT.
    }

    public int readRegister(int offset) {
        checkOffset(offset);
        int aligned = offset & ~1;
        if (aligned < 0x180) {
            int voiceIndex = aligned >>> 4;
            int register = aligned & 0x0F;
            Voice voice = voices[voiceIndex];
            switch (register) {
                case 0x00: return voice.volumeLeft;
                case 0x02: return voice.volumeRight;
                case 0x04: return voice.pitch;
                case 0x06: return voice.startAddress;
                case 0x08: return voice.adsrLow;
                case 0x0A: return voice.adsrHigh;
                case 0x0C: return voice.currentVolume;
                case 0x0E: return voice.repeatAddress;
                default: return 0;
            }
        }
        if (aligned == SPU_TRANSFER_ADDRESS) return transferAddress;
        if (aligned == SPU_TRANSFER_FIFO) {
            if (fifoReadLatched) {
                int result = fifoReadLatch;
                fifoReadLatched = false;
                fifoReadMask = 0;
                advanceTransferAddress(2);
                return result;
            }
            return readRamHalfWord();
        }
        if (aligned == SPU_STATUS) return status | (registers[SPU_CONTROL >>> 1] & 0x3F);
        return registers[aligned >>> 1] & 0xFFFF;
    }

    public void writeRegister(int offset, int value) {
        writeRegister(offset, value, 0xFFFF);
    }

    public void writeRegister(int offset, int value, int writeMask) {
        checkOffset(offset);
        int aligned = offset & ~1;
        if (aligned == SPU_TRANSFER_FIFO && writeMask == 0xFFFF) {
            fifoWriteMask = 0;
            writeRamHalfWord(value);
            return;
        }
        int index = aligned >>> 1;
        int old = readRegister(aligned);
        int merged = ((old & ~writeMask) | (value & writeMask)) & 0xFFFF;
        if (aligned < 0x180) {
            int voiceIndex = aligned >>> 4;
            int register = aligned & 0x0F;
            Voice voice = voices[voiceIndex];
            switch (register) {
                case 0x00: voice.volumeLeft = merged; break;
                case 0x02: voice.volumeRight = merged; break;
                case 0x04: voice.pitch = merged & 0x3FFF; break;
                case 0x06: voice.startAddress = merged; break;
                case 0x08: voice.adsrLow = merged; break;
                case 0x0A: voice.adsrHigh = merged; break;
                case 0x0C: /* envelope/current volume is read-only in this model */ break;
                case 0x0E: voice.repeatAddress = merged; break;
                default: break;
            }
            return;
        }
        if (aligned == SPU_TRANSFER_ADDRESS) {
            transferAddress = merged & 0xFFFF;
            transferCurrentAddress = (transferAddress << 3) & RAM_MASK;
            registers[index] = transferAddress;
            return;
        }
        if (aligned == SPU_STATUS) return;
        if (aligned == SPU_TRANSFER_FIFO) {
            writeRamHalfWord(merged);
            return;
        }
        if (aligned == SPU_CONTROL) {
            registers[index] = merged;
            if ((merged & 0x8000) == 0) {
                for (Voice voice : voices) {
                    voice.active = false;
                    voice.sampleIndex = SAMPLES_PER_BLOCK;
                }
            }
            return;
        }
        if (aligned == SPU_KEY_ON_LOW || aligned == SPU_KEY_ON_HIGH) {
            registers[index] = merged;
            if ((registers[SPU_CONTROL >>> 1] & 0x8000) == 0) return;
            int firstVoice = aligned == SPU_KEY_ON_LOW ? 0 : 16;
            applyKeyMask(firstVoice, merged, true);
            return;
        }
        if (aligned == SPU_KEY_OFF_LOW || aligned == SPU_KEY_OFF_HIGH) {
            registers[index] = merged;
            int firstVoice = aligned == SPU_KEY_OFF_LOW ? 0 : 16;
            applyKeyMask(firstVoice, merged, false);
            return;
        }
        registers[index] = merged;
    }

    public int readRegisterByte(int offset) {
        checkOffset(offset);
        int aligned = offset & ~1;
        int lane = offset & 1;
        if (aligned != SPU_TRANSFER_FIFO) return (readRegister(aligned) >>> (lane * 8)) & 0xFF;
        if (!fifoReadLatched) {
            fifoReadLatch = readRamHalfWordAtTransferAddress();
            fifoReadMask = 0;
            fifoReadLatched = true;
        }
        int result = (fifoReadLatch >>> (lane * 8)) & 0xFF;
        fifoReadMask |= 1 << lane;
        if (fifoReadMask == 3) {
            advanceTransferAddress(2);
            fifoReadLatched = false;
            fifoReadMask = 0;
        }
        return result;
    }

    public void writeRegisterByte(int offset, int value) {
        checkOffset(offset);
        int aligned = offset & ~1;
        int lane = offset & 1;
        if (aligned == SPU_TRANSFER_FIFO) {
            int mask = 1 << lane;
            fifoWriteLatch = (fifoWriteLatch & ~(0xFF << (lane * 8))) | ((value & 0xFF) << (lane * 8));
            fifoWriteMask |= mask;
            if (fifoWriteMask == 3) {
                writeRamHalfWord(fifoWriteLatch);
                fifoWriteMask = 0;
            }
            return;
        }
        int shift = lane << 3;
        int mask = 0xFF << shift;
        writeRegister(aligned, (value & 0xFF) << shift, mask);
    }

    /** Render signed 16-bit stereo PCM, interleaved L/R at 44.1 kHz. */
    public short[] renderPcm(int frameCount) {
        if (frameCount < 0 || frameCount > SAMPLE_RATE * 10) {
            throw new IllegalArgumentException("invalid audio frame count");
        }
        short[] output = new short[frameCount * 2];
        if ((registers[SPU_CONTROL >>> 1] & 0x8000) == 0) return output;
        int mainLeft = signedVolume(registers[SPU_MAIN_VOLUME_LEFT >>> 1]);
        int mainRight = signedVolume(registers[SPU_MAIN_VOLUME_RIGHT >>> 1]);
        for (int frame = 0; frame < frameCount; frame++) {
            long left = 0;
            long right = 0;
            for (Voice voice : voices) {
                if (!voice.active) continue;
                if (voice.sampleIndex >= SAMPLES_PER_BLOCK && !loadNextBlock(voice)) continue;
                int sample = voice.samples[voice.sampleIndex];
                left += ((long) sample * signedVolume(voice.volumeLeft)) >> 14;
                right += ((long) sample * signedVolume(voice.volumeRight)) >> 14;
                advanceVoice(voice);
            }
            left = (left * mainLeft) >> 14;
            right = (right * mainRight) >> 14;
            output[frame * 2] = (short) clamp16(left);
            output[frame * 2 + 1] = (short) clamp16(right);
        }
        return output;
    }

    public boolean isVoiceActive(int voiceIndex) {
        checkVoice(voiceIndex);
        return voices[voiceIndex].active;
    }

    public int getTransferAddress() { return transferAddress; }

    private void applyKeyMask(int firstVoice, int mask, boolean keyOn) {
        for (int bit = 0; bit < 16 && firstVoice + bit < VOICE_COUNT; bit++) {
            if ((mask & (1 << bit)) == 0) continue;
            Voice voice = voices[firstVoice + bit];
            if (keyOn) {
                voice.active = true;
                voice.currentAddress = (voice.startAddress << 3) & RAM_MASK;
                voice.repeatAddress = voice.startAddress;
                voice.phase = 0;
                voice.sampleIndex = SAMPLES_PER_BLOCK;
                voice.history1 = 0;
                voice.history2 = 0;
                voice.blockLoaded = false;
                voice.currentVolume = 0x3FFF;
                loadNextBlock(voice);
            } else {
                voice.active = false;
                voice.sampleIndex = SAMPLES_PER_BLOCK;
            }
        }
    }

    private void advanceVoice(Voice voice) {
        voice.phase += voice.pitch;
        int samplesToAdvance = voice.phase >>> 12;
        voice.phase &= 0x0FFF;
        while (samplesToAdvance-- > 0 && voice.active) {
            voice.sampleIndex++;
            if (voice.sampleIndex >= SAMPLES_PER_BLOCK) loadNextBlock(voice);
        }
    }

    private boolean loadNextBlock(Voice voice) {
        if (!voice.active) return false;
        if (voice.sampleIndex >= SAMPLES_PER_BLOCK && voice.blockLoaded) {
            int flags = voice.blockFlags;
            if ((flags & 0x02) != 0) voice.repeatAddress = (voice.currentAddress >>> 3) & 0xFFFF;
            if ((flags & 0x01) != 0) {
                if ((flags & 0x04) != 0) voice.currentAddress = (voice.repeatAddress << 3) & RAM_MASK;
                else {
                    voice.active = false;
                    return false;
                }
            } else {
                voice.currentAddress = (voice.currentAddress + ADPCM_BLOCK_BYTES) & RAM_MASK;
            }
        }
        int address = voice.currentAddress & RAM_MASK;
        int header = ram[address] & 0xFF;
        voice.blockFlags = ram[(address + 1) & RAM_MASK] & 0xFF;
        int shift = Math.min(12, header & 0x0F);
        int filter = (header >>> 4) & 0x0F;
        int coefficient1 = filter < FILTER_1.length ? FILTER_1[filter] : 0;
        int coefficient2 = filter < FILTER_2.length ? FILTER_2[filter] : 0;
        for (int sampleIndex = 0; sampleIndex < SAMPLES_PER_BLOCK; sampleIndex++) {
            int packed = ram[(address + 2 + (sampleIndex >>> 1)) & RAM_MASK] & 0xFF;
            int nibble = (sampleIndex & 1) == 0 ? packed & 0x0F : (packed >>> 4) & 0x0F;
            if ((nibble & 8) != 0) nibble -= 16;
            int decoded = (nibble << 12) >> shift;
            decoded += (voice.history1 * coefficient1 + voice.history2 * coefficient2 + 32) >> 6;
            decoded = clamp16(decoded);
            voice.samples[sampleIndex] = (short) decoded;
            voice.history2 = voice.history1;
            voice.history1 = decoded;
        }
        voice.sampleIndex = 0;
        voice.blockLoaded = true;
        return true;
    }

    private int readRamHalfWord() {
        int result = readRamHalfWordAtTransferAddress();
        advanceTransferAddress(2);
        return result;
    }

    private int readRamHalfWordAtTransferAddress() {
        return (ram[transferCurrentAddress] & 0xFF)
                | ((ram[(transferCurrentAddress + 1) & RAM_MASK] & 0xFF) << 8);
    }

    private void writeRamHalfWord(int value) {
        ram[transferCurrentAddress] = (byte) value;
        ram[(transferCurrentAddress + 1) & RAM_MASK] = (byte) (value >>> 8);
        advanceTransferAddress(2);
    }

    private void advanceTransferAddress(int byteCount) {
        transferCurrentAddress = (transferCurrentAddress + byteCount) & RAM_MASK;
    }

    private static int signedVolume(int value) {
        int volume = value & 0x7FFF;
        return (volume & 0x4000) != 0 ? volume - 0x8000 : volume;
    }

    private static int clamp16(long value) {
        return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static void checkOffset(int offset) {
        if (offset < 0 || offset >= REGISTER_BYTES) throw new IllegalArgumentException("invalid SPU register offset");
    }

    private static void checkVoice(int index) {
        if (index < 0 || index >= VOICE_COUNT) throw new IllegalArgumentException("invalid SPU voice");
    }

    private static final class Voice {
        int volumeLeft;
        int volumeRight;
        int pitch = 0x1000;
        int startAddress;
        int adsrLow;
        int adsrHigh;
        int currentVolume;
        int repeatAddress;
        int currentAddress;
        int phase;
        int sampleIndex = SAMPLES_PER_BLOCK;
        int history1;
        int history2;
        int blockFlags;
        boolean blockLoaded;
        boolean active;
        final short[] samples = new short[SAMPLES_PER_BLOCK];

        void reset() {
            volumeLeft = volumeRight = 0;
            pitch = 0x1000;
            startAddress = adsrLow = adsrHigh = currentVolume = repeatAddress = 0;
            currentAddress = phase = history1 = history2 = blockFlags = 0;
            sampleIndex = SAMPLES_PER_BLOCK;
            blockLoaded = false;
            active = false;
            Arrays.fill(samples, (short) 0);
        }
    }
}
