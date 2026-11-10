package com.db.storage;

import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.exception.StorageException;
import com.db.model.VectorEntry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Файловое хранение коллекций.
 *
 * <pre>
 * dataDirectory/
 * └── &lt;collection&gt;/
 *     ├── meta.properties   formatVersion, name, dimension, metric, maxVectors
 *     └── vectors.bin       бинарные записи + CRC32
 * </pre>
 *
 * Формат vectors.bin (big-endian, {@link DataOutputStream}):
 * <pre>
 * int    MAGIC ("VDB1")
 * int    formatVersion
 * int    dimension
 * int    count
 * count × { int idLen, byte[idLen] id(UTF-8), double[dimension] vector, int dataLen (-1 = null), byte[dataLen] data }
 * long   CRC32 всего, что выше
 * </pre>
 *
 * Каждый файл пишется во временный, сбрасывается на диск (fsync) и атомарно переименовывается —
 * падение процесса посреди сохранения не портит предыдущую версию.
 */
public final class CollectionFileStore {

    public static final int FORMAT_VERSION = 1;
    static final String META_FILE = "meta.properties";
    static final String VECTORS_FILE = "vectors.bin";
    private static final int MAGIC = 0x56444231; // "VDB1"

    private final Path root;

    public CollectionFileStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public record StoredCollection(CollectionConfig config, List<VectorEntry> entries) {
    }

    // ------------------------------------------------------------------ write

    public void write(CollectionConfig config, List<VectorEntry> entries) throws IOException {
        Path dir = root.resolve(config.name());
        Files.createDirectories(dir);
        // сначала данные, потом meta: meta появляется только для полностью записанной коллекции
        writeAtomically(dir.resolve(VECTORS_FILE), out -> writeVectors(out, config, entries));
        writeAtomically(dir.resolve(META_FILE), out -> writeMeta(out, config));
    }

    public void delete(String collectionName) throws IOException {
        Path dir = root.resolve(CollectionConfig.validateName(collectionName));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void writeMeta(OutputStream out, CollectionConfig c) throws IOException {
        Properties p = new Properties();
        p.setProperty("formatVersion", Integer.toString(FORMAT_VERSION));
        p.setProperty("name", c.name());
        p.setProperty("dimension", Integer.toString(c.dimension()));
        p.setProperty("metric", c.metric().name());
        p.setProperty("maxVectors", Integer.toString(c.maxVectors()));
        Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
        p.store(w, "vecDB collection metadata");
        w.flush();
    }

    private static void writeVectors(OutputStream raw, CollectionConfig c, List<VectorEntry> entries)
            throws IOException {
        CRC32 crc = new CRC32();
        DataOutputStream out = new DataOutputStream(
                new CheckedOutputStream(new BufferedOutputStream(raw, 1 << 16), crc));
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(c.dimension());
        out.writeInt(entries.size());
        for (VectorEntry e : entries) {
            byte[] id = e.id().getBytes(StandardCharsets.UTF_8);
            out.writeInt(id.length);
            out.write(id);
            for (double x : e.vector()) {
                out.writeDouble(x);
            }
            if (e.data() == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(e.data().length);
                out.write(e.data());
            }
        }
        out.flush();
        long checksum = crc.getValue();
        new DataOutputStream(raw).writeLong(checksum); // сама сумма в CRC не входит
        raw.flush();
    }

    @FunctionalInterface
    private interface StreamWriter {
        void write(OutputStream out) throws IOException;
    }

    private static void writeAtomically(Path target, StreamWriter writer) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream out = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch))) {
            writer.write(out);
            out.flush();
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------- read

    /** Читает все коллекции из {@code root}. Каталоги без meta.properties игнорируются. */
    public List<StoredCollection> readAll() throws IOException {
        List<StoredCollection> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (Files.exists(dir.resolve(META_FILE))) {
                    result.add(read(dir));
                }
            }
        }
        result.sort(Comparator.comparing(sc -> sc.config().name()));
        return result;
    }

    StoredCollection read(Path dir) throws IOException {
        CollectionConfig config = readMeta(dir.resolve(META_FILE));
        if (!config.name().equals(dir.getFileName().toString())) {
            throw new StorageException("collection name '" + config.name()
                    + "' does not match directory " + dir);
        }
        Path vectors = dir.resolve(VECTORS_FILE);
        List<VectorEntry> entries = Files.exists(vectors) ? readVectors(vectors, config) : List.of();
        return new StoredCollection(config, entries);
    }

    private static CollectionConfig readMeta(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        try {
            int version = Integer.parseInt(required(p, "formatVersion", file));
            if (version != FORMAT_VERSION) {
                throw new StorageException("unsupported formatVersion " + version + " in " + file
                        + " (expected " + FORMAT_VERSION + ")");
            }
            return new CollectionConfig(
                    required(p, "name", file),
                    Integer.parseInt(required(p, "dimension", file)),
                    DistanceMetric.valueOf(required(p, "metric", file)),
                    Integer.parseInt(p.getProperty("maxVectors", "0")));
        } catch (IllegalArgumentException e) { // NumberFormatException, неизвестная метрика, невалидный конфиг
            throw new StorageException("corrupted metadata in " + file + ": " + e.getMessage(), e);
        }
    }

    private static String required(Properties p, String key, Path file) {
        String v = p.getProperty(key);
        if (v == null) {
            throw new StorageException("missing '" + key + "' in " + file);
        }
        return v;
    }

    private static List<VectorEntry> readVectors(Path file, CollectionConfig config) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            DataInputStream in = new DataInputStream(new CheckedInputStream(raw, crc));
            try {
                if (in.readInt() != MAGIC) {
                    throw new StorageException("not a vecDB file: " + file);
                }
                int version = in.readInt();
                if (version != FORMAT_VERSION) {
                    throw new StorageException("unsupported formatVersion " + version + " in " + file);
                }
                int dim = in.readInt();
                if (dim != config.dimension()) {
                    throw new StorageException("dimension in " + file + " is " + dim
                            + ", meta says " + config.dimension());
                }
                int count = in.readInt();
                if (count < 0) {
                    throw new StorageException("negative record count in " + file);
                }
                List<VectorEntry> entries = new ArrayList<>(Math.min(count, 1 << 20));
                for (int i = 0; i < count; i++) {
                    String id = new String(readBytes(in, in.readInt(), file), StandardCharsets.UTF_8);
                    double[] v = new double[dim];
                    for (int j = 0; j < dim; j++) {
                        v[j] = in.readDouble();
                    }
                    int dataLen = in.readInt();
                    byte[] data = dataLen == -1 ? null : readBytes(in, dataLen, file);
                    entries.add(new VectorEntry(id, v, data));
                }
                long expected = crc.getValue();
                long actual = new DataInputStream(raw).readLong();
                if (expected != actual) {
                    throw new StorageException("checksum mismatch in " + file + " — file is corrupted");
                }
                return entries;
            } catch (EOFException e) {
                throw new StorageException("unexpected end of file " + file + " — file is truncated", e);
            }
        }
    }

    private static byte[] readBytes(DataInputStream in, int len, Path file) throws IOException {
        if (len < 0) {
            throw new StorageException("negative length in " + file);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }
}
