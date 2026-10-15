package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public class Ps1DiscImageTest {
    @Test
    public void readsTrackRelativeRawSectorsAndMode2UserData() throws Exception {
        byte[] image = new byte[6 * 2352];
        for (int sector = 0; sector < 6; sector++) {
            Arrays.fill(image, sector * 2352, (sector + 1) * 2352, (byte) sector);
        }
        image[4 * 2352 + 18] = 0x20; // Mode 2 Form 2 submode.
        Arrays.fill(image, 4 * 2352 + 24, 5 * 2352, (byte) 0x55);
        image[5 * 2352 + 18] = 0;
        Arrays.fill(image, 5 * 2352 + 24, 6 * 2352, (byte) 0x33);
        MemorySource source = new MemorySource(image);
        Ps1CueSheet cue = Ps1CueSheet.parse("FILE \"disc.bin\" BINARY\n"
                + "TRACK 01 MODE2/2352\nINDEX 01 00:00:00\n"
                + "TRACK 02 AUDIO\nINDEX 00 00:00:01\nINDEX 01 00:00:02\n"
                + "TRACK 03 MODE2/2352\nINDEX 00 00:00:03\nINDEX 01 00:00:04\n");

        Ps1DiscImage disc = Ps1DiscImage.open(cue, name -> {
            assertEquals("disc.bin", name);
            return source;
        });
        assertEquals(1, disc.getTrackSectorCount(1));
        assertEquals(1, disc.getTrackSectorCount(2));
        assertEquals(2, disc.getTrackSectorCount(3));

        byte[] raw = new byte[2352];
        disc.readRawSector(3, 0, raw, 0);
        assertEquals(4, raw[0] & 0xFF);
        byte[] payload = new byte[2324];
        assertEquals(2324, disc.readUserDataSector(3, 0, payload, 0));
        assertEquals(0x55, payload[0] & 0xFF);
        assertEquals(2048, disc.readUserDataSector(3, 1, payload, 0));
        assertEquals(0x33, payload[0] & 0xFF);
        assertThrows(IllegalStateException.class, () -> disc.readUserDataSector(2, 0, payload, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> disc.readRawSector(1, 1, raw, 0));

        disc.close();
        assertTrue(source.closed);
        assertThrows(IllegalStateException.class, () -> disc.readRawSector(1, 0, raw, 0));
    }

    @Test
    public void readsMode1IsoSectorsAndRejectsTruncatedFiles() throws Exception {
        byte[] iso = new byte[2 * 2048];
        Arrays.fill(iso, 2048, iso.length, (byte) 0x6A);
        MemorySource source = new MemorySource(iso);
        Ps1CueSheet cue = Ps1CueSheet.parse("FILE \"disc.iso\" BINARY\n"
                + "TRACK 01 MODE1/2048\nINDEX 01 00:00:00\n");
        Ps1DiscImage disc = Ps1DiscImage.open(cue, name -> source);
        byte[] payload = new byte[2048];
        assertEquals(2048, disc.readUserDataSector(1, 1, payload, 0));
        assertEquals(0x6A, payload[0] & 0xFF);
        disc.close();

        Ps1CueSheet truncatedCue = Ps1CueSheet.parse("FILE \"short.bin\" BINARY\n"
                + "TRACK 01 MODE1/2048\nINDEX 01 00:00:00\n");
        assertThrows(IOException.class, () -> Ps1DiscImage.open(truncatedCue, name -> new MemorySource(new byte[100])));
    }

    private static final class MemorySource implements Ps1DiscImage.DataSource {
        private final byte[] bytes;
        private boolean closed;

        MemorySource(byte[] bytes) { this.bytes = bytes; }
        @Override public long size() { return bytes.length; }
        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            if (closed) throw new IOException("closed");
            if (position < 0 || position + length > bytes.length) throw new IOException("EOF");
            System.arraycopy(bytes, (int) position, destination, offset, length);
        }
        @Override public void close() { closed = true; }
    }
}
