package com.db.storage;

import com.db.api.ICollection;
import com.db.api.IVectorDB;
import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.exception.CollectionAlreadyExistsException;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.DatabaseClosedException;
import com.db.exception.StorageException;
import com.db.model.CollectionStats;
import com.db.model.DbStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Локальная векторная БД: коллекции в памяти + сохранение на диск в {@code dataDirectory}.
 *
 * <pre>
 * try (InMemoryVectorDB db = InMemoryVectorDB.open(config)) {   // создаёт каталог и загружает данные
 *     ICollection docs = db.createCollection("docs", 384, DistanceMetric.COSINE);
 *     ...
 * }                                                            // close() = финальный save()
 * </pre>
 *
 * Блокировки:
 * <ul>
 *   <li>{@code schemaLock} — write для create/drop/load/close, read для save: схема не меняется во время сохранения;</li>
 *   <li>{@code saveLock} — ручной save() и автосохранение не выполняются одновременно;</li>
 *   <li>операции с векторами блокируют только свою коллекцию.</li>
 * </ul>
 */
public final class InMemoryVectorDB implements IVectorDB {

    public static final String ENGINE = "in-memory/brute-force";

    private final DbConfig config;
    private final CollectionFileStore store;
    private final ConcurrentHashMap<String, InMemoryCollection> collections = new ConcurrentHashMap<>();
    /** Версия коллекции, которая сейчас лежит на диске; нет ключа — ещё не сохранялась. */
    private final ConcurrentHashMap<String, Long> persistedVersions = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock schemaLock = new ReentrantReadWriteLock();
    private final ReentrantLock saveLock = new ReentrantLock();
    private final ScheduledExecutorService autosave;

    private volatile boolean closed;
    private volatile Instant lastSaveAt;
    private volatile long lastSaveDurationMs;

    private InMemoryVectorDB(DbConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = new CollectionFileStore(config.dataPath());
        this.autosave = config.autoSave() ? startAutosave() : null;
    }

    /** Открывает БД: создаёт {@code dataDirectory} при необходимости и загружает сохранённые коллекции. */
    public static InMemoryVectorDB open(DbConfig config) {
        try {
            Files.createDirectories(config.dataPath());
        } catch (IOException e) {
            throw new StorageException("cannot create data directory " + config.dataPath(), e);
        }
        InMemoryVectorDB db = new InMemoryVectorDB(config);
        try {
            db.load();
        } catch (IOException | RuntimeException e) {
            db.shutdownAutosave();
            if (e instanceof RuntimeException re) throw re;
            throw new StorageException("cannot load data from " + config.dataPath(), e);
        }
        return db;
    }

    public DbConfig config() {
        return config;
    }

    // ------------------------------------------------------------- IDatabase

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DbStats stats() {
        Map<String, CollectionStats> per = new LinkedHashMap<>();
        for (String name : sortedNames()) {
            InMemoryCollection c = collections.get(name);
            if (c != null) {
                per.put(name, c.stats());
            }
        }
        long live = 0, puts = 0, searches = 0, hits = 0, misses = 0, evicted = 0;
        for (CollectionStats s : per.values()) {
            live += s.liveVectors();
            puts += s.totalPuts();
            searches += s.totalSearches();
            hits += s.hits();
            misses += s.misses();
            evicted += s.evictedVectors();
        }
        return new DbStats(ENGINE, per.size(), live, puts, searches, hits, misses, evicted,
                lastSaveAt, lastSaveDurationMs, per);
    }

    /** Останавливает автосохранение, сохраняет изменения на диск. Повторный вызов ничего не делает. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        shutdownAutosave();
        saveLock.lock();
        schemaLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            try {
                saveChanged();
            } finally {
                for (InMemoryCollection c : collections.values()) {
                    c.markClosed(config.name());
                }
            }
        } catch (IOException e) {
            throw new StorageException("final save on close failed", e);
        } finally {
            schemaLock.writeLock().unlock();
            saveLock.unlock();
        }
    }

    // ---------------------------------------------------- ICollectionManager

    @Override
    public ICollection createCollection(CollectionConfig cfg) {
        Objects.requireNonNull(cfg, "config");
        schemaLock.writeLock().lock();
        try {
            ensureOpen();
            InMemoryCollection created = new InMemoryCollection(cfg);
            if (collections.putIfAbsent(cfg.name(), created) != null) {
                throw new CollectionAlreadyExistsException(cfg.name());
            }
            persistedVersions.remove(cfg.name());
            return created;
        } finally {
            schemaLock.writeLock().unlock();
        }
    }

    @Override
    public ICollection getCollection(String name) {
        return findCollection(name).orElseThrow(() -> new CollectionNotFoundException(name));
    }

    @Override
    public Optional<ICollection> findCollection(String name) {
        ensureOpen();
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(collections.get(name));
    }

    @Override
    public boolean dropCollection(String name) {
        schemaLock.writeLock().lock();
        try {
            ensureOpen();
            InMemoryCollection removed = name == null ? null : collections.remove(name);
            if (removed == null) {
                return false;
            }
            removed.markDropped();
            persistedVersions.remove(name);
            try {
                store.delete(name);
            } catch (IOException e) {
                throw new StorageException("collection '" + name + "' dropped in memory, "
                        + "but its files could not be deleted", e);
            }
            return true;
        } finally {
            schemaLock.writeLock().unlock();
        }
    }

    @Override
    public List<String> listCollections() {
        ensureOpen();
        return sortedNames();
    }

    // ---------------------------------------------------------- IPersistable

    /** Записывает на диск коллекции, изменённые с прошлого сохранения. */
    @Override
    public void save() throws IOException {
        ensureOpen();
        saveLock.lock();
        schemaLock.readLock().lock();
        try {
            ensureOpen();
            saveChanged();
        } finally {
            schemaLock.readLock().unlock();
            saveLock.unlock();
        }
    }

    /** Заменяет содержимое памяти данными с диска; несохранённые изменения теряются. */
    @Override
    public void load() throws IOException {
        ensureOpen();
        saveLock.lock();
        schemaLock.writeLock().lock();
        try {
            ensureOpen();
            List<CollectionFileStore.StoredCollection> stored = store.readAll();
            for (InMemoryCollection old : collections.values()) {
                old.markDropped();
            }
            collections.clear();
            persistedVersions.clear();
            for (CollectionFileStore.StoredCollection sc : stored) {
                InMemoryCollection c = new InMemoryCollection(sc.config());
                c.restore(sc.entries());
                collections.put(sc.config().name(), c);
                persistedVersions.put(sc.config().name(), c.version());
            }
        } finally {
            schemaLock.writeLock().unlock();
            saveLock.unlock();
        }
    }

    // ---------------------------------------------------------------- internal

    /** Вызывать под saveLock и schemaLock (read или write). */
    private void saveChanged() throws IOException {
        long started = System.nanoTime();
        for (Map.Entry<String, InMemoryCollection> e : collections.entrySet()) {
            InMemoryCollection.Snapshot snap = e.getValue().snapshot();
            Long persisted = persistedVersions.get(e.getKey());
            if (persisted != null && persisted == snap.version()) {
                continue;
            }
            store.write(e.getValue().config(), snap.entries());
            persistedVersions.put(e.getKey(), snap.version());
        }
        lastSaveDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        lastSaveAt = Instant.now();
    }

    private ScheduledExecutorService startAutosave() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vecdb-autosave-" + config.name());
            t.setDaemon(true);
            return t;
        });
        long period = config.saveInterval().toMillis();
        ex.scheduleWithFixedDelay(this::autosaveTick, period, period, TimeUnit.MILLISECONDS);
        return ex;
    }

    private void autosaveTick() {
        if (closed) {
            return;
        }
        try {
            save();
        } catch (DatabaseClosedException ignored) {
            // гонка с close() — финальное сохранение сделает close()
        } catch (IOException | UncheckedIOException | StorageException e) {
            System.err.println("[vecdb] autosave failed: " + e.getMessage());
        }
    }

    private void shutdownAutosave() {
        if (autosave == null) {
            return;
        }
        autosave.shutdown();
        try {
            if (!autosave.awaitTermination(10, TimeUnit.SECONDS)) {
                autosave.shutdownNow();
            }
        } catch (InterruptedException e) {
            autosave.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private List<String> sortedNames() {
        List<String> names = new ArrayList<>(collections.keySet());
        Collections.sort(names);
        return names;
    }

    private void ensureOpen() {
        if (closed) {
            throw new DatabaseClosedException(config.name());
        }
    }
}
