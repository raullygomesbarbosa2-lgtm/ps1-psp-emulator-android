package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class Ps1Iso9660BootLoaderTest {
    @Test
    public void findsSystemConfigBootPathAndLoadsPsxExecutableThroughSession() throws Exception {
        byte[] iso = makeBootableIso();
        Ps1DiscImage image = Ps1DiscImage.open(Ps1CueSheet.forIso("test.iso"), name -> new MemorySource(iso));
        Ps1Session session = new Ps1Session();
        session.insertDisc(image);

        Ps1Session.DiscBootInfo boot = session.bootDisc();

        assertEquals("cdrom:\\SLUS_000.00;1", boot.executablePath);
        assertEquals(0x80010000, boot.executableInfo.entryPoint);
        assertEquals(Ps1Session.State.READY, session.getState());
        assertTrue(session.hasDisc());
        session.close();
    }

    private static byte[] makeBootableIso() {
        byte[] iso = new byte[24 * 2048];
        byte[] pvd = new byte[2048];
        pvd[0] = 1;
        byte[] id = {'C', 'D', '0', '0', '1'};
        System.arraycopy(id, 0, pvd, 1, id.length);
        pvd[6] = 1;
        writeDirectoryRecord(pvd, 156, 20, 2048, true, new byte[] {0});
        System.arraycopy(pvd, 0, iso, 16 * 2048, 2048);

        byte[] root = new byte[2048];
        int cursor = writeDirectoryRecord(root, 0, 20, 2048, true, new byte[] {0});
        cursor += writeDirectoryRecord(root, cursor, 20, 2048, true, new byte[] {1});
        byte[] cnf = "BOOT = cdrom:\\SLUS_000.00;1\nTCB = 4\n".getBytes(StandardCharsets.US_ASCII);
        cursor += writeDirectoryRecord(root, cursor, 21, cnf.length, false,
                "SYSTEM.CNF;1".getBytes(StandardCharsets.US_ASCII));
        byte[] executable = makeExe();
        writeDirectoryRecord(root, cursor, 22, executable.length, false,
                "SLUS_000.00;1".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(root, 0, iso, 20 * 2048, 2048);
        System.arraycopy(cnf, 0, iso, 21 * 2048, cnf.length);
        System.arraycopy(executable, 0, iso, 22 * 2048, executable.length);
        return iso;
    }

    private static int writeDirectoryRecord(byte[] sector, int offset, int extent, int size,
                                            boolean directory, byte[] name) {
        int length = 33 + name.length + ((name.length & 1) == 0 ? 1 : 0);
        sector[offset] = (byte) length;
        sector[offset + 1] = 0;
        writeBothEndian32(sector, offset + 2, extent);
        writeBothEndian32(sector, offset + 10, size);
        sector[offset + 25] = (byte) (directory ? 2 : 0);
        writeBothEndian16(sector, offset + 28, 1);
        sector[offset + 32] = (byte) name.length;
        System.arraycopy(name, 0, sector, offset + 33, name.length);
        return length;
    }

    private static void writeBothEndian32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
        target[offset + 4] = (byte) (value >>> 24);
        target[offset + 5] = (byte) (value >>> 16);
        target[offset + 6] = (byte) (value >>> 8);
        target[offset + 7] = (byte) value;
    }

    private static void writeBothEndian16(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static byte[] makeExe() {
        byte[] file = new byte[Ps1ExecutableLoader.HEADER_SIZE + 8];
        byte[] magic = {'P', 'S', '-', 'X', ' ', 'E', 'X', 'E'};
        System.arraycopy(magic, 0, file, 0, magic.length);
        writeLe32(file, 0x10, 0x80010000);
        writeLe32(file, 0x14, 0x80001000);
        writeLe32(file, 0x18, 0x80010000);
        writeLe32(file, 0x1C, 8);
        writeLe32(file, 0x30, 0x801FF000);
        writeLe32(file, 0x34, 0x1000);
        writeLe32(file, Ps1ExecutableLoader.HEADER_SIZE, 0x08004000);
        writeLe32(file, Ps1ExecutableLoader.HEADER_SIZE + 4, 0);
        return file;
    }

    private static void writeLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static final class MemorySource implements Ps1DiscImage.DataSource {
        private final byte[] data;
        MemorySource(byte[] data) { this.data = data; }
        @Override public long size() { return data.length; }
        @Override public void readFully(long position, byte[] target, int offset, int length) throws IOException {
            if (position < 0 || position + length > data.length) throw new IOException("out of range");
            System.arraycopy(data, (int) position, target, offset, length);
        }
        @Override public void close() { }
    }
}
