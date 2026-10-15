package com.raully.psxpsp.ps1;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class MipsR3000ATest {
    private static final int START = 0x1000;

    @Test
    public void executesIntegerInstructionsAndKeepsZeroRegisterHardwired() {
        TestBus bus = new TestBus();
        bus.put32(START, i(0x09, 0, 1, 5));       // ADDIU r1,r0,5
        bus.put32(START + 4, i(0x09, 1, 2, -2));  // ADDIU r2,r1,-2
        bus.put32(START + 8, r(1, 2, 3, 0, 0x21)); // ADDU r3,r1,r2
        bus.put32(START + 12, i(0x09, 0, 0, 99)); // ADDIU r0,r0,99
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);

        cpu.step(); cpu.step(); cpu.step(); cpu.step();

        assertEquals(5, cpu.getRegister(1));
        assertEquals(3, cpu.getRegister(2));
        assertEquals(8, cpu.getRegister(3));
        assertEquals(0, cpu.getRegister(0));
    }

    @Test
    public void branchExecutesOneDelaySlotBeforeTakingTarget() {
        TestBus bus = new TestBus();
        bus.put32(START, i(0x09, 0, 1, 1));      // ADDIU r1,r0,1
        bus.put32(START + 4, i(0x04, 1, 1, 1));  // BEQ r1,r1,+1 -> START+12
        bus.put32(START + 8, i(0x09, 0, 2, 7));  // delay slot
        bus.put32(START + 12, i(0x09, 0, 3, 9)); // branch target
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);

        cpu.step(); cpu.step();
        assertEquals(START + 8, cpu.getPc());
        cpu.step();
        assertEquals(7, cpu.getRegister(2));
        assertEquals(START + 12, cpu.getPc());
        cpu.step();
        assertEquals(9, cpu.getRegister(3));
    }

    @Test
    public void loadResultIsDelayedByOneInstruction() {
        TestBus bus = new TestBus();
        bus.put32(0x2000, 0x12345678);
        bus.put32(START, i(0x23, 4, 1, 0));       // LW r1,0(r4)
        bus.put32(START + 4, r(1, 0, 2, 0, 0x21)); // ADDU r2,r1,r0 sees old r1
        bus.put32(START + 8, r(1, 0, 3, 0, 0x21)); // next instruction sees loaded r1
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(4, 0x2000);

        cpu.step();
        cpu.step();
        assertEquals(0, cpu.getRegister(2));
        assertEquals(0x12345678, cpu.getRegister(1));
        cpu.step();
        assertEquals(0x12345678, cpu.getRegister(3));
    }

    @Test
    public void lwlAndLwrMergeCorrectLittleEndianByteLanes() {
        int[] expectedLwl = {0x11BBCCDD, 0x1122CCDD, 0x112233DD, 0x11223344};
        int[] expectedLwr = {0x44332211, 0xAA443322, 0xAABB4433, 0xAABBCC44};
        for (int offset = 0; offset < 4; offset++) {
            assertPartialLoadResult(0x22, offset, 0xAABBCCDD, expectedLwl[offset]); // LWL
            assertPartialLoadResult(0x26, offset, 0xAABBCCDD, expectedLwr[offset]); // LWR
        }
    }

    @Test
    public void consecutiveLwlLwrToSameRegisterMergeTheOlderPartialLoad() {
        TestBus bus = new TestBus();
        bus.put32(0x2000, 0x44332211);
        bus.write8(0x2004, 0x55);
        bus.put32(START, i(0x22, 1, 2, 4)); // LWL r2,4(r1)
        bus.put32(START + 4, i(0x26, 1, 2, 1)); // LWR r2,1(r1)
        bus.put32(START + 8, 0); // NOP
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 0x2000);
        cpu.setRegister(2, 0xAABBCCDD);

        cpu.step(); cpu.step(); cpu.step();

        assertEquals(0x55443322, cpu.getRegister(2));
    }

    @Test
    public void swlAndSwrWriteOnlyTheirLittleEndianByteLanes() {
        int[][] expectedSwl = {
                {0xA1, 0x20, 0x30, 0x40},
                {0xB2, 0xA1, 0x30, 0x40},
                {0xC3, 0xB2, 0xA1, 0x40},
                {0xD4, 0xC3, 0xB2, 0xA1}
        };
        int[][] expectedSwr = {
                {0xD4, 0xC3, 0xB2, 0xA1},
                {0x10, 0xD4, 0xC3, 0xB2},
                {0x10, 0x20, 0xD4, 0xC3},
                {0x10, 0x20, 0x30, 0xD4}
        };
        for (int offset = 0; offset < 4; offset++) {
            assertPartialStoreResult(0x2A, offset, expectedSwl[offset]); // SWL
            assertPartialStoreResult(0x2E, offset, expectedSwr[offset]); // SWR
        }
    }

    @Test
    public void newerRegisterWriteWinsOverAnOlderDelayedLoad() {
        TestBus bus = new TestBus();
        bus.put32(0x2000, 0x12345678);
        bus.put32(START, i(0x23, 4, 1, 0));      // LW r1,0(r4)
        bus.put32(START + 4, i(0x09, 0, 1, 9));  // ADDIU r1,r0,9
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(4, 0x2000);

        cpu.step(); cpu.step();

        assertEquals(9, cpu.getRegister(1));
    }

    @Test
    public void cop2TransfersRequireCu2AndKeepMfcResultsDelayed() {
        TestBus bus = new TestBus();
        bus.put32(START, cop0Transfer(4, 1, 12)); // MTC0 r1, Status (enable CU2)
        bus.put32(START + 4, cop2Transfer(4, 2, 14)); // MTC2 r2, SXY2
        bus.put32(START + 8, cop2Transfer(0, 3, 14)); // MFC2 r3, SXY2
        bus.put32(START + 12, cop2Transfer(6, 2, 4)); // CTC2 r2, control 4
        bus.put32(START + 16, cop2Transfer(2, 4, 4)); // CFC2 r4, control 4
        bus.put32(START + 20, 0); // NOP, exposes delayed CFC2 result
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 1 << 30);
        cpu.setRegister(2, 0x000A0001);

        for (int i = 0; i < 6; i++) cpu.step();

        assertEquals(0x000A0001, cpu.getRegister(3));
        assertEquals(0x000A0001, cpu.getRegister(4));

        TestBus disabledBus = new TestBus();
        disabledBus.put32(START, cop2Transfer(0, 2, 0));
        MipsR3000A disabledCpu = new MipsR3000A(disabledBus);
        disabledCpu.reset(START);
        disabledCpu.step();
        assertEquals(11, (disabledCpu.getCp0Register(13) >>> 2) & 0x1F);
    }

    @Test
    public void nclipCommandRunsThroughCop2AndReturnsMac0() {
        TestBus bus = new TestBus();
        bus.put32(START, cop0Transfer(4, 1, 12)); // Enable CU2.
        bus.put32(START + 4, cop2Transfer(4, 2, 12)); // MTC2 r2, SXY0
        bus.put32(START + 8, cop2Transfer(4, 3, 13)); // MTC2 r3, SXY1
        bus.put32(START + 12, cop2Transfer(4, 4, 14)); // MTC2 r4, SXY2
        bus.put32(START + 16, 0x4A000006); // NCLIP
        bus.put32(START + 20, cop2Transfer(0, 5, 24)); // MFC2 r5, MAC0
        bus.put32(START + 24, 0); // load-delay slot
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 1 << 30);
        cpu.setRegister(2, 0x00000000); // (0,0)
        cpu.setRegister(3, 0x0000000A); // (10,0)
        cpu.setRegister(4, 0x000A0000); // (0,10)

        for (int i = 0; i < 7; i++) cpu.step();

        assertEquals(100, cpu.getRegister(5));
    }

    @Test
    public void addOverflowRaisesExceptionAndRecordsEpc() {
        TestBus bus = new TestBus();
        bus.put32(START, i(0x08, 1, 2, 1)); // ADDI r2,r1,1
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, Integer.MAX_VALUE);

        cpu.step();

        assertEquals(MipsR3000A.GENERAL_EXCEPTION_VECTOR, cpu.getPc());
        assertEquals(START, cpu.getCp0Register(14));
        assertEquals(12, (cpu.getCp0Register(13) >>> 2) & 0x1F);
    }

    @Test
    public void misalignedLoadRaisesAddressErrorAndSetsBadVaddr() {
        TestBus bus = new TestBus();
        bus.put32(START, i(0x23, 1, 2, 0)); // LW r2,0(r1)
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 0x2001);

        cpu.step();

        assertEquals(0x2001, cpu.getCp0Register(8));
        assertEquals(4, (cpu.getCp0Register(13) >>> 2) & 0x1F);
        assertEquals(START, cpu.getCp0Register(14));
    }

    private static void assertPartialLoadResult(int opcode, int offset, int initialRegister, int expected) {
        TestBus bus = new TestBus();
        bus.put32(0x2000, 0x44332211);
        bus.put32(START, i(opcode, 1, 2, offset));
        bus.put32(START + 4, 0); // load-delay slot
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 0x2000);
        cpu.setRegister(2, initialRegister);

        cpu.step(); cpu.step();

        assertEquals("opcode 0x" + Integer.toHexString(opcode) + ", offset " + offset,
                expected, cpu.getRegister(2));
    }

    private static void assertPartialStoreResult(int opcode, int offset, int[] expectedBytes) {
        TestBus bus = new TestBus();
        bus.put32(0x2000, 0x40302010);
        bus.put32(START, i(opcode, 1, 2, offset));
        MipsR3000A cpu = new MipsR3000A(bus);
        cpu.reset(START);
        cpu.setRegister(1, 0x2000);
        cpu.setRegister(2, 0xA1B2C3D4);

        cpu.step();

        for (int i = 0; i < expectedBytes.length; i++) {
            assertEquals("opcode 0x" + Integer.toHexString(opcode) + ", offset " + offset + ", byte " + i,
                    expectedBytes[i], bus.read8(0x2000 + i));
        }
    }

    private static int cop0Transfer(int rs, int rt, int rd) {
        return (0x10 << 26) | (rs << 21) | (rt << 16) | (rd << 11);
    }

    private static int cop2Transfer(int rs, int rt, int rd) {
        return (0x12 << 26) | (rs << 21) | (rt << 16) | (rd << 11);
    }

    private static int i(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }

    private static int r(int rs, int rt, int rd, int shamt, int function) {
        return (rs << 21) | (rt << 16) | (rd << 11) | (shamt << 6) | function;
    }

    /** Tiny little-endian RAM used only by the CPU unit tests. */
    private static final class TestBus implements Ps1Bus {
        private final byte[] memory = new byte[0x10000];
        private int index(int address) { return address & (memory.length - 1); }
        @Override public int read8(int address) { return memory[index(address)] & 0xFF; }
        @Override public int read16(int address) { return read8(address) | (read8(address + 1) << 8); }
        @Override public int read32(int address) { return read16(address) | (read16(address + 2) << 16); }
        @Override public void write8(int address, int value) { memory[index(address)] = (byte) value; }
        @Override public void write16(int address, int value) { write8(address, value); write8(address + 1, value >>> 8); }
        @Override public void write32(int address, int value) { write16(address, value); write16(address + 2, value >>> 16); }
        void put32(int address, int value) { write32(address, value); }
    }
}
