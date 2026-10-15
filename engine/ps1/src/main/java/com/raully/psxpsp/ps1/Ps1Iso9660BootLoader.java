package com.raully.psxpsp.ps1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Experimental no-BIOS boot shortcut for ISO9660 discs with SYSTEM.CNF and a PS-X EXE. */
public final class Ps1Iso9660BootLoader {
    private static final int SECTOR_SIZE = 2048;
    private static final int FIRST_VOLUME_DESCRIPTOR_LBA = 16;
    private static final int MAX_DIRECTORY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BOOT_FILE_BYTES = 16 * 1024 * 1024;
    private static final byte[] CD001 = {'C', 'D', '0', '0', '1'};

    private Ps1Iso9660BootLoader() { }

    public static BootExecutable load(Ps1DiscImage disc) throws IOException {
        if (disc == null) throw new IllegalArgumentException("disc image is required");
        byte[] primary = findPrimaryVolumeDescriptor(disc);
        DirectoryEntry root = parseDirectoryEntry(primary, 156, primary[156] & 0xFF);
        if (root == null || !root.isDirectory()) throw new IOException("ISO9660 root directory is invalid");

        DirectoryEntry systemConfig = findPathEntry(disc, root, "SYSTEM.CNF");
        if (systemConfig == null || systemConfig.isDirectory()) throw new IOException("SYSTEM.CNF is missing");
        byte[] configBytes = readFile(disc, systemConfig, 64 * 1024);
        String bootPath = parseBootPath(new String(configBytes, StandardCharsets.US_ASCII));
        if (bootPath == null) throw new IOException("SYSTEM.CNF has no BOOT entry");

        DirectoryEntry executable = findPathEntry(disc, root, bootPath);
        if (executable == null || executable.isDirectory()) throw new IOException("BOOT executable is missing from the disc");
        byte[] image = readFile(disc, executable, MAX_BOOT_FILE_BYTES);
        if (image.length < Ps1ExecutableLoader.HEADER_SIZE) throw new IOException("BOOT file is too small to be a PS-X EXE");
        return new BootExecutable(bootPath, image);
    }

    private static byte[] findPrimaryVolumeDescriptor(Ps1DiscImage disc) throws IOException {
        byte[] sector = new byte[SECTOR_SIZE];
        for (int lba = FIRST_VOLUME_DESCRIPTOR_LBA; lba < FIRST_VOLUME_DESCRIPTOR_LBA + 32; lba++) {
            readSector(disc, lba, sector);
            if (!hasCd001Identifier(sector)) throw new IOException("ISO9660 volume descriptor is invalid");
            int type = sector[0] & 0xFF;
            if (type == 1 && (sector[6] & 0xFF) == 1) return sector;
            if (type == 255) break;
        }
        throw new IOException("ISO9660 primary volume descriptor was not found");
    }

    private static boolean hasCd001Identifier(byte[] sector) {
        if (sector.length < SECTOR_SIZE) return false;
        for (int i = 0; i < CD001.length; i++) if (sector[i + 1] != CD001[i]) return false;
        return true;
    }

    private static DirectoryEntry findPathEntry(Ps1DiscImage disc, DirectoryEntry root, String path)
            throws IOException {
        String clean = path.trim().replace('\\', '/');
        int colon = clean.indexOf(':');
        if (colon >= 0) clean = clean.substring(colon + 1);
        while (clean.startsWith("/")) clean = clean.substring(1);
        String[] components = clean.split("/+");
        DirectoryEntry current = root;
        for (int i = 0; i < components.length; i++) {
            if (components[i].isEmpty() || components[i].equals(".")) continue;
            current = findChild(disc, current, components[i]);
            if (current == null) return null;
            if (i < components.length - 1 && !current.isDirectory()) return null;
        }
        return current;
    }

    private static DirectoryEntry findChild(Ps1DiscImage disc, DirectoryEntry directory, String name)
            throws IOException {
        if (!directory.isDirectory()) return null;
        byte[] contents = readFile(disc, directory, MAX_DIRECTORY_BYTES);
        String wanted = withoutVersion(name);
        int cursor = 0;
        while (cursor < contents.length) {
            int sectorOffset = cursor % SECTOR_SIZE;
            int recordLength = contents[cursor] & 0xFF;
            if (recordLength == 0) {
                cursor += SECTOR_SIZE - sectorOffset;
                continue;
            }
            if (recordLength < 34 || cursor + recordLength > contents.length) {
                throw new IOException("ISO9660 directory record is malformed");
            }
            DirectoryEntry entry = parseDirectoryEntry(contents, cursor, recordLength);
            if (entry != null && !entry.isSpecialName() && withoutVersion(entry.name).equalsIgnoreCase(wanted)) {
                return entry;
            }
            cursor += recordLength;
        }
        return null;
    }

    private static DirectoryEntry parseDirectoryEntry(byte[] data, int offset, int recordLength) throws IOException {
        if (recordLength < 34 || offset < 0 || offset > data.length - recordLength) {
            throw new IOException("ISO9660 directory entry is truncated");
        }
        int nameLength = data[offset + 32] & 0xFF;
        if (33 + nameLength > recordLength) throw new IOException("ISO9660 identifier is truncated");
        String name = nameLength == 1 && (data[offset + 33] == 0 || data[offset + 33] == 1)
                ? (data[offset + 33] == 0 ? "." : "..")
                : new String(data, offset + 33, nameLength, StandardCharsets.US_ASCII);
        long extent = readLe32(data, offset + 2);
        long size = readLe32(data, offset + 10);
        int flags = data[offset + 25] & 0xFF;
        if (extent > Integer.MAX_VALUE) throw new IOException("ISO9660 extent is too large");
        return new DirectoryEntry(name, (int) extent, size, (flags & 2) != 0);
    }

    private static byte[] readFile(Ps1DiscImage disc, DirectoryEntry file, int maxSize) throws IOException {
        if (file.size < 0 || file.size > maxSize || file.size > Integer.MAX_VALUE) {
            throw new IOException("ISO9660 file exceeds the supported read size");
        }
        int length = (int) file.size;
        byte[] result = new byte[length];
        byte[] sector = new byte[SECTOR_SIZE];
        int copied = 0;
        int lba = file.extent;
        while (copied < length) {
            readSector(disc, lba++, sector);
            int amount = Math.min(SECTOR_SIZE, length - copied);
            System.arraycopy(sector, 0, result, copied, amount);
            copied += amount;
        }
        return result;
    }

    private static void readSector(Ps1DiscImage disc, int lba, byte[] sector) throws IOException {
        byte[] payload = new byte[2324];
        int bytes = disc.readUserDataSectorAtLba(lba, payload, 0);
        if (bytes < SECTOR_SIZE) throw new IOException("sector has less than 2048 bytes of ISO data");
        System.arraycopy(payload, 0, sector, 0, SECTOR_SIZE);
    }

    private static String parseBootPath(String config) {
        for (String line : config.split("\\r?\\n")) {
            int equals = line.indexOf('=');
            if (equals < 0 || !line.substring(0, equals).trim().equalsIgnoreCase("BOOT")) continue;
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static String withoutVersion(String name) {
        String trimmed = name.trim();
        int version = trimmed.lastIndexOf(';');
        if (version >= 0) trimmed = trimmed.substring(0, version);
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static long readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
    }

    public static final class BootExecutable {
        public final String path;
        private final byte[] image;
        BootExecutable(String path, byte[] image) { this.path = path; this.image = image; }
        public byte[] copyImage() { return image.clone(); }
        public int getSize() { return image.length; }
    }

    private static final class DirectoryEntry {
        final String name;
        final int extent;
        final long size;
        final boolean directory;
        DirectoryEntry(String name, int extent, long size, boolean directory) {
            this.name = name;
            this.extent = extent;
            this.size = size;
            this.directory = directory;
        }
        boolean isDirectory() { return directory; }
        boolean isSpecialName() { return name.equals(".") || name.equals(".."); }
    }
}
