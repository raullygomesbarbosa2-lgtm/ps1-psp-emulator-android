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
        assertFalse(gte.executeCommand(0x01)); // RTPS is not implemented yet.
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
