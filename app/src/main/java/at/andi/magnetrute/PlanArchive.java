package at.andi.magnetrute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Versioned, bounded ZIP interchange; never extracts paths supplied by an archive. */
public final class PlanArchive {
    public static final int MAX_AREAS = 30;
    public static final int MAX_DIMENSION = 2400;
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 40 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final int MAX_ZIP_BYTES = MAX_TOTAL_BYTES + 1024 * 1024;

    private PlanArchive() { }

    public static final class Area {
        public final String id, floor, name;
        public final int width, height;
        /** Zero means uncalibrated; applies to the final rectified/cropped image. */
        public final double metersPerPixel;
        public final byte[] image;

        public Area(String id, String floor, String name, int width, int height, double metersPerPixel, byte[] image) {
            this.id = id;
            this.floor = floor;
            this.name = name;
            this.width = width;
            this.height = height;
            this.metersPerPixel = metersPerPixel;
            this.image = image == null ? null : image.clone();
        }
    }

    public static final class Document {
        public final List<Area> areas;

        public Document(List<Area> areas) {
            if (areas == null) throw new IllegalArgumentException("Bereiche fehlen");
            this.areas = Collections.unmodifiableList(new ArrayList<>(areas));
        }
    }

    public static Document load(File file) throws IOException {
        if (!file.exists()) return new Document(Collections.emptyList());
        try (InputStream in = new FileInputStream(file)) { return read(in); }
    }

    /** Writes and syncs a sibling temporary file before an atomic replacement. */
    public static void save(Document document, File file) throws IOException {
        validate(document);
        File destination = file.getAbsoluteFile();
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Speicherordner nicht verfügbar");
        }
        File temporary = File.createTempFile("plans-", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                write(document, out);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    public static void write(Document document, OutputStream output) throws IOException {
        validate(document);
        Properties manifest = new Properties();
        manifest.setProperty("format", "MagnetRute-Grundrisse");
        manifest.setProperty("version", "1");
        manifest.setProperty("count", Integer.toString(document.areas.size()));
        for (int i = 0; i < document.areas.size(); i++) {
            Area area = document.areas.get(i);
            String key = "area." + i + ".";
            manifest.setProperty(key + "id", area.id);
            manifest.setProperty(key + "floor", area.floor);
            manifest.setProperty(key + "name", area.name);
            manifest.setProperty(key + "width", Integer.toString(area.width));
            manifest.setProperty(key + "height", Integer.toString(area.height));
            manifest.setProperty(key + "metersPerPixel", Double.toString(area.metersPerPixel));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
        manifest.store(writer, "MagnetRute Grundrisse: schema 1");
        writer.flush();
        ZipOutputStream zip = new ZipOutputStream(output);
        put(zip, "manifest.properties", bytes.toByteArray());
        for (Area area : document.areas) put(zip, imagePath(area.id), area.image);
        zip.finish();
        zip.flush();
    }

    public static Document read(InputStream input) throws IOException {
        byte[] archive = boundedRead(input, MAX_ZIP_BYTES);
        Set<String> centralNames = centralDirectory(archive);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!centralNames.contains(name) || entries.containsKey(name) || entry.isDirectory()) {
                    throw new IOException("Ungültiger oder doppelter ZIP-Eintrag");
                }
                int limit = "manifest.properties".equals(name) ? MAX_MANIFEST_BYTES : MAX_IMAGE_BYTES;
                byte[] data = boundedRead(zip, limit);
                total += data.length;
                if (total > (long) MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) throw new IOException("ZIP zu groß");
                entries.put(name, data);
                zip.closeEntry(); // Verifies CRC and truncated compressed data.
            }
        }
        if (!entries.keySet().equals(centralNames)) throw new IOException("Unvollständiges ZIP");
        byte[] metadata = entries.remove("manifest.properties");
        if (metadata == null) throw new IOException("Grundriss-Metadaten fehlen");
        Properties manifest = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Doppeltes Metadatenfeld");
                return super.put(key, value);
            }
        };
        try {
            manifest.load(new InputStreamReader(new ByteArrayInputStream(metadata), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) { throw new IOException("Ungültige Metadaten", e); }
        if (!"MagnetRute-Grundrisse".equals(manifest.getProperty("format"))
                || !"1".equals(manifest.getProperty("version"))) {
            throw new IOException("Unbekanntes Grundriss-ZIP-Format");
        }
        int count = integer(manifest.getProperty("count"));
        if (count < 0 || count > MAX_AREAS || manifest.size() != 3 + 6 * count) {
            throw new IOException("Ungültige Bereichsanzahl oder Metadaten");
        }
        List<Area> areas = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = "area." + i + ".";
            String id = manifest.getProperty(key + "id");
            double scale;
            try { scale = Double.parseDouble(manifest.getProperty(key + "metersPerPixel", "")); }
            catch (NumberFormatException e) { throw new IOException("Ungültiger Maßstab", e); }
            areas.add(new Area(id, manifest.getProperty(key + "floor"), manifest.getProperty(key + "name"),
                    integer(manifest.getProperty(key + "width")), integer(manifest.getProperty(key + "height")),
                    scale, entries.remove(imagePath(id))));
        }
        if (!entries.isEmpty()) throw new IOException("Unerwartete Dateien im Grundriss-ZIP");
        Document document = new Document(areas);
        validate(document);
        return document;
    }

    private static String imagePath(String id) { return "images/" + id + ".jpg"; }

    private static void validate(Document document) throws IOException {
        if (document == null || document.areas.size() > MAX_AREAS) throw new IOException("Zu viele Bereiche");
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (Area area : document.areas) {
            if (area == null || area.id == null
                    || !area.id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !ids.add(area.id)) throw new IOException("Ungültige oder doppelte Bereichs-ID");
            validateName(area.floor);
            validateName(area.name);
            if (area.width < 1 || area.width > MAX_DIMENSION || area.height < 1 || area.height > MAX_DIMENSION
                    || !Double.isFinite(area.metersPerPixel) || area.metersPerPixel < 0
                    || area.metersPerPixel > 1_000_000) throw new IOException("Ungültige Bildgröße oder Kalibrierung");
            byte[] image = area.image;
            if (image == null || image.length < 4 || image.length > MAX_IMAGE_BYTES
                    || (image[0] & 255) != 255 || (image[1] & 255) != 216
                    || (image[image.length - 2] & 255) != 255 || (image[image.length - 1] & 255) != 217) {
                throw new IOException("Bereich enthält kein gültiges JPEG");
            }
            total += image.length;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Grundrisse überschreiten 40 MiB");
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 120 || !name.equals(name.trim())) {
            throw new IOException("Ungültiger Stockwerks-/Bereichsname");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) throw new IOException("Ungültiger Stockwerks-/Bereichsname");
        }
    }

    private static int integer(String value) throws IOException {
        if (value == null || !value.matches("[0-9]{1,9}")) throw new IOException("Ungültige Zahl in Metadaten");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("Ungültige Zahl in Metadaten", e); }
    }

    private static byte[] boundedRead(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if ((long) data.size() + n > limit) throw new IOException("ZIP-Eintrag überschreitet Größenlimit");
            data.write(buffer, 0, n);
        }
        return data.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** Require a complete central directory/EOCD; ZipInputStream alone accepts truncated ZIP tails. */
    private static Set<String> centralDirectory(byte[] data) throws IOException {
        int eocd = data.length - 22;
        if (eocd < 0 || u32(data, eocd) != 0x06054b50L || u16(data, eocd + 20) != 0
                || u16(data, eocd + 4) != 0 || u16(data, eocd + 6) != 0) throw new IOException("ZIP unvollständig");
        int count = u16(data, eocd + 10);
        long size = u32(data, eocd + 12), offset = u32(data, eocd + 16);
        if (count != u16(data, eocd + 8) || count < 1 || count > MAX_AREAS + 1
                || offset + size != eocd || offset > eocd) throw new IOException("Ungültiges ZIP-Verzeichnis");
        Set<String> names = new HashSet<>();
        int p = (int) offset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > eocd || u32(data, p) != 0x02014b50L) throw new IOException("ZIP-Verzeichnis beschädigt");
            int length = u16(data, p + 28), extra = u16(data, p + 30), comment = u16(data, p + 32);
            long end = (long) p + 46 + length + extra + comment;
            if (end > eocd || u16(data, p + 34) != 0 || u32(data, p + 42) >= offset
                    || (u16(data, p + 8) & 1) != 0) throw new IOException("ZIP-Verzeichnis beschädigt");
            String name = new String(data, p + 46, length, StandardCharsets.UTF_8);
            if (!("manifest.properties".equals(name)
                    || name.matches("images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"))
                    || !names.add(name)) throw new IOException("Unerwarteter oder doppelter ZIP-Pfad");
            p = (int) end;
        }
        if (p != eocd) throw new IOException("ZIP-Verzeichnis beschädigt");
        return names;
    }

    private static int u16(byte[] data, int i) { return (data[i] & 255) | ((data[i + 1] & 255) << 8); }
    private static long u32(byte[] data, int i) { return (long) u16(data, i) | ((long) u16(data, i + 2) << 16); }
}
