package com.raully.psxpsp.ps1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Ps1SpuTest {
    @Test
    public void spuRegistersAndRamTransferFifoAreMapped() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        int base = Ps1Spu.BASE_ADDRESS;
        bus.write16(base + 0x180, 0x1234);
        assertEquals(0x1234, bus.read16(base + 0x180));

        bus.write16(base + 0x1A6, 2); // transfer base is in 8-byte units
        bus.write16(base + 0x1A8, 0xABCD);
        bus.write16(base + 0x1A8, 0x1234);
        assertEquals(2, bus.getSpu().getTransferAddress()); // the programmed base register does not increment
        bus.write16(base + 0x1A6, 2);
        assertEquals(0xABCD, bus.read16(base + 0x1A8));
        assertEquals(0x1234, bus.read16(base + 0x1A8));

        bus.write16(base + 0x1A6, 4);
        bus.write32(base + 0x1A8, 0x56789ABC);
        bus.write16(base + 0x1A6, 4);
        assertEquals(0x9ABC, bus.read16(base + 0x1A8));
        assertEquals(0x5678, bus.read16(base + 0x1A8));

        bus.write16(base + 0x1A6, 3);
        bus.write8(base + 0x1A8, 0x78);
        bus.write8(base + 0x1A9, 0x56);
        bus.write16(base + 0x1A6, 3);
        assertEquals(0x5678, bus.read16(base + 0x1A8));
    }

    @Test
    public void keyOnDecodesAdpcmBlockToStereoPcmAndKeyOffStopsVoice() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        int base = Ps1Spu.BASE_ADDRESS;
        bus.write16(base + 0x1A6, 0);
        bus.write16(base + 0x1A8, 0x000C); // shift 12, filter 0
        for (int i = 0; i < 7; i++) bus.write16(base + 0x1A8, 0x7777);

        bus.write16(base + 0x000, 0x3FFF); // voice 0 left volume
        bus.write16(base + 0x002, 0x3FFF); // voice 0 right volume
        bus.write16(base + 0x004, 0x1000); // nominal pitch
        bus.write16(base + 0x006, 0); // start at SPU RAM address zero
        bus.write16(base + 0x180, 0x3FFF); // main left volume
        bus.write16(base + 0x182, 0x3FFF); // main right volume
        bus.write16(base + 0x1AA, 0x8000); // enable SPU
        assertEquals(0x8000, bus.read16(base + 0x1AA));
        bus.write16(base + 0x188, 1); // key on voice 0

        assertTrue(bus.getSpu().isVoiceActive(0));
        short[] pcm = bus.getSpu().renderPcm(4);
        assertEquals(8, pcm.length);
        assertTrue(pcm[0] > 0);
        assertTrue(pcm[1] > 0);

        bus.write16(base + 0x18C, 1); // key off voice 0
        assertFalse(bus.getSpu().isVoiceActive(0));
        short[] silent = bus.getSpu().renderPcm(1);
        assertEquals(0, silent[0]);
        assertEquals(0, silent[1]);
    }

    @Test
    public void voiceCanBeTriggeredAgainAfterEndOfBlock() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        int base = Ps1Spu.BASE_ADDRESS;
        bus.write16(base + 0x1A6, 0);
        bus.write16(base + 0x1A8, 0x010C); // shift 12, filter 0, end-of-sample flag
        for (int i = 0; i < 7; i++) bus.write16(base + 0x1A8, 0x7777);

        bus.write16(base + 0x000, 0x3FFF);
        bus.write16(base + 0x002, 0x3FFF);
        bus.write16(base + 0x004, 0x1000);
        bus.write16(base + 0x006, 0);
        bus.write16(base + 0x180, 0x3FFF);
        bus.write16(base + 0x182, 0x3FFF);
        bus.write16(base + 0x1AA, 0x8000);
        bus.write16(base + 0x188, 1);
        assertTrue(bus.getSpu().isVoiceActive(0));
        bus.getSpu().renderPcm(29); // consume the block and reach its end marker
        assertFalse(bus.getSpu().isVoiceActive(0));

        bus.write16(base + 0x188, 1);
        assertTrue(bus.getSpu().isVoiceActive(0));
        short[] pcm = bus.getSpu().renderPcm(1);
        assertTrue(pcm[0] > 0);
    }

    @Test
    public void disabledSpuProducesSilenceAndResetClearsVoiceState() {
        Ps1MemoryBus bus = new Ps1MemoryBus();
        Ps1Spu spu = bus.getSpu();
        bus.write16(Ps1Spu.BASE_ADDRESS + 0x188, 1);
        assertFalse(spu.isVoiceActive(0)); // key-on is ignored while SPUCNT is disabled
        short[] disabled = spu.renderPcm(2);
        assertEquals(0, disabled[0]);
        assertEquals(0, disabled[1]);
        bus.write16(Ps1Spu.BASE_ADDRESS + 0x1AA, 0x8000);
        bus.write16(Ps1Spu.BASE_ADDRESS + 0x188, 1);
        assertTrue(spu.isVoiceActive(0));
        bus.clear();
        assertFalse(spu.isVoiceActive(0));
    }
}
