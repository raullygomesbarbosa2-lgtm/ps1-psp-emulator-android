package com.raully.psxpsp.ps1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class Ps1AudioClockTest {
    @Test
    public void ntscSampleCountsUseFractionalFramesWithoutLongTermDrift() {
        Ps1AudioClock clock = new Ps1AudioClock(false);
        int total = 0;
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int frame = 0; frame < 600; frame++) {
            int samples = clock.nextFrameSampleCount();
            total += samples;
            minimum = Math.min(minimum, samples);
            maximum = Math.max(maximum, samples);
        }
        assertEquals(441441, total); // 600 frames at 60000/1001 Hz
        assertEquals(735, minimum);
        assertEquals(736, maximum);
    }

    @Test
    public void changingVideoStandardResetsTheFractionalPhase() {
        Ps1AudioClock clock = new Ps1AudioClock(false);
        assertEquals(735, clock.nextFrameSampleCount());
        clock.setPal(true);
        assertEquals(882, clock.nextFrameSampleCount());
        clock.setPal(false);
        assertEquals(735, clock.nextFrameSampleCount());
    }

    @Test
    public void palSampleCountsAreExactly882PerFrame() {
        Ps1AudioClock clock = new Ps1AudioClock(true);
        int total = 0;
        for (int frame = 0; frame < 50; frame++) {
            int samples = clock.nextFrameSampleCount();
            assertEquals(882, samples);
            total += samples;
        }
        assertEquals(Ps1Spu.SAMPLE_RATE, total);
        clock.reset();
        assertTrue(clock.nextFrameSampleCount() == 882);
    }
}
