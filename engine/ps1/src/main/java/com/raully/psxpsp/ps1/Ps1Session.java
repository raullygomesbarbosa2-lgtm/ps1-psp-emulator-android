package com.raully.psxpsp.ps1;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/**
 * Experimental standalone-executable session. The current provisional scheduler
 * advances one system cycle per interpreted instruction; it is not cycle-accurate.
 */
public final class Ps1Session implements AutoCloseable {
    public enum State { EMPTY, READY, RUNNING, PAUSED, STOPPED }

    public interface FrameListener {
        /** Called on the emulation worker thread when the provisional raster advances a frame. */
        void onFrame(Ps1VideoFrame frame);
    }

    private static final long NTSC_FRAME_NANOS = 16_666_667L;
    private static final long PAL_FRAME_NANOS = 20_000_000L;

    private final Object pauseLock = new Object();
    private final Ps1Machine machine = new Ps1Machine();
    private volatile State state = State.EMPTY;
    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;
    private Ps1ExecutableLoader.ExecutableInfo executableInfo;
    private FrameListener frameListener;
    private Thread worker;

    public State getState() { return state; }
    public Ps1ExecutableLoader.ExecutableInfo getExecutableInfo() { return executableInfo; }
    public boolean hasDisc() { return machine.getBus().getCdrom().hasDisc(); }

    /** Attach an already opened disc image to the machine's CD-ROM device. */
    public synchronized void insertDisc(Ps1DiscImage image) throws IOException {
        if (state == State.RUNNING || state == State.PAUSED || (worker != null && worker.isAlive())) {
            throw new IllegalStateException("stop the current session before replacing the disc");
        }
        machine.getBus().getCdrom().insertDisc(image);
    }

    /** Read SYSTEM.CNF and load its PS-X EXE entry through the experimental HLE shortcut. */
    public synchronized DiscBootInfo bootDisc() throws IOException {
        if (state == State.RUNNING || state == State.PAUSED || (worker != null && worker.isAlive())) {
            throw new IllegalStateException("stop the current session before booting a disc");
        }
        if (!machine.getBus().getCdrom().hasDisc()) throw new IllegalStateException("insert a disc before booting");
        Ps1Iso9660BootLoader.BootExecutable boot = Ps1Iso9660BootLoader.load(machine.getBus().getCdrom().getDiscImage());
        Ps1ExecutableLoader.ExecutableInfo info = Ps1ExecutableLoader.load(machine, boot.copyImage());
        executableInfo = info;
        stopRequested = false;
        pauseRequested = false;
        state = State.READY;
        return new DiscBootInfo(boot.path, info);
    }

    /** Load a standalone PS-X EXE for the experimental session. */ 
    public synchronized Ps1ExecutableLoader.ExecutableInfo loadExecutable(byte[] file) {
        if (state == State.RUNNING || state == State.PAUSED || (worker != null && worker.isAlive())) {
            throw new IllegalStateException("stop the current session before loading another executable");
        }
        executableInfo = Ps1ExecutableLoader.load(machine, file);
        state = State.READY;
        stopRequested = false;
        pauseRequested = false;
        return executableInfo;
    }

    /** Start the experimental CPU/raster loop and emit snapshots as frames advance. */
    public synchronized void start(FrameListener listener) {
        if (state == State.RUNNING) throw new IllegalStateException("session is already running");
        if (state != State.READY) throw new IllegalStateException("load a PS-X EXE before starting");
        frameListener = listener;
        stopRequested = false;
        pauseRequested = false;
        state = State.RUNNING;
        worker = new Thread(this::runLoop, "ps1-session");
        worker.setDaemon(true);
        worker.start();
    }

    public void pause() {
        if (state == State.RUNNING) {
            pauseRequested = true;
            state = State.PAUSED;
        }
    }

    public void resume() {
        synchronized (pauseLock) {
            if (state == State.PAUSED && !stopRequested) {
                pauseRequested = false;
                state = State.RUNNING;
                pauseLock.notifyAll();
            }
        }
    }

    public void stop() {
        stopRequested = true;
        pauseRequested = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        Thread active = worker;
        if (active != null) active.interrupt();
        if (state != State.EMPTY) state = State.STOPPED;
    }

    @Override public void close() {
        Thread active = worker;
        stop();
        if (active != null && active != Thread.currentThread()) {
            try { active.join(500); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        try { machine.getBus().getCdrom().ejectDisc(); } catch (IOException ignored) { }
    }

    public static final class DiscBootInfo {
        public final String executablePath;
        public final Ps1ExecutableLoader.ExecutableInfo executableInfo;
        private DiscBootInfo(String executablePath, Ps1ExecutableLoader.ExecutableInfo executableInfo) {
            this.executablePath = executablePath;
            this.executableInfo = executableInfo;
        }
    }

    private void runLoop() {
        long lastFrame = machine.getVideoTiming().getFrameCount();
        long frameStartNanos = System.nanoTime();
        try {
            while (!stopRequested) {
                synchronized (pauseLock) {
                    while (pauseRequested && !stopRequested) pauseLock.wait();
                }
                if (stopRequested) break;

                machine.step();
                machine.tickSystemCycles(1);
                long currentFrame = machine.getVideoTiming().getFrameCount();
                if (currentFrame != lastFrame) {
                    lastFrame = currentFrame;
                    FrameListener listener = frameListener;
                    if (listener != null) listener.onFrame(machine.renderDisplayFrame());
                    long framePeriod = machine.getGpu().isPal() ? PAL_FRAME_NANOS : NTSC_FRAME_NANOS;
                    long remaining = framePeriod - (System.nanoTime() - frameStartNanos);
                    if (remaining > 0) LockSupport.parkNanos(remaining);
                    frameStartNanos = System.nanoTime();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (state != State.STOPPED) state = State.STOPPED;
        }
    }
}
