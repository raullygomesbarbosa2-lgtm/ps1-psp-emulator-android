package com.raully.psxpsp.ps1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class Ps1CueSheetTest {
    @Test
    public void parsesTracksModesAndPregapsFromQuotedAndMultipleFiles() {
        String cueText = "FILE \"disc one.bin\" BINARY\n"
                + "  TRACK 01 MODE2/2352\n"
                + "    INDEX 01 00:00:00\n"
                + "  TRACK 02 AUDIO\n"
                + "    INDEX 00 00:02:00\n"
                + "    INDEX 01 00:03:00\n"
                + "FILE second.iso BINARY\n"
                + "  TRACK 03 MODE1/2048\n"
                + "    PREGAP 00:00:10\n"
                + "    INDEX 01 00:00:00\n";

        Ps1CueSheet cue = Ps1CueSheet.parse(cueText);

        assertEquals(3, cue.getTracks().size());
        assertEquals("disc one.bin", cue.getTrack(1).getFileName());
        assertEquals(Ps1CueSheet.TrackMode.MODE2_2352, cue.getTrack(1).getMode());
        assertEquals(150, cue.getTrack(2).getIndex00Frame());
        assertEquals(225, cue.getTrack(2).getIndex01Frame());
        assertEquals(10, cue.getTrack(3).getPregapFrames());
        assertEquals("second.iso", cue.getTrack(3).getFileName());
    }

    @Test
    public void standaloneIsoUsesOneMode1DataTrack() {
        Ps1CueSheet cue = Ps1CueSheet.forIso("game.iso");
        assertEquals(1, cue.getTracks().size());
        assertEquals(Ps1CueSheet.TrackMode.MODE1_2048, cue.getTrack(1).getMode());
        assertEquals(0, cue.getTrack(1).getIndex01Frame());
    }

    @Test
    public void rejectsNonBinaryAndInconsistentTrackLayouts() {
        assertThrows(IllegalArgumentException.class, () -> Ps1CueSheet.parse(
                "FILE \"music.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n"));
        assertThrows(IllegalArgumentException.class, () -> Ps1CueSheet.parse(
                "FILE \"bad.bin\" BINARY\n"
                        + "TRACK 01 MODE1/2048\nINDEX 01 00:00:00\n"
                        + "TRACK 02 MODE2/2352\nINDEX 01 00:01:00\n"));
        assertThrows(IllegalArgumentException.class, () -> Ps1CueSheet.parse(
                "FILE \"bad.bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:75\n"));
    }
}
