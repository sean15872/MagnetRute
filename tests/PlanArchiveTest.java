import at.andi.magnetrute.PlanArchive;
import at.andi.magnetrute.PlanArchive.Area;
import at.andi.magnetrute.PlanArchive.Document;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** JVM archive tests use marker-only JPEG fixtures; Android verifies image decoding separately. */
public final class PlanArchiveTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String ID2 = "abcdefab-1234-1234-1234-123456789abc";
    private static final String IMAGE_PATH = "images/" + ID + ".jpg";
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
    private static int checks;

    private interface Failing { void run() throws Exception; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void fails(Failing action, String message) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException: " + message);
    }
    private static Area area(String id, String floor, String name, double scale, byte[] image) {
        return new Area(id, floor, name, 1200, 800, scale, image);
    }
    private static Document document() {
        return new Document(Arrays.asList(area(ID, "Erdgeschoss – Süd", "Küche ☕", .025, JPEG),
                area(ID2, "Dach / Nord", "Bereich 2", 0, JPEG)));
    }
    private static byte[] write(Document document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanArchive.write(document, out);
        return out.toByteArray();
    }
    private static Document read(byte[] bytes) throws IOException {
        return PlanArchive.read(new ByteArrayInputStream(bytes));
    }
    private static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
                result.put(entry.getName(), out.toByteArray());
            }
        }
        return result;
    }
    private static byte[] zip(Map<String, byte[]> entries, boolean stored) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(item.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static byte[] replaceMetadata(byte[] archive, String oldText, String newText) throws IOException {
        Map<String, byte[]> entries = entries(archive);
        String manifest = new String(entries.get("manifest.properties"), StandardCharsets.UTF_8);
        if (!manifest.contains(oldText)) throw new AssertionError("Fixture property absent: " + oldText);
        entries.put("manifest.properties", manifest.replace(oldText, newText).getBytes(StandardCharsets.UTF_8));
        return zip(entries, false);
    }

    public static void main(String[] args) throws Exception {
        byte[] archive = write(document());
        Document restored = read(archive);
        check(restored.areas.size() == 2, "all areas restored");
        Area first = restored.areas.get(0);
        check(first.id.equals(ID), "stable ID");
        check(first.floor.equals("Erdgeschoss – Süd") && first.name.equals("Küche ☕"), "Unicode floor/area names");
        check(first.width == 1200 && first.height == 800, "image dimensions");
        check(first.metersPerPixel == .025 && restored.areas.get(1).metersPerPixel == 0, "scale and uncalibrated state");
        check(Arrays.equals(first.image, JPEG), "JPEG bytes preserved");
        check(read(write(new Document(Collections.emptyList()))).areas.isEmpty(), "empty project roundtrip");
        try { restored.areas.clear(); throw new AssertionError("mutable area list"); }
        catch (UnsupportedOperationException expected) { checks++; }

        fails(() -> read(new byte[0]), "empty input");
        fails(() -> read(Arrays.copyOf(archive, archive.length - 1)), "truncated end record");
        fails(() -> read(Arrays.copyOf(archive, archive.length / 2)), "truncated compressed contents");
        fails(() -> read(replaceMetadata(archive, "version=1", "version=2")), "future schema version");
        fails(() -> read(replaceMetadata(archive, "count=2", "count=31")), "excess count");
        fails(() -> read(replaceMetadata(archive, "count=2", "count=2\ncount=2")), "duplicate property");
        fails(() -> read(replaceMetadata(archive, "area.0.width=1200", "area.0.width=2401")), "oversized dimensions");
        fails(() -> read(replaceMetadata(archive, "area.0.metersPerPixel=0.025", "area.0.metersPerPixel=NaN")), "NaN scale");
        fails(() -> read(replaceMetadata(archive, "area.0.metersPerPixel=0.025", "area.0.metersPerPixel=-1")), "negative scale");
        fails(() -> read(replaceMetadata(archive, "area.1.id=" + ID2, "area.1.id=" + ID)), "duplicate ID");
        Map<String, byte[]> missing = entries(archive); missing.remove(IMAGE_PATH);
        fails(() -> read(zip(missing, false)), "missing image");
        Map<String, byte[]> extra = entries(archive); extra.put("../../outside.txt", JPEG);
        fails(() -> read(zip(extra, false)), "ZIP traversal path");
        Map<String, byte[]> extraImage = entries(archive);
        extraImage.put("images/00000000-1234-1234-1234-123456789abc.jpg", JPEG);
        fails(() -> read(zip(extraImage, false)), "unreferenced image");
        Map<String, byte[]> badImage = entries(archive); badImage.put(IMAGE_PATH, new byte[]{1, 2, 3, 4});
        fails(() -> read(zip(badImage, false)), "invalid JPEG markers");
        Map<String, byte[]> bomb = entries(archive); bomb.put(IMAGE_PATH, new byte[PlanArchive.MAX_IMAGE_BYTES + 1]);
        fails(() -> read(zip(bomb, false)), "compressed image exceeds decompressed limit");
        byte[] corrupt = zip(entries(archive), true);
        for (int i = 0; i < corrupt.length - JPEG.length; i++) {
            if (corrupt[i] == JPEG[0] && corrupt[i + 1] == JPEG[1] && corrupt[i + 2] == 1) {
                corrupt[i + 2] = 99; break;
            }
        }
        fails(() -> read(corrupt), "CRC corruption");

        fails(() -> write(new Document(Arrays.asList(first, first))), "duplicate ID on write");
        fails(() -> write(new Document(Collections.singletonList(area(ID, "  EG", "Zimmer", 0, JPEG)))), "untrimmed floor");
        fails(() -> write(new Document(Collections.singletonList(area(ID, "EG", "x\ny", 0, JPEG)))), "control characters");
        fails(() -> write(new Document(Collections.singletonList(area("../x", "EG", "Zimmer", 0, JPEG)))), "invalid ID");
        fails(() -> write(new Document(Collections.singletonList(area(ID, "EG", "Zimmer", Double.POSITIVE_INFINITY, JPEG)))), "infinite scale");
        fails(() -> write(new Document(Collections.nCopies(31, first))), "area count limit");
        byte[] mutable = JPEG.clone(); Area copied = area(ID, "EG", "Zimmer", 0, mutable); mutable[0] = 0;
        check(copied.image[0] == (byte) 0xff, "image bytes copied on construction");

        File directory = Files.createTempDirectory("magnetrute-archive-test").toFile();
        File file = new File(directory, "plans.zip");
        try {
            check(PlanArchive.load(file).areas.isEmpty(), "missing file yields empty project");
            PlanArchive.save(document(), file);
            check(PlanArchive.load(file).areas.size() == 2, "atomic file save/load");
            byte[] before = Files.readAllBytes(file.toPath());
            fails(() -> PlanArchive.save(new Document(Collections.singletonList(area(ID, "EG", "", 0, JPEG))), file),
                    "invalid update rejected");
            check(Arrays.equals(before, Files.readAllBytes(file.toPath())), "failed save preserves existing archive");
            check(directory.list().length == 1, "no temporary files remain");
            Files.write(file.toPath(), new byte[]{1, 2, 3});
            fails(() -> PlanArchive.load(file), "corrupt saved file is not silently empty");
        } finally {
            Files.deleteIfExists(file.toPath()); Files.deleteIfExists(directory.toPath());
        }
        System.out.println("PlanArchive: " + checks + " checks OK");
    }
}
