package com.raully.psxpsp.ps1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed metadata for a binary CUE sheet. File access and CD controller behavior are separate. */
public final class Ps1CueSheet {
    private static final Pattern FILE = Pattern.compile("^FILE\\s+(?:\"([^\"]+)\"|(\\S+))\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK = Pattern.compile("^TRACK\\s+(\\d{1,2})\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile("^INDEX\\s+(\\d{1,2})\\s+(\\d{1,3}:\\d{2}:\\d{2})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREGAP = Pattern.compile("^PREGAP\\s+(\\d{1,3}:\\d{2}:\\d{2})\\s*$", Pattern.CASE_INSENSITIVE);

    public enum TrackMode {
        AUDIO(2352),
        MODE1_2048(2048), MODE1_2352(2352),
        MODE2_2048(2048), MODE2_2324(2324), MODE2_2336(2336), MODE2_2352(2352);

        private final int bytesPerSector;

        TrackMode(int bytesPerSector) { this.bytesPerSector = bytesPerSector; }
        public int getBytesPerSector() { return bytesPerSector; }
        public boolean isAudio() { return this == AUDIO; }

        static TrackMode parse(String text) {
            String mode = text.toUpperCase(Locale.ROOT);
            switch (mode) {
                case "AUDIO": return AUDIO;
                case "MODE1/2048": return MODE1_2048;
                case "MODE1/2352": return MODE1_2352;
                case "MODE2/2048": return MODE2_2048;
                case "MODE2/2324": return MODE2_2324;
                case "MODE2/2336": return MODE2_2336;
                case "MODE2/2352": return MODE2_2352;
                default: throw new IllegalArgumentException("unsupported CUE track mode: " + text);
            }
        }
    }

    public static final class Track {
        private final int number;
        private final String fileName;
        private final int fileSection;
        private final TrackMode mode;
        private final long index00Frame;
        private final long index01Frame;
        private final long pregapFrames;

        private Track(TrackBuilder builder) {
            number = builder.number;
            fileName = builder.fileName;
            fileSection = builder.fileSection;
            mode = builder.mode;
            index00Frame = builder.index00Frame;
            index01Frame = builder.index01Frame;
            pregapFrames = builder.pregapFrames;
        }

        public int getNumber() { return number; }
        public String getFileName() { return fileName; }
        public int getFileSection() { return fileSection; }
        public TrackMode getMode() { return mode; }
        public long getIndex00Frame() { return index00Frame; }
        public long getIndex01Frame() { return index01Frame; }
        /** Synthetic pregap declared with PREGAP; these sectors are not present in the binary file. */
        public long getPregapFrames() { return pregapFrames; }
    }

    private static final class TrackBuilder {
        final int number;
        final String fileName;
        final int fileSection;
        final TrackMode mode;
        final Map<Integer, Long> indexes = new HashMap<>();
        long index00Frame = -1;
        long index01Frame = -1;
        long pregapFrames;
        boolean pregapSeen;
        int previousIndexNumber = -1;

        TrackBuilder(int number, String fileName, int fileSection, TrackMode mode) {
            this.number = number;
            this.fileName = fileName;
            this.fileSection = fileSection;
            this.mode = mode;
        }
    }

    private final List<Track> tracks;

    private Ps1CueSheet(List<Track> tracks) {
        this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    public List<Track> getTracks() { return tracks; }

    public Track getTrack(int number) {
        for (Track track : tracks) if (track.number == number) return track;
        throw new IllegalArgumentException("CUE has no track " + number);
    }

    /** Treat a standalone ISO as a single Mode 1, 2048-byte data track. */
    public static Ps1CueSheet forIso(String isoFileName) {
        if (isoFileName == null || isoFileName.trim().isEmpty()) {
            throw new IllegalArgumentException("ISO file name is missing");
        }
        TrackBuilder builder = new TrackBuilder(1, isoFileName, 0, TrackMode.MODE1_2048);
        builder.index01Frame = 0;
        return new Ps1CueSheet(Collections.singletonList(new Track(builder)));
    }

    /** Parse BINARY CUE text. AUDIO and common Mode 1/Mode 2 sector layouts are recognized. */
    public static Ps1CueSheet parse(String cueText) {
        if (cueText == null) throw new IllegalArgumentException("CUE text is missing");
        List<TrackBuilder> builders = new ArrayList<>();
        String currentFile = null;
        int fileSection = -1;
        int previousTrackNumber = 0;
        TrackBuilder currentTrack = null;
        String[] lines = cueText.split("\\r?\\n");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty() || line.regionMatches(true, 0, "REM", 0, 3)
                    || line.regionMatches(true, 0, "TITLE", 0, 5)
                    || line.regionMatches(true, 0, "PERFORMER", 0, 9)
                    || line.regionMatches(true, 0, "CATALOG", 0, 7)
                    || line.regionMatches(true, 0, "FLAGS", 0, 5)
                    || line.regionMatches(true, 0, "ISRC", 0, 4)
                    || line.regionMatches(true, 0, "POSTGAP", 0, 7)) continue;

            Matcher matcher = FILE.matcher(line);
            if (matcher.matches()) {
                currentFile = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (currentFile.trim().isEmpty()) throw error(lineIndex, "empty FILE name");
                if (!"BINARY".equalsIgnoreCase(matcher.group(3))) {
                    throw error(lineIndex, "only BINARY CUE files are supported");
                }
                fileSection++;
                currentTrack = null;
                continue;
            }

            matcher = TRACK.matcher(line);
            if (matcher.matches()) {
                if (currentFile == null) throw error(lineIndex, "TRACK appears before FILE");
                int number = Integer.parseInt(matcher.group(1));
                if (number < 1 || number > 99 || number <= previousTrackNumber) {
                    throw error(lineIndex, "track numbers must be in ascending order from 01 to 99");
                }
                TrackMode mode;
                try { mode = TrackMode.parse(matcher.group(2)); }
                catch (IllegalArgumentException unsupported) { throw error(lineIndex, unsupported.getMessage()); }
                currentTrack = new TrackBuilder(number, currentFile, fileSection, mode);
                builders.add(currentTrack);
                previousTrackNumber = number;
                continue;
            }

            matcher = INDEX.matcher(line);
            if (matcher.matches()) {
                if (currentTrack == null) throw error(lineIndex, "INDEX appears before TRACK");
                int indexNumber = Integer.parseInt(matcher.group(1));
                long frame = parseTime(matcher.group(2), lineIndex);
                if (indexNumber <= currentTrack.previousIndexNumber) {
                    throw error(lineIndex, "track indexes must be in ascending order");
                }
                currentTrack.previousIndexNumber = indexNumber;
                if (indexNumber == 0 || indexNumber == 1) {
                    if (currentTrack.indexes.put(indexNumber, frame) != null) {
                        throw error(lineIndex, "duplicate INDEX " + indexNumber);
                    }
                    if (indexNumber == 0) currentTrack.index00Frame = frame;
                    else currentTrack.index01Frame = frame;
                }
                continue;
            }

            matcher = PREGAP.matcher(line);
            if (matcher.matches()) {
                if (currentTrack == null) throw error(lineIndex, "PREGAP appears before TRACK");
                if (currentTrack.pregapSeen) throw error(lineIndex, "duplicate PREGAP");
                currentTrack.pregapFrames = parseTime(matcher.group(1), lineIndex);
                currentTrack.pregapSeen = true;
                continue;
            }

            if (startsWithDirective(line, "FILE") || startsWithDirective(line, "TRACK")
                    || startsWithDirective(line, "INDEX") || startsWithDirective(line, "PREGAP")) {
                throw error(lineIndex, "malformed CUE directive");
            }
            // Other CUE metadata directives are not needed to locate binary sectors.
        }

        if (builders.isEmpty()) throw new IllegalArgumentException("CUE contains no tracks");
        Map<Integer, Long> lastIndexBySection = new HashMap<>();
        Map<Integer, Integer> sectorSizeBySection = new HashMap<>();
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            TrackBuilder builder = builders.get(i);
            if (builder.index01Frame < 0) throw new IllegalArgumentException("track " + builder.number + " has no INDEX 01");
            if (builder.index00Frame >= 0 && builder.index00Frame > builder.index01Frame) {
                throw new IllegalArgumentException("track " + builder.number + " INDEX 00 is after INDEX 01");
            }
            Long previousIndex = lastIndexBySection.put(builder.fileSection, builder.index01Frame);
            if (previousIndex != null && builder.index01Frame <= previousIndex) {
                throw new IllegalArgumentException("INDEX 01 positions must increase within each FILE");
            }
            Integer size = sectorSizeBySection.put(builder.fileSection, builder.mode.bytesPerSector);
            if (size != null && size != builder.mode.bytesPerSector) {
                throw new IllegalArgumentException("tracks in one FILE must use the same sector size");
            }
            tracks.add(new Track(builder));
        }
        return new Ps1CueSheet(tracks);
    }

    private static long parseTime(String value, int lineIndex) {
        String[] parts = value.split(":");
        try {
            long minutes = Long.parseLong(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            int frames = Integer.parseInt(parts[2]);
            if (seconds >= 60 || frames >= 75) throw error(lineIndex, "invalid CUE time");
            return (minutes * 60L + seconds) * 75L + frames;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException invalid) {
            throw error(lineIndex, "invalid CUE time");
        }
    }

    private static boolean startsWithDirective(String line, String directive) {
        return line.equalsIgnoreCase(directive) || line.regionMatches(true, 0, directive + " ", 0, directive.length() + 1);
    }

    private static IllegalArgumentException error(int zeroBasedLine, String message) {
        return new IllegalArgumentException("CUE line " + (zeroBasedLine + 1) + ": " + message);
    }
}
