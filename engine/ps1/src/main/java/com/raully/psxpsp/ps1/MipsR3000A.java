package com.raully.psxpsp.ps1;

/**
 * Primeira etapa do interpretador inteiro MIPS R3000A do PS1.
 * Implementa um subconjunto base da ISA, atraso de desvio, atraso de carga,
 * multiplicação/divisão inteiras e exceções básicas. Não implementa ainda
 * cache, GTE/COP2, temporização do console nem todas as instruções MIPS.
 */
public final class MipsR3000A {
    public static final int RESET_VECTOR = 0xBFC00000;
    public static final int GENERAL_EXCEPTION_VECTOR = 0x80000080;
    public static final int BOOT_EXCEPTION_VECTOR = 0xBFC00180;

    private static final int EXC_INTERRUPT = 0;
    private static final int EXC_ADDRESS_LOAD = 4;
    private static final int EXC_ADDRESS_STORE = 5;
    private static final int EXC_BUS_ERROR_LOAD = 6;
    private static final int EXC_BUS_ERROR_STORE = 7;
    private static final int EXC_SYSCALL = 8;
    private static final int EXC_BREAK = 9;
    private static final int EXC_RESERVED_INSTRUCTION = 10;
    private static final int EXC_COPROCESSOR_UNUSABLE = 11;
    private static final int EXC_OVERFLOW = 12;
    private static final int CP0_BAD_VADDR = 8;
    private static final int CP0_STATUS = 12;
    private static final int CP0_CAUSE = 13;
    private static final int CP0_EPC = 14;
    private static final int STATUS_BEV = 1 << 22;

    private final Ps1Bus bus;
    private final int[] gpr = new int[32];
    private final int[] cp0 = new int[16];

    private int pc;
    private int hi;
    private int lo;
    private boolean branchPending;
    private int branchTarget;
    private int branchInstructionPc;
    private int pendingLoadRegister = -1;
    private int pendingLoadValue;

    // Per-step bookkeeping for MIPS load-delay behavior.
    private int currentInstructionPc;
    private int currentBranchPc;
    private boolean currentInstructionInDelaySlot;
    private int registerWrittenThisStep;
    private int scheduledLoadRegister;
    private int scheduledLoadValue;

    public MipsR3000A(Ps1Bus bus) {
        if (bus == null) throw new IllegalArgumentException("bus must not be null");
        this.bus = bus;
        reset();
    }

    /** Reset CPU state to the PS1 reset vector. Memory/device reset is external. */
    public void reset() {
        reset(RESET_VECTOR);
    }

    /** Reset CPU state to a caller-selected entry point (e.g. an HLE boot stub). */
    public void reset(int entryPoint) {
        for (int i = 0; i < gpr.length; i++) gpr[i] = 0;
        for (int i = 0; i < cp0.length; i++) cp0[i] = 0;
        pc = entryPoint;
        hi = 0;
        lo = 0;
        branchPending = false;
        branchTarget = 0;
        branchInstructionPc = 0;
        pendingLoadRegister = -1;
        pendingLoadValue = 0;
        gpr[0] = 0;
    }

    public int getPc() { return pc; }
    public int getHi() { return hi; }
    public int getLo() { return lo; }

    public int getRegister(int index) {
        checkRegisterIndex(index);
        return index == 0 ? 0 : gpr[index];
    }

    /** Used by the platform boot code and deterministic CPU tests. */
    public void setRegister(int index, int value) {
        checkRegisterIndex(index);
        if (index != 0) gpr[index] = value;
        gpr[0] = 0;
    }

    public int getCp0Register(int index) {
        if (index < 0 || index >= cp0.length) throw new IllegalArgumentException("invalid CP0 register");
        return cp0[index];
    }

    /** Execute one instruction, including pending IRQs and any older delayed load. */
    public void step() {
        refreshHardwareInterrupt();
        if (!branchPending && hardwareInterruptEnabled()) {
            if (pendingLoadRegister > 0) gpr[pendingLoadRegister] = pendingLoadValue;
            pendingLoadRegister = -1;
            currentInstructionPc = pc;
            currentInstructionInDelaySlot = false;
            currentBranchPc = 0;
            registerWrittenThisStep = -1;
            scheduledLoadRegister = -1;
            branchPending = false;
            enterException(EXC_INTERRUPT, -1);
            gpr[0] = 0;
            return;
        }
        final int delayedLoadRegister = pendingLoadRegister;
        final int delayedLoadValue = pendingLoadValue;
        pendingLoadRegister = -1;

        final boolean wasDelaySlot = branchPending;
        final int oldBranchTarget = branchTarget;
        final int oldBranchPc = branchInstructionPc;
        branchPending = false;

        currentInstructionPc = pc;
        currentInstructionInDelaySlot = wasDelaySlot;
        currentBranchPc = oldBranchPc;
        registerWrittenThisStep = -1;
        scheduledLoadRegister = -1;
        scheduledLoadValue = 0;
        boolean exceptionTaken = false;

        try {
            if ((currentInstructionPc & 3) != 0) throw new CpuFault(EXC_ADDRESS_LOAD, currentInstructionPc);
            int instruction = bus.read32(currentInstructionPc);
            pc += 4;
            execute(instruction);
            // A control transfer in another transfer's delay slot is undefined on R3000A.
            if (wasDelaySlot && branchPending) {
                branchPending = false;
                throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
            }
        } catch (Ps1Bus.BusFaultException fault) {
            enterException(fault.isWrite() ? EXC_BUS_ERROR_STORE : EXC_BUS_ERROR_LOAD, -1);
            exceptionTaken = true;
        } catch (CpuFault fault) {
            enterException(fault.code, fault.badAddress);
            exceptionTaken = true;
        }

        // The older load is visible after this instruction; a newer write to the same
        // register takes precedence. The current instruction read the pre-load value.
        if (delayedLoadRegister > 0 && delayedLoadRegister != registerWrittenThisStep) {
            gpr[delayedLoadRegister] = delayedLoadValue;
        }
        if (scheduledLoadRegister > 0) {
            pendingLoadRegister = scheduledLoadRegister;
            pendingLoadValue = scheduledLoadValue;
        }
        gpr[0] = 0;

        if (!exceptionTaken && wasDelaySlot) pc = oldBranchTarget;
    }

    private void execute(int instruction) throws CpuFault {
        int opcode = instruction >>> 26;
        int rs = (instruction >>> 21) & 31;
        int rt = (instruction >>> 16) & 31;
        int rd = (instruction >>> 11) & 31;
        int shamt = (instruction >>> 6) & 31;
        int function = instruction & 63;
        int immediate = (short) (instruction & 0xFFFF);
        int unsignedImmediate = instruction & 0xFFFF;

        switch (opcode) {
            case 0x00: executeSpecial(rs, rt, rd, shamt, function); return;
            case 0x01: executeRegimm(rs, rt, immediate); return;
            case 0x02: scheduleBranch((pc & 0xF0000000) | ((instruction & 0x03FFFFFF) << 2)); return;
            case 0x03:
                writeRegister(31, pc + 4);
                scheduleBranch((pc & 0xF0000000) | ((instruction & 0x03FFFFFF) << 2));
                return;
            case 0x04: scheduleBranch(readRegister(rs) == readRegister(rt) ? pc + (immediate << 2) : pc + 4); return;
            case 0x05: scheduleBranch(readRegister(rs) != readRegister(rt) ? pc + (immediate << 2) : pc + 4); return;
            case 0x06: if (rt != 0) throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1); scheduleBranch(readRegister(rs) <= 0 ? pc + (immediate << 2) : pc + 4); return;
            case 0x07: if (rt != 0) throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1); scheduleBranch(readRegister(rs) > 0 ? pc + (immediate << 2) : pc + 4); return;
            case 0x08: writeRegister(rt, checkedAdd(readRegister(rs), immediate)); return;
            case 0x09: writeRegister(rt, readRegister(rs) + immediate); return;
            case 0x0A: writeRegister(rt, readRegister(rs) < immediate ? 1 : 0); return;
            case 0x0B: writeRegister(rt, Integer.compareUnsigned(readRegister(rs), immediate) < 0 ? 1 : 0); return;
            case 0x0C: writeRegister(rt, readRegister(rs) & unsignedImmediate); return;
            case 0x0D: writeRegister(rt, readRegister(rs) | unsignedImmediate); return;
            case 0x0E: writeRegister(rt, readRegister(rs) ^ unsignedImmediate); return;
            case 0x0F: writeRegister(rt, unsignedImmediate << 16); return;
            case 0x10: executeCop0(instruction, rs, rt, rd, function); return;
            case 0x11: throw new CpuFault(EXC_COPROCESSOR_UNUSABLE, -1); // PS1 has no COP1.
            case 0x12: throw new CpuFault(EXC_COPROCESSOR_UNUSABLE, -1); // GTE/COP2 arrives later.
            case 0x20: scheduleLoad(rt, sign8(bus.read8(effectiveAddress(rs, immediate)))); return;
            case 0x21: {
                int address = effectiveAddress(rs, immediate);
                requireAligned(address, 2, false);
                scheduleLoad(rt, (short) bus.read16(address));
                return;
            }
            case 0x22: case 0x26: // LWL/LWR are deliberately rejected until lane semantics are tested.
                throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
            case 0x23: {
                int address = effectiveAddress(rs, immediate);
                requireAligned(address, 4, false);
                scheduleLoad(rt, bus.read32(address));
                return;
            }
            case 0x24: scheduleLoad(rt, bus.read8(effectiveAddress(rs, immediate)) & 0xFF); return;
            case 0x25: {
                int address = effectiveAddress(rs, immediate);
                requireAligned(address, 2, false);
                scheduleLoad(rt, bus.read16(address) & 0xFFFF);
                return;
            }
            case 0x28: bus.write8(effectiveAddress(rs, immediate), readRegister(rt)); return;
            case 0x29: {
                int address = effectiveAddress(rs, immediate);
                requireAligned(address, 2, true);
                bus.write16(address, readRegister(rt));
                return;
            }
            case 0x2A: case 0x2E: // SWL/SWR await verified byte-lane behavior.
                throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
            case 0x2B: {
                int address = effectiveAddress(rs, immediate);
                requireAligned(address, 4, true);
                bus.write32(address, readRegister(rt));
                return;
            }
            default: throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
        }
    }

    private void executeSpecial(int rs, int rt, int rd, int shamt, int function) throws CpuFault {
        int a = readRegister(rs);
        int b = readRegister(rt);
        switch (function) {
            case 0x00: writeRegister(rd, b << shamt); return; // SLL
            case 0x02: writeRegister(rd, b >>> shamt); return; // SRL
            case 0x03: writeRegister(rd, b >> shamt); return; // SRA
            case 0x04: writeRegister(rd, b << (a & 31)); return; // SLLV
            case 0x06: writeRegister(rd, b >>> (a & 31)); return; // SRLV
            case 0x07: writeRegister(rd, b >> (a & 31)); return; // SRAV
            case 0x08: scheduleBranch(a); return; // JR
            case 0x09: writeRegister(rd, pc + 4); scheduleBranch(a); return; // JALR
            case 0x0C: throw new CpuFault(EXC_SYSCALL, -1);
            case 0x0D: throw new CpuFault(EXC_BREAK, -1);
            case 0x10: writeRegister(rd, hi); return; // MFHI
            case 0x11: hi = a; return; // MTHI
            case 0x12: writeRegister(rd, lo); return; // MFLO
            case 0x13: lo = a; return; // MTLO
            case 0x18: { long product = (long) a * (long) b; lo = (int) product; hi = (int) (product >>> 32); return; }
            case 0x19: { long product = Integer.toUnsignedLong(a) * Integer.toUnsignedLong(b); lo = (int) product; hi = (int) (product >>> 32); return; }
            case 0x1A:
                if (b == 0) { lo = a < 0 ? 1 : -1; hi = a; }
                else if (a == Integer.MIN_VALUE && b == -1) { lo = Integer.MIN_VALUE; hi = 0; }
                else { lo = a / b; hi = a % b; }
                return;
            case 0x1B:
                if (b == 0) { lo = -1; hi = a; }
                else { lo = (int) (Integer.toUnsignedLong(a) / Integer.toUnsignedLong(b)); hi = (int) (Integer.toUnsignedLong(a) % Integer.toUnsignedLong(b)); }
                return;
            case 0x20: writeRegister(rd, checkedAdd(a, b)); return; // ADD
            case 0x21: writeRegister(rd, a + b); return; // ADDU
            case 0x22: writeRegister(rd, checkedSubtract(a, b)); return; // SUB
            case 0x23: writeRegister(rd, a - b); return; // SUBU
            case 0x24: writeRegister(rd, a & b); return;
            case 0x25: writeRegister(rd, a | b); return;
            case 0x26: writeRegister(rd, a ^ b); return;
            case 0x27: writeRegister(rd, ~(a | b)); return;
            case 0x2A: writeRegister(rd, a < b ? 1 : 0); return;
            case 0x2B: writeRegister(rd, Integer.compareUnsigned(a, b) < 0 ? 1 : 0); return;
            default: throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
        }
    }

    private void executeRegimm(int rs, int rt, int immediate) throws CpuFault {
        int value = readRegister(rs);
        boolean negative = value < 0;
        boolean taken;
        switch (rt) {
            case 0x00: taken = negative; break; // BLTZ
            case 0x01: taken = !negative; break; // BGEZ
            case 0x10: taken = negative; if (taken) writeRegister(31, pc + 4); break; // BLTZAL
            case 0x11: taken = !negative; if (taken) writeRegister(31, pc + 4); break; // BGEZAL
            default: throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
        }
        scheduleBranch(taken ? pc + (immediate << 2) : pc + 4);
    }

    private void executeCop0(int instruction, int rs, int rt, int rd, int function) throws CpuFault {
        if (rs == 0) { scheduleLoad(rt, cp0[rd & 15]); return; } // MFC0
        if (rs == 4) { cp0[rd & 15] = readRegister(rt); return; } // MTC0
        if (rs == 16 && function == 0x10) { // RFE
            int status = cp0[CP0_STATUS];
            cp0[CP0_STATUS] = (status & ~0x3F) | ((status >>> 2) & 0x0F);
            return;
        }
        throw new CpuFault(EXC_RESERVED_INSTRUCTION, -1);
    }

    private int effectiveAddress(int baseRegister, int offset) {
        return readRegister(baseRegister) + offset;
    }

    private void requireAligned(int address, int alignment, boolean store) throws CpuFault {
        if ((address & (alignment - 1)) != 0) {
            throw new CpuFault(store ? EXC_ADDRESS_STORE : EXC_ADDRESS_LOAD, address);
        }
    }

    private int checkedAdd(int a, int b) throws CpuFault {
        long result = (long) a + (long) b;
        if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) throw new CpuFault(EXC_OVERFLOW, -1);
        return (int) result;
    }

    private int checkedSubtract(int a, int b) throws CpuFault {
        long result = (long) a - (long) b;
        if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) throw new CpuFault(EXC_OVERFLOW, -1);
        return (int) result;
    }

    private void scheduleBranch(int target) {
        branchPending = true;
        branchTarget = target;
        branchInstructionPc = currentInstructionPc;
    }

    private void scheduleLoad(int register, int value) {
        if (register != 0) {
            scheduledLoadRegister = register;
            scheduledLoadValue = value;
        }
    }

    private int readRegister(int index) {
        return index == 0 ? 0 : gpr[index];
    }

    private void writeRegister(int index, int value) {
        if (index != 0) {
            gpr[index] = value;
            registerWrittenThisStep = index;
        }
    }

    private void refreshHardwareInterrupt() {
        if (bus.isInterruptPending()) cp0[CP0_CAUSE] |= 1 << 10;
        else cp0[CP0_CAUSE] &= ~(1 << 10);
    }

    private boolean hardwareInterruptEnabled() {
        int status = cp0[CP0_STATUS];
        int cause = cp0[CP0_CAUSE];
        return (status & 1) != 0 && (status & (1 << 10)) != 0 && (cause & (1 << 10)) != 0;
    }

    private void enterException(int exceptionCode, int badAddress) {
        int status = cp0[CP0_STATUS];
        cp0[CP0_STATUS] = (status & ~0x3F) | ((status << 2) & 0x3F);

        int cause = cp0[CP0_CAUSE] & ~((1 << 31) | (0x1F << 2));
        cause |= (exceptionCode & 0x1F) << 2;
        if (currentInstructionInDelaySlot) cause |= 1 << 31;
        cp0[CP0_CAUSE] = cause;
        cp0[CP0_EPC] = currentInstructionInDelaySlot ? currentBranchPc : currentInstructionPc;
        if (badAddress >= 0) cp0[CP0_BAD_VADDR] = badAddress;
        branchPending = false;
        pc = (cp0[CP0_STATUS] & STATUS_BEV) != 0 ? BOOT_EXCEPTION_VECTOR : GENERAL_EXCEPTION_VECTOR;
    }

    private int sign8(int value) {
        return (byte) value;
    }

    private void checkRegisterIndex(int index) {
        if (index < 0 || index >= gpr.length) throw new IllegalArgumentException("invalid register index");
    }

    private static final class CpuFault extends Exception {
        final int code;
        final int badAddress;
        CpuFault(int code, int badAddress) { this.code = code; this.badAddress = badAddress; }
    }
}
