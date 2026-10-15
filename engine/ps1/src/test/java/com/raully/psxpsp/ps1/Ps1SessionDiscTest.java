package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public class Ps1SessionDiscTest {
    @Test
    public void attachedDiscIsEjectedAndClosedWithItsSession() throws Exception {
        MemorySource source = new MemorySource(new byte[2048]);
        Ps1DiscImage image = Ps1DiscImage.open(Ps1CueSheet.forIso("test.iso"), name -> source);
        Ps1Session session = new Ps1Session();

        session.insertDisc(image);
        assertTrue(session.hasDisc());
        session.close();

        assertFalse(session.hasDisc());
        assertTrue(source.closed);
    }

    private static final class MemorySource implements Ps1DiscImage.DataSource {
        private final byte[] bytes;
        boolean closed;
        MemorySource(byte[] bytes) { this.bytes = bytes; }
        @Override public long size() { return bytes.length; }
        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            if (closed || position < 0 || position + length > bytes.length) throw new IOException("invalid read");
            System.arraycopy(bytes, (int) position, destination, offset, length);
        }
        @Override public void close() { closed = true; }
    }
}
