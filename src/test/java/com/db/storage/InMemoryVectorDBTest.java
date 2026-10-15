package com.db.storage;

import com.db.api.ICollection;
import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.config.DistanceMetric;
import com.db.exception.CollectionAlreadyExistsException;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.DatabaseClosedException;
import com.db.exception.StorageException;
import com.db.model.DbStats;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryVectorDBTest {

    @TempDir
    Path dir;

    private InMemoryVectorDB db;

    private DbConfig config() {
        return DbConfig.builder().dataDirectory(dir.toString()).autoSave(false).build();
    }

    private InMemoryVectorDB open() {
        db = InMemoryVectorDB.open(config());
        return db;
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static VectorEntry e(String id, String payload, double... v) {
        return new VectorEntry(id, v, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ collections

    @Test
    void createGetListDrop() {
        open();
        ICollection docs = db.createCollection("docs", 3, DistanceMetric.COSINE);
        db.createCollection(CollectionConfig.builder("img", 2).metric(DistanceMetric.EUCLIDEAN).build());
        assertEquals(List.of("docs", "img"), db.listCollections());
        assertTrue(db.getCollection("docs") == docs);
        assertTrue(db.hasCollection("img"));
        assertTrue(db.findCollection("nope").isEmpty());
        assertThrows(CollectionNotFoundException.class, () -> db.getCollection("nope"));
        assertThrows(CollectionAlreadyExistsException.class, () -> db.createCollection("docs", 5, DistanceMetric.COSINE));

        assertTrue(db.dropCollection("img"));
        assertFalse(db.dropCollection("img"));
        assertEquals(List.of("docs"), db.listCollections());
    }

    @Test
    void collectionsAreIsolated() {
        open();
        ICollection a = db.createCollection("a", 2, DistanceMetric.EUCLIDEAN);
        ICollection b = db.createCollection("b", 4, DistanceMetric.COSINE);
        a.put(e("x", null, 1, 1));
        b.put(e("x", null, 1, 2, 3, 4));
        assertEquals(2, a.getById("x").orElseThrow().dimension());
        assertEquals(4, b.getById("x").orElseThrow().dimension());
        a.delete("x");
        assertTrue(b.exists("x"));
    }

    @Test
    void droppedCollectionHandleIsDead() {
        open();
        ICollection c = db.createCollection("tmp", 1, DistanceMetric.EUCLIDEAN);
        db.dropCollection("tmp");
        assertThrows(CollectionNotFoundException.class, () -> c.put(e("a", null, 1)));
        // можно создать заново с другой схемой
        assertEquals(5, db.createCollection("tmp", 5, DistanceMetric.COSINE).dimension());
    }

    @Test
    void statsAggregateAllCollections() {
        open();
        db.createCollection("a", 1, DistanceMetric.EUCLIDEAN).put(e("1", null, 1));
        ICollection b = db.createCollection("b", 1, DistanceMetric.EUCLIDEAN);
        b.put(e("1", null, 1));
        b.put(e("2", null, 2));
        DbStats s = db.stats();
        assertEquals(2, s.collections());
        assertEquals(3, s.liveVectors());
        assertEquals(3, s.totalPuts());
        assertEquals(2, s.perCollection().get("b").liveVectors());
        assertEquals(InMemoryVectorDB.ENGINE, s.engine());
    }

    // ------------------------------------------------------------ persistence

    @Test
    void saveAndReopenRestoresEverything() throws IOException {
        open();
        ICollection docs = db.createCollection(CollectionConfig.builder("docs", 3)
                .metric(DistanceMetric.DOT_PRODUCT).maxVectors(100).build());
        docs.put(e("a", "привет", 1, 2, 3));
        docs.put(e("b", null, -1, 0.5, 1e-300));
        db.createCollection("empty", 7, DistanceMetric.COSINE);
        db.save();
        assertNotNull(db.stats().lastSaveAt());
        db.close();

        open();
        assertEquals(List.of("docs", "empty"), db.listCollections());
        ICollection restored = db.getCollection("docs");
        assertEquals(CollectionConfig.builder("docs", 3).metric(DistanceMetric.DOT_PRODUCT).maxVectors(100).build(),
                restored.config());
        VectorEntry a = restored.getById("a").orElseThrow();
        assertArrayEquals(new double[]{1, 2, 3}, a.vector());
        assertEquals("привет", new String(a.data(), StandardCharsets.UTF_8));
        VectorEntry b = restored.getById("b").orElseThrow();
        assertEquals(null, b.data());
        assertEquals(1e-300, b.vector()[2], 0);
        assertEquals(0, db.getCollection("empty").size());
        assertEquals("a", restored.getNearest(new double[]{1, 2, 3}, 1).get(0).entry().id());
    }

    @Test
    void closeSavesAutomatically() {
        open();
        db.createCollection("c", 1, DistanceMetric.EUCLIDEAN).put(e("a", null, 1));
        db.close();
        open();
        assertTrue(db.getCollection("c").exists("a"));
    }

    @Test
    void dropDeletesFilesOnDisk() throws IOException {
        open();
        db.createCollection("gone", 1, DistanceMetric.EUCLIDEAN).put(e("a", null, 1));
        db.save();
        assertTrue(Files.exists(dir.resolve("gone").resolve("vectors.bin")));
        db.dropCollection("gone");
        assertFalse(Files.exists(dir.resolve("gone")));
        db.close();
        open();
        assertFalse(db.hasCollection("gone"));
    }

    @Test
    void loadDiscardsUnsavedChanges() throws IOException {
        open();
        ICollection c = db.createCollection("c", 1, DistanceMetric.EUCLIDEAN);
        c.put(e("saved", null, 1));
        db.save();
        c.put(e("unsaved", null, 2));
        db.createCollection("new", 1, DistanceMetric.EUCLIDEAN);
        db.load();
        assertEquals(List.of("c"), db.listCollections());
        assertTrue(db.getCollection("c").exists("saved"));
        assertFalse(db.getCollection("c").exists("unsaved"));
    }

    @Test
    void unchangedCollectionsAreNotRewritten() throws Exception {
        open();
        db.createCollection("c", 1, DistanceMetric.EUCLIDEAN).put(e("a", null, 1));
        db.save();
        Path file = dir.resolve("c").resolve("vectors.bin");
        var before = Files.getLastModifiedTime(file);
        Thread.sleep(20);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(before.toMillis() - 10_000));
        var marked = Files.getLastModifiedTime(file);
        db.save();
        assertEquals(marked, Files.getLastModifiedTime(file));
        db.getCollection("c").put(e("b", null, 2));
        db.save();
        assertFalse(marked.equals(Files.getLastModifiedTime(file)));
    }

    @Test
    void corruptedFileDetected() throws IOException {
        open();
        db.createCollection("c", 2, DistanceMetric.EUCLIDEAN).put(e("a", "data", 1, 2));
        db.close();
        db = null;
        Path file = dir.resolve("c").resolve("vectors.bin");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(30);
            int b = raf.read();
            raf.seek(30);
            raf.write(b ^ 0xFF);
        }
        StorageException ex = assertThrows(StorageException.class, () -> InMemoryVectorDB.open(config()));
        assertTrue(ex.getMessage().contains("checksum"), ex.getMessage());
    }

    @Test
    void truncatedFileDetected() throws IOException {
        open();
        db.createCollection("c", 2, DistanceMetric.EUCLIDEAN).put(e("a", "data", 1, 2));
        db.close();
        db = null;
        Path file = dir.resolve("c").resolve("vectors.bin");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(raf.length() - 5);
        }
        assertThrows(StorageException.class, () -> InMemoryVectorDB.open(config()));
    }

    @Test
    void unsupportedFormatVersionRejected() throws IOException {
        open();
        db.createCollection("c", 1, DistanceMetric.EUCLIDEAN);
        db.close();
        db = null;
        Path meta = dir.resolve("c").resolve("meta.properties");
        Files.writeString(meta, Files.readString(meta).replace("formatVersion=1", "formatVersion=99"));
        StorageException ex = assertThrows(StorageException.class, () -> InMemoryVectorDB.open(config()));
        assertTrue(ex.getMessage().contains("formatVersion"), ex.getMessage());
    }

    @Test
    void foreignDirectoriesIgnored() throws IOException {
        Files.createDirectories(dir.resolve("not-a-collection"));
        Files.writeString(dir.resolve("readme.txt"), "hi");
        open();
        assertTrue(db.listCollections().isEmpty());
    }

    @Test
    void autosaveWritesInBackground() throws Exception {
        db = InMemoryVectorDB.open(DbConfig.builder().dataDirectory(dir.toString())
                .saveInterval(Duration.ofMillis(50)).build());
        db.createCollection("c", 1, DistanceMetric.EUCLIDEAN).put(e("a", null, 1));
        Path meta = dir.resolve("c").resolve("meta.properties");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(meta) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(meta), "autosave did not write the collection");
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    void closedDatabaseRejectsOperations() {
        open();
        ICollection c = db.createCollection("c", 1, DistanceMetric.EUCLIDEAN);
        db.close();
        assertTrue(db.isClosed());
        db.close(); // идемпотентно
        assertThrows(DatabaseClosedException.class, () -> db.createCollection("x", 1, DistanceMetric.COSINE));
        assertThrows(DatabaseClosedException.class, () -> db.listCollections());
        assertThrows(DatabaseClosedException.class, () -> c.put(e("a", null, 1)));
        assertThrows(DatabaseClosedException.class, () -> db.save());
        db = null;
    }
}
