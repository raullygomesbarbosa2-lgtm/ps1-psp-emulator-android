package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Ps1GteTest {
    @Test
    public void nclipCalculatesSignedTriangleAreaFromScreenFifo() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeData(12, packSxy(0, 0));
        gte.writeData(13, packSxy(10, 0));
        gte.writeData(14, packSxy(0, 10));

        assertTrue(gte.executeCommand(0x06));
        assertEquals(100, gte.readData(24)); // MAC0

        gte.writeData(12, packSxy(0, 0));
        gte.writeData(13, packSxy(0, 10));
        gte.writeData(14, packSxy(10, 0));
        assertTrue(gte.executeCommand(0x06));
        assertEquals(-100, gte.readData(24));
    }

    @Test
    public void rtpsTransformsProjectsAndAdvancesScreenAndDepthFifos() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeControl(0, 0x00001000); // R11=1.0, R12=0
        gte.writeControl(1, 0x00000000); // R13=0, R21=0
        gte.writeControl(2, 0x00001000); // R22=1.0, R23=0
        gte.writeControl(3, 0x00000000); // R31=0, R32=0
        gte.writeControl(4, 0x00001000); // R33=1.0
        gte.writeControl(26, 100); // Projection H
        gte.writeControl(28, 0x01000000); // DQB gives IR0 exactly 0x1000.
        gte.writeData(0, packSxy(10, 20));
        gte.writeData(1, 100);
        gte.writeData(12, packSxy(1, 2));
        gte.writeData(13, packSxy(3, 4));
        gte.writeData(14, packSxy(5, 6));
        gte.writeData(16, 10);
        gte.writeData(17, 20);
        gte.writeData(18, 30);
        gte.writeData(19, 40);

        assertTrue(gte.executeCommand(0x00080001)); // RTPS, SF=1.

        assertEquals(10, gte.readData(25)); // MAC1
        assertEquals(20, gte.readData(26)); // MAC2
        assertEquals(100, gte.readData(27)); // MAC3
        assertEquals(10, gte.readData(9)); // IR1
        assertEquals(20, gte.readData(10)); // IR2
        assertEquals(100, gte.readData(11)); // IR3
        assertEquals(20, gte.readData(16));
        assertEquals(30, gte.readData(17));
        assertEquals(40, gte.readData(18));
        assertEquals(100, gte.readData(19));
        assertEquals(packSxy(3, 4), gte.readData(12));
        assertEquals(packSxy(5, 6), gte.readData(13));
        assertEquals(packSxy(10, 20), gte.readData(14));
        assertEquals(0x1000, gte.readData(8)); // IR0
        assertEquals(0x01000000, gte.readData(24)); // final MAC0 = DQB
        assertEquals(0, gte.readControl(31));
    }

    @Test
    public void rtptProjectsThreeVerticesAndDepthCueUsesOnlyTheLastVertex() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeControl(0, 0x00001000);
        gte.writeControl(2, 0x00001000);
        gte.writeControl(4, 0x00001000);
        gte.writeControl(26, 100);
        gte.writeControl(27, 500); // V0 would saturate IR0; V2 should not.
        gte.writeData(0, packSxy(10, 20));
        gte.writeData(1, 100);
        gte.writeData(2, packSxy(30, 40));
        gte.writeData(3, 200);
        gte.writeData(4, packSxy(50, 60));
        gte.writeData(5, 400);
        gte.writeData(8, 123);
        gte.writeData(16, 9);
        gte.writeData(17, 8);
        gte.writeData(18, 7);
        gte.writeData(19, 6);

        assertTrue(gte.executeCommand(0x00080030)); // RTPT, SF=1.

        assertEquals(packSxy(10, 20), gte.readData(12));
        assertEquals(packSxy(15, 20), gte.readData(13));
        assertEquals(packSxy(12, 15), gte.readData(14));
        assertEquals(6, gte.readData(16));
        assertEquals(100, gte.readData(17));
        assertEquals(200, gte.readData(18));
        assertEquals(400, gte.readData(19));
        assertEquals(2000, gte.readData(8));
        assertEquals(8_192_000, gte.readData(24));
        assertEquals(0, gte.readControl(31)); // no IR0 saturation from the first vertex
    }

    @Test
    public void mvmvaSelectsRotationVectorAndTranslationAndWritesMacIr() {
        Ps1Gte gte = new Ps1Gte();
        setIdentityMatrix(gte, 0);
        gte.writeControl(5, 1);
        gte.writeControl(6, -2);
        gte.writeControl(7, 3);
        gte.writeData(0, packSxy(2, 3));
        gte.writeData(1, 4);
        gte.writeData(12, packSxy(11, 12)); // MVMVA must not touch SXY FIFO.

        assertTrue(gte.executeCommand(mvmvaInstruction(1, 0, 0, 0, false)));

        assertEquals(3, gte.readData(25));
        assertEquals(1, gte.readData(26));
        assertEquals(7, gte.readData(27));
        assertEquals(3, gte.readData(9));
        assertEquals(1, gte.readData(10));
        assertEquals(7, gte.readData(11));
        assertEquals(packSxy(11, 12), gte.readData(12));
    }

    @Test
    public void mvmvaCanSelectLightMatrixAndVectorOne() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeControl(8, 0x00002000);
        gte.writeControl(10, 0x00002000);
        gte.writeControl(12, 0x00002000);
        gte.writeData(2, packSxy(-2, 3));
        gte.writeData(3, 4);

        assertTrue(gte.executeCommand(mvmvaInstruction(1, 1, 1, 3, false)));

        assertEquals(-4, gte.readData(9));
        assertEquals(6, gte.readData(10));
        assertEquals(8, gte.readData(11));
    }

    @Test
    public void mvmvaSelectsColorMatrixVectorTwoAndBackgroundTranslation() {
        Ps1Gte gte = new Ps1Gte();
        setIdentityMatrix(gte, 16);
        gte.writeControl(13, 4);
        gte.writeControl(14, -5);
        gte.writeControl(15, 6);
        gte.writeData(4, packSxy(1, 2));
        gte.writeData(5, 3);

        assertTrue(gte.executeCommand(mvmvaInstruction(1, 2, 2, 1, false)));

        assertEquals(5, gte.readData(9));
        assertEquals(-3, gte.readData(10));
        assertEquals(9, gte.readData(11));
    }

    @Test
    public void mvmvaHonorsLimitModeAndRejectsReservedMatrixSelector() {
        Ps1Gte gte = new Ps1Gte();
        setIdentityMatrix(gte, 0);
        gte.writeData(9, -3);
        gte.writeData(10, 4);
        gte.writeData(11, 5);

        assertTrue(gte.executeCommand(mvmvaInstruction(1, 0, 3, 3, false)));
        assertEquals(-3, gte.readData(9));
        assertTrue(gte.executeCommand(mvmvaInstruction(1, 0, 3, 3, true)));
        assertEquals(-3, gte.readData(25)); // MAC is not clipped.
        assertEquals(0, gte.readData(9));
        assertEquals(0x81000000, gte.readControl(31)); // IR1 saturation and summary.
        assertFalse(gte.executeCommand(mvmvaInstruction(1, 3, 0, 0, false)));
    }

    @Test
    public void mvmvaFarColorSelectorRetainsItsTwoStageHardwareQuirk() {
        Ps1Gte gte = new Ps1Gte();
        setIdentityMatrix(gte, 0);
        gte.writeControl(21, 50000);

        assertTrue(gte.executeCommand(mvmvaInstruction(1, 0, 0, 2, false)));

        assertEquals(0, gte.readData(25)); // Second stage replaces MAC1.
        assertEquals(0, gte.readData(9));
        assertEquals(0x81000000, gte.readControl(31)); // First-stage IR saturation remains flagged.
    }

    @Test
    public void screenFifoAndReadOnlyDerivedRegistersFollowAliases() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeData(12, 0x00020001);
        gte.writeData(13, 0x00040003);
        gte.writeData(14, 0x00060005);
        gte.writeData(15, 0x00080007);

        assertEquals(0x00040003, gte.readData(12));
        assertEquals(0x00060005, gte.readData(13));
        assertEquals(0x00080007, gte.readData(14));
        assertEquals(0x00080007, gte.readData(15));

        gte.writeData(23, 0xFFFFFFFF);
        gte.writeData(29, 0xFFFFFFFF);
        assertEquals(0, gte.readData(23));
        assertEquals(0x00000000, gte.readData(29));
    }

    @Test
    public void irgbAndLeadingSignCountUseTheirDerivedRegisterRules() {
        Ps1Gte gte = new Ps1Gte();
        gte.writeData(28, 0x00007FFF);
        assertEquals(31 << 7, gte.readData(9));
        assertEquals(31 << 7, gte.readData(10));
        assertEquals(31 << 7, gte.readData(11));
        assertEquals(0x00007FFF, gte.readData(28));
        assertEquals(0x00007FFF, gte.readData(29));

        gte.writeData(30, 0x00000010);
        assertEquals(27, gte.readData(31));
        gte.writeData(30, -1);
        assertEquals(32, gte.readData(31));
    }

    private static void setIdentityMatrix(Ps1Gte gte, int base) {
        gte.writeControl(base, 0x00001000);
        gte.writeControl(base + 2, 0x00001000);
        gte.writeControl(base + 4, 0x00001000);
    }

    private static int mvmvaInstruction(int sf, int matrix, int vector, int translation, boolean lm) {
        return (sf << 19) | (matrix << 17) | (vector << 15) | (translation << 13)
                | (lm ? 1 << 10 : 0) | 0x12;
    }

    private static int packSxy(int x, int y) {
        return (y & 0xFFFF) << 16 | (x & 0xFFFF);
    }
}
