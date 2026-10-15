package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
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

    private static int packSxy(int x, int y) {
        return (y & 0xFFFF) << 16 | (x & 0xFFFF);
    }
}
