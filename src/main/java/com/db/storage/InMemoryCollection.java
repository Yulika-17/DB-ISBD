package com.db.storage;

import com.db.api.ICollection;
import com.db.api.IIndex;
import com.db.config.CollectionConfig;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.DatabaseClosedException;
import com.db.exception.DimensionMismatchException;
import com.db.exception.InvalidIdException;
import com.db.index.BruteForceIndex;
import com.db.model.CollectionStats;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Коллекция в оперативной памяти.
 * <ul>
 *   <li>хранилище {@code id → entry} и индекс k-NN синхронизируются под одной блокировкой;</li>
 *   <li>чтения (get/exists/search) идут параллельно, записи — эксклюзивно ({@link ReentrantReadWriteLock});</li>
 *   <li>записи копируются при вставке и выдаче — внешний код не может изменить данные в БД через массивы;</li>
 *   <li>при {@code maxVectors > 0} и переполнении вытесняется самая старая по времени записи запись (FIFO).</li>
 * </ul>
 */
public final class InMemoryCollection implements ICollection {

    private final CollectionConfig config;
    /** Порядок вставки нужен для FIFO-вытеснения. */
    private final LinkedHashMap<String, VectorEntry> storage = new LinkedHashMap<>();
    private final IIndex index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final LongAdder totalPuts = new LongAdder();
    private final LongAdder totalSearches = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evicted = new LongAdder();

    /** Растёт при каждом изменении данных — по нему БД понимает, что коллекцию надо пересохранить. */
    private final AtomicLong version = new AtomicLong();
    private enum State { OPEN, DROPPED, CLOSED }

    private volatile State state = State.OPEN;
    private volatile String ownerName = "";

    public InMemoryCollection(CollectionConfig config) {
        this(config, new BruteForceIndex(config.metric()));
    }

    public InMemoryCollection(CollectionConfig config, IIndex index) {
        this.config = Objects.requireNonNull(config, "config");
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public CollectionConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- IRamDB

    @Override
    public void put(VectorEntry entry) {
        Objects.requireNonNull(entry, "entry");
        validateVector(entry.vector());
        VectorEntry copy = copyOf(entry);
        lock.writeLock().lock();
        try {
            ensureAlive();
            // remove + put: перезаписанная запись становится «самой новой» для FIFO
            if (storage.remove(copy.id()) == null) {
                evictIfFull();
            }
            storage.put(copy.id(), copy);
            index.add(copy);
            totalPuts.increment();
            version.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<VectorEntry> getById(String id) {
        validateId(id);
        lock.readLock().lock();
        try {
            ensureAlive();
            VectorEntry e = storage.get(id);
            if (e == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(copyOf(e));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(String id) {
        validateId(id);
        lock.writeLock().lock();
        try {
            ensureAlive();
            if (storage.remove(id) == null) {
                return false;
            }
            index.remove(id);
            version.incrementAndGet();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean exists(String id) {
        validateId(id);
        lock.readLock().lock();
        try {
            ensureAlive();
            return storage.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long size() {
        lock.readLock().lock();
        try {
            ensureAlive();
            return storage.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            ensureAlive();
            if (!storage.isEmpty()) {
                storage.clear();
                index.clear();
                version.incrementAndGet();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------- INearestSearch

    @Override
    public List<SearchResult> getNearest(double[] query, int k) {
        Objects.requireNonNull(query, "query");
        validateVector(query);
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }
        lock.readLock().lock();
        try {
            ensureAlive();
            totalSearches.increment();
            List<SearchResult> raw = index.getNearest(query, k);
            List<SearchResult> out = new ArrayList<>(raw.size());
            for (SearchResult r : raw) {
                out.add(new SearchResult(copyOf(r.entry()), r.distance()));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------ stats

    @Override
    public CollectionStats stats() {
        lock.readLock().lock();
        try {
            return new CollectionStats(
                    config.name(), config.dimension(), config.metric(),
                    storage.size(),
                    totalPuts.sum(), totalSearches.sum(),
                    hits.sum(), misses.sum(), evicted.sum());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------- пакетный API для InMemoryVectorDB

    /** Номер версии данных; меняется при каждом изменении. */
    long version() {
        return version.get();
    }

    /** Согласованный снимок записей (в порядке вставки) для сохранения на диск. */
    Snapshot snapshot() {
        lock.readLock().lock();
        try {
            // записи неизменяемы (мы их не мутируем и отдаём наружу копии) — копировать массивы не нужно
            return new Snapshot(version.get(), List.copyOf(storage.values()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Загрузка с диска: без счётчика put'ов, версия не меняется. */
    void restore(List<VectorEntry> entries) {
        lock.writeLock().lock();
        try {
            storage.clear();
            index.clear();
            for (VectorEntry e : entries) {
                validateVector(e.vector());
                storage.put(e.id(), e);
                index.add(e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Вызывается БД при dropCollection: дальнейшие обращения по старой ссылке — ошибка. */
    void markDropped() {
        lock.writeLock().lock();
        try {
            state = State.DROPPED;
            storage.clear();
            index.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Вызывается БД при close(): ссылки на коллекцию, оставшиеся у клиента, перестают работать. */
    void markClosed(String dbName) {
        lock.writeLock().lock();
        try {
            ownerName = dbName;
            state = State.CLOSED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    record Snapshot(long version, List<VectorEntry> entries) {
    }

    // --------------------------------------------------------------- helpers

    private void evictIfFull() {
        int max = config.maxVectors();
        if (max <= 0) {
            return;
        }
        Iterator<VectorEntry> it = storage.values().iterator();
        while (storage.size() >= max && it.hasNext()) {
            VectorEntry oldest = it.next();
            it.remove();
            index.remove(oldest.id());
            evicted.increment();
        }
    }

    private void validateVector(double[] v) {
        if (v.length != config.dimension()) {
            throw new DimensionMismatchException(config.dimension(), v.length);
        }
        for (double x : v) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("vector contains NaN or Infinity");
            }
        }
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new InvalidIdException("id cannot be null or blank");
        }
    }

    private void ensureAlive() {
        switch (state) {
            case OPEN -> { }
            case DROPPED -> throw new CollectionNotFoundException(config.name());
            case CLOSED -> throw new DatabaseClosedException(ownerName);
        }
    }

    static VectorEntry copyOf(VectorEntry e) {
        return new VectorEntry(e.id(), e.vector().clone(), e.data() == null ? null : e.data().clone());
    }
}
