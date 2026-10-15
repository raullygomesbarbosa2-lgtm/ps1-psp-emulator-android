package com.raully.psxpsp.ps1;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Random-access reader for binary files referenced by a parsed CUE sheet. */
public final class Ps1DiscImage implements AutoCloseable {
    public interface DataSource extends AutoCloseable {
        long size() throws IOException;
        void readFully(long position, byte[] destination, int offset, int length) throws IOException;
        @Override void close() throws IOException;
    }

    public interface FileProvider {
        DataSource open(String cueFileName) throws IOException;
    }

    private static final class TrackLayout {
        final Ps1CueSheet.Track track;
        final DataSource source;
        final long firstSector;
        final long sectorCount;
        final long discStartLba;

        TrackLayout(Ps1CueSheet.Track track, DataSource source, long firstSector,
                    long sectorCount, long discStartLba) {
            this.track = track;
            this.source = source;
            this.firstSector = firstSector;
            this.sectorCount = sectorCount;
            this.discStartLba = discStartLba;
        }
    }

    private final Ps1CueSheet cueSheet;
    private final Map<String, DataSource> sources;
    private final Map<Integer, TrackLayout> tracks;
    private boolean closed;

    private Ps1DiscImage(Ps1CueSheet cueSheet, Map<String, DataSource> sources,
                         Map<Integer, TrackLayout> tracks) {
        this.cueSheet = cueSheet;
        this.sources = sources;
        this.tracks = tracks;
    }

    /** Opens each referenced binary once and validates track ranges against its file size. */
    public static Ps1DiscImage open(Ps1CueSheet cueSheet, FileProvider provider) throws IOException {
        if (cueSheet == null || provider == null) throw new IllegalArgumentException("CUE sheet and file provider are required");
        Map<String, DataSource> sources = new LinkedHashMap<>();
        try {
            for (Ps1CueSheet.Track track : cueSheet.getTracks()) {
                if (!sources.containsKey(track.getFileName())) {
                    DataSource source = provider.open(track.getFileName());
                    if (source == null) throw new IOException("could not open CUE file: " + track.getFileName());
                    sources.put(track.getFileName(), source);
                }
            }
            Map<Integer, TrackLayout> layouts = new LinkedHashMap<>();
            for (int index = 0; index < cueSheet.getTracks().size(); index++) {
                Ps1CueSheet.Track track = cueSheet.getTracks().get(index);
                DataSource source = sources.get(track.getFileName());
                int sectorSize = track.getMode().getBytesPerSector();
                long fileSize = source.size();
                if (fileSize < 0 || fileSize % sectorSize != 0) {
                    throw new IOException("binary size is not a whole number of sectors: " + track.getFileName());
                }
                long fileSectorCount = fileSize / sectorSize;
                long endFrame = fileSectorCount;
                for (int nextIndex = index + 1; nextIndex < cueSheet.getTracks().size(); nextIndex++) {
                    Ps1CueSheet.Track next = cueSheet.getTracks().get(nextIndex);
                    if (next.getFileSection() == track.getFileSection()) {
                        endFrame = next.getIndex00Frame() >= 0 ? next.getIndex00Frame() : next.getIndex01Frame();
                        break;
                    }
                }
                long firstFrame = track.getIndex01Frame();
                if (firstFrame > fileSectorCount || endFrame < firstFrame || endFrame > fileSectorCount) {
                    throw new IOException("CUE track " + track.getNumber() + " points outside " + track.getFileName());
                }
                long count = endFrame - firstFrame;
                if (count == 0) throw new IOException("CUE track " + track.getNumber() + " has no stored sectors");
                long discStartLba = 0;
                if (index > 0) {
                    Ps1CueSheet.Track previousTrack = cueSheet.getTracks().get(index - 1);
                    TrackLayout previousLayout = layouts.get(previousTrack.getNumber());
                    long storedPregap = track.getIndex00Frame() >= 0
                            ? track.getIndex01Frame() - track.getIndex00Frame() : 0;
                    discStartLba = previousLayout.discStartLba + previousLayout.sectorCount
                            + storedPregap + track.getPregapFrames();
                }
                layouts.put(track.getNumber(), new TrackLayout(track, source, firstFrame, count, discStartLba));
            }
            return new Ps1DiscImage(cueSheet, Collections.unmodifiableMap(sources),
                    Collections.unmodifiableMap(layouts));
        } catch (IOException | RuntimeException failure) {
            for (DataSource source : sources.values()) {
                try { source.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        }
    }

    public Ps1CueSheet getCueSheet() { return cueSheet; }

    public long getTrackSectorCount(int trackNumber) {
        return layout(trackNumber).sectorCount;
    }

    public Ps1CueSheet.TrackMode getTrackMode(int trackNumber) {
        return layout(trackNumber).track.getMode();
    }

    public long getTrackStartLba(int trackNumber) {
        return layout(trackNumber).discStartLba;
    }

    public long getDiscSectorCount() {
        long end = 0;
        for (TrackLayout layout : tracks.values()) {
            end = Math.max(end, layout.discStartLba + layout.sectorCount);
        }
        return end;
    }

    /** Reads a data sector by the disc LBA timeline (the standard lead-in is not included). */
    public int readUserDataSectorAtLba(long lba, byte[] destination, int destinationOffset) throws IOException {
        if (lba < 0) throw new IndexOutOfBoundsException("negative disc LBA");
        for (TrackLayout layout : tracks.values()) {
            long end = layout.discStartLba + layout.sectorCount;
            if (lba >= layout.discStartLba && lba < end) {
                if (layout.track.getMode().isAudio()) throw new IllegalStateException("LBA points to an audio track");
                return readUserDataSector(layout.track.getNumber(), lba - layout.discStartLba,
                        destination, destinationOffset);
            }
        }
        throw new IndexOutOfBoundsException("LBA is outside a stored data track");
    }

    /** Reads one stored sector relative to the track's INDEX 01. */
    public void readRawSector(int trackNumber, long sectorOffset, byte[] destination, int destinationOffset)
            throws IOException {
        TrackLayout layout = layout(trackNumber);
        int sectorSize = layout.track.getMode().getBytesPerSector();
        checkSectorRange(layout, sectorOffset);
        checkDestination(destination, destinationOffset, sectorSize);
        long absoluteSector = layout.firstSector + sectorOffset;
        layout.source.readFully(absoluteSector * sectorSize, destination, destinationOffset, sectorSize);
    }

    /**
     * Reads the user-data payload from a data sector. Returns 2048 or 2324 bytes depending on Mode 2 form.
     * Raw sector headers and audio tracks are not returned as user data.
     */
    public int readUserDataSector(int trackNumber, long sectorOffset, byte[] destination, int destinationOffset)
            throws IOException {
        TrackLayout layout = layout(trackNumber);
        Ps1CueSheet.TrackMode mode = layout.track.getMode();
        checkSectorRange(layout, sectorOffset);
        if (mode.isAudio()) throw new IllegalStateException("audio tracks do not have data sectors");
        if (mode == Ps1CueSheet.TrackMode.MODE1_2048 || mode == Ps1CueSheet.TrackMode.MODE2_2048
                || mode == Ps1CueSheet.TrackMode.MODE2_2324) {
            int length = mode.getBytesPerSector();
            checkDestination(destination, destinationOffset, length);
            long absoluteSector = layout.firstSector + sectorOffset;
            layout.source.readFully(absoluteSector * length, destination, destinationOffset, length);
            return length;
        }

        int sectorSize = mode.getBytesPerSector();
        byte[] raw = new byte[sectorSize];
        readRawSector(trackNumber, sectorOffset, raw, 0);
        int dataOffset;
        int dataLength;
        if (mode == Ps1CueSheet.TrackMode.MODE1_2352) {
            dataOffset = 16;
            dataLength = 2048;
        } else if (mode == Ps1CueSheet.TrackMode.MODE2_2352) {
            int subMode = raw[18] & 0xFF;
            dataOffset = 24;
            dataLength = (subMode & 0x20) != 0 ? 2324 : 2048;
        } else if (mode == Ps1CueSheet.TrackMode.MODE2_2336) {
            int subMode = raw[2] & 0xFF;
            dataOffset = 8;
            dataLength = (subMode & 0x20) != 0 ? 2324 : 2048;
        } else {
            throw new IllegalStateException("unsupported data sector mode: " + mode);
        }
        checkDestination(destination, destinationOffset, dataLength);
        System.arraycopy(raw, dataOffset, destination, destinationOffset, dataLength);
        return dataLength;
    }

    private TrackLayout layout(int trackNumber) {
        if (closed) throw new IllegalStateException("disc image is closed");
        TrackLayout layout = tracks.get(trackNumber);
        if (layout == null) throw new IllegalArgumentException("CUE has no track " + trackNumber);
        return layout;
    }

    private static void checkSectorRange(TrackLayout layout, long sectorOffset) {
        if (sectorOffset < 0 || sectorOffset >= layout.sectorCount) {
            throw new IndexOutOfBoundsException("sector offset is outside track " + layout.track.getNumber());
        }
    }

    private static void checkDestination(byte[] destination, int offset, int length) {
        if (destination == null || offset < 0 || length < 0 || offset > destination.length - length) {
            throw new IndexOutOfBoundsException("destination buffer is too small");
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (DataSource source : sources.values()) {
            try { source.close(); }
            catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
