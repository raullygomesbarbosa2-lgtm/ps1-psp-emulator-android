package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class Ps1SessionTest {
    @Test
    public void loaderMapsStandaloneExeAndInitializesEntryGpAndStack() {
        Ps1Machine machine = new Ps1Machine();
        Ps1ExecutableLoader.ExecutableInfo info = Ps1ExecutableLoader.load(machine, makeExe());

        assertEquals(0x80010000, info.entryPoint);
        assertEquals(0x80001000, info.globalPointer);
        assertEquals(8, info.textSize);
        assertEquals(0x80200000, info.initialStack);
        assertEquals(0x80010000, machine.getCpu().getPc());
        assertEquals(0x80001000, machine.getCpu().getRegister(28));
        assertEquals(0x80200000, machine.getCpu().getRegister(29));
        assertEquals(0x08004000, machine.getBus().read32(0x80010000));
    }

    @Test
    public void loaderRejectsInvalidOrTruncatedExecutables() {
        Ps1Machine machine = new Ps1Machine();
        try {
            Ps1ExecutableLoader.load(machine, new byte[Ps1ExecutableLoader.HEADER_SIZE]);
            fail("invalid magic must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not a PS-X EXE"));
        }

        byte[] truncated = makeExe();
        truncated[0x1C] = 0x20; // text size 32, but only 8 payload bytes
        try {
            Ps1ExecutableLoader.load(machine, truncated);
            fail("truncated payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test(timeout = 20000)
    public void sessionEmitsFramesFromItsRunningMachine() throws InterruptedException {
        Ps1Session session = new Ps1Session();
        session.loadExecutable(makeExe());
        CountDownLatch frameReceived = new CountDownLatch(1);
        AtomicReference<Ps1VideoFrame> frame = new AtomicReference<>();
        session.start(new Ps1Session.FrameListener() {
            @Override public void onFrame(Ps1VideoFrame value) {
                frame.set(value);
                frameReceived.countDown();
            }
        });
        try {
            assertTrue("the session should deliver a raster frame", frameReceived.await(15, TimeUnit.SECONDS));
            assertNotNull(frame.get());
            assertEquals(256, frame.get().getWidth());
            assertEquals(240, frame.get().getHeight());
        } finally {
            session.stop();
        }
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
        writeLe32(file, Ps1ExecutableLoader.HEADER_SIZE, 0x08004000); // j 0x80010000
        writeLe32(file, Ps1ExecutableLoader.HEADER_SIZE + 4, 0); // branch delay slot
        return file;
    }

    private static void writeLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
