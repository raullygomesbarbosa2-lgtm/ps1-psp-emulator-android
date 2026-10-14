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
