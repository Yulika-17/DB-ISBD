package com.db.replication;

import com.db.api.ICollection;
import com.db.api.IVectorDB;
import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.config.NodeRole;
import com.db.exception.CollectionAlreadyExistsException;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.ReplicaReadOnlyException;
import com.db.model.DbStats;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import com.db.storage.InMemoryVectorDB;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Обёртка над {@link InMemoryVectorDB}: журнал логических операций, асинхронная
 * репликация 1 ведущий + 1 ведомый, восстановление из WAL.
 */
public final class ReplicatedVectorDB implements IVectorDB, IReplicationNode {

    private final DbConfig config;
    private final InMemoryVectorDB inner;
    private final WalLog wal;
    private final ReentrantLock applyLock = new ReentrantLock();
    private final Condition applied = applyLock.newCondition();
    private final ConcurrentHashMap<String, ICollection> views = new ConcurrentHashMap<>();
    private final AtomicLong lastWriteLsn = new AtomicLong();
    private final AtomicLong lastAppliedLsn = new AtomicLong();
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    private volatile NodeRole role;
    private volatile MasterReplicator master;
    private volatile ReplicaFollower replica;
    private volatile boolean closed;

    private ReplicatedVectorDB(DbConfig config, InMemoryVectorDB inner, WalLog wal) {
        this.config = config;
        this.inner = inner;
        this.wal = wal;
        this.role = config.role();
    }

    public static ReplicatedVectorDB open(DbConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.replicationEnabled()) {
            throw new IllegalArgumentException("ReplicatedVectorDB requires role MASTER or REPLICA");
        }
        InMemoryVectorDB inner = InMemoryVectorDB.open(config);
        WalLog wal = WalLog.open(config.dataPath());
        ReplicatedVectorDB db = new ReplicatedVectorDB(config, inner, wal);
        try {
            db.replayFromCheckpoint();
            db.startReplication();
            return db;
        } catch (RuntimeException e) {
            wal.close();
            inner.abandonWithoutSave();
            throw e;
        }
    }

    public int replicationPort() {
        MasterReplicator m = master;
        return m == null ? config.replicationPort() : m.port();
    }

    public DbConfig config() {
        return config;
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public boolean isClosed() {
        return closed || inner.isClosed();
    }

    @Override
    public DbStats stats() {
        return inner.stats();
    }

    @Override
    public ICollection createCollection(CollectionConfig cfg) {
        ensureMasterWriter();
        applyLock.lock();
        try {
            if (inner.hasCollection(cfg.name())) {
                throw new CollectionAlreadyExistsException(cfg.name());
            }
            WalRecord rec = wal.append(WalRecord.createCollection(cfg));
            applying.set(true);
            try {
                inner.createCollection(cfg);
            } finally {
                applying.set(false);
            }
            noteApplied(rec);
            ReplicationLog.master(rec.applySummary());
            return view(cfg.name());
        } finally {
            applyLock.unlock();
        }
    }

    @Override
    public ICollection getCollection(String name) {
        return findCollection(name).orElseThrow(() -> new CollectionNotFoundException(name));
    }

    @Override
    public Optional<ICollection> findCollection(String name) {
        return inner.findCollection(name).map(ignored -> view(name));
    }

    @Override
    public boolean dropCollection(String name) {
        ensureMasterWriter();
        applyLock.lock();
        try {
            if (!inner.hasCollection(name)) {
                return false;
            }
            WalRecord rec = wal.append(WalRecord.dropCollection(name));
            applying.set(true);
            try {
                inner.dropCollection(name);
            } finally {
                applying.set(false);
            }
            views.remove(name);
            noteApplied(rec);
            ReplicationLog.master(rec.applySummary());
            return true;
        } finally {
            applyLock.unlock();
        }
    }

    @Override
    public List<String> listCollections() {
        return inner.listCollections();
    }

    @Override
    public void save() throws IOException {
        inner.save();
        wal.writeCheckpoint(wal.lastLsn());
    }

    @Override
    public void load() throws IOException {
        applyLock.lock();
        try {
            inner.load();
            views.clear();
            lastAppliedLsn.set(0);
            replayFromCheckpoint();
        } finally {
            applyLock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopReplication();
        try {
            inner.close();
            wal.writeCheckpoint(wal.lastLsn());
        } finally {
            wal.close();
        }
    }

    /** Падение без снимка: WAL уже на диске, память теряется. */
    public void crashUnsaved() {
        if (closed) {
            return;
        }
        closed = true;
        stopReplication();
        wal.close();
        inner.abandonWithoutSave();
    }

    @Override
    public ReplicationStatus replicationStatus() {
        long appliedLsn = lastAppliedLsn.get();
        if (role == NodeRole.REPLICA) {
            long seen = replica == null ? appliedLsn : replica.masterLsn();
            if (seen < appliedLsn) {
                seen = appliedLsn;
            }
            return new ReplicationStatus(role, wal.lastLsn(), appliedLsn, seen,
                    Math.max(0, seen - appliedLsn), 0, config.replicationDelayMs());
        }
        return new ReplicationStatus(role, wal.lastLsn(), appliedLsn, wal.lastLsn(),
                0, replicationPort(), config.replicationDelayMs());
    }

    @Override
    public List<WalRecord> walDump(int n) {
        return wal.last(n);
    }

    @Override
    public synchronized void promote() {
        if (role == NodeRole.MASTER) {
            return;
        }
        stopReplication();
        role = NodeRole.MASTER;
        master = new MasterReplicator(wal, config.replicationPort());
        master.start();
        ReplicationLog.replica("promoted to MASTER lastLsn=" + wal.lastLsn());
    }

    @Override
    public long lastWriteLsn() {
        return lastWriteLsn.get();
    }

    @Override
    public boolean awaitApplied(long lsn, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        applyLock.lock();
        try {
            while (lastAppliedLsn.get() < lsn) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                applied.await(remaining, TimeUnit.NANOSECONDS);
            }
            return true;
        } finally {
            applyLock.unlock();
        }
    }

    void applyFromNetwork(WalRecord rec) {
        applyLock.lock();
        try {
            if (rec.lsn() <= lastAppliedLsn.get()) {
                return;
            }
            wal.appendWithLsn(rec);
            applying.set(true);
            try {
                applyRecord(rec);
            } finally {
                applying.set(false);
            }
            noteApplied(rec);
            ReplicationLog.replica(rec.applySummary());
        } finally {
            applyLock.unlock();
        }
    }

    private void replayFromCheckpoint() {
        long from = wal.checkpointLsn();
        applying.set(true);
        try {
            long applied = from;
            for (WalRecord rec : wal.recordsAfter(from)) {
                applyRecord(rec);
                applied = rec.lsn();
            }
            lastAppliedLsn.set(applied);
            lastWriteLsn.set(applied);
        } finally {
            applying.set(false);
        }
    }

    private void applyRecord(WalRecord rec) {
        switch (rec.op()) {
            case CREATE_COLLECTION -> {
                if (!inner.hasCollection(rec.collection())) {
                    inner.createCollection(rec.collectionConfig());
                }
            }
            case DROP_COLLECTION -> inner.dropCollection(rec.collection());
            case PUT -> inner.getCollection(rec.collection()).put(rec.entry());
            case DELETE -> {
                if (inner.hasCollection(rec.collection())) {
                    inner.getCollection(rec.collection()).delete(rec.id());
                }
            }
            case CLEAR -> {
                if (inner.hasCollection(rec.collection())) {
                    inner.getCollection(rec.collection()).clear();
                }
            }
        }
        views.remove(rec.collection());
        if (rec.op() == WalRecord.Op.CREATE_COLLECTION || rec.op() == WalRecord.Op.PUT
                || rec.op() == WalRecord.Op.DELETE || rec.op() == WalRecord.Op.CLEAR) {
            if (inner.hasCollection(rec.collection())) {
                view(rec.collection());
            }
        }
    }

    private void startReplication() {
        if (role == NodeRole.MASTER) {
            master = new MasterReplicator(wal, config.replicationPort());
            master.start();
        } else {
            replica = new ReplicaFollower(
                    config.masterHost(),
                    config.masterPort(),
                    config.replicationDelayMs(),
                    lastAppliedLsn::get,
                    this::applyFromNetwork);
            replica.start();
        }
    }

    private void stopReplication() {
        if (replica != null) {
            replica.stop();
            replica = null;
        }
        if (master != null) {
            master.stop();
            master = null;
        }
    }

    private void ensureMasterWriter() {
        if (closed || inner.isClosed()) {
            inner.stats();
        }
        if (role != NodeRole.MASTER && !Boolean.TRUE.equals(applying.get())) {
            throw new ReplicaReadOnlyException();
        }
    }

    private void noteApplied(WalRecord rec) {
        lastAppliedLsn.set(rec.lsn());
        lastWriteLsn.set(rec.lsn());
        applied.signalAll();
    }

    private ICollection view(String name) {
        return views.computeIfAbsent(name, n -> new ReplicatedCollection(inner.getCollection(n)));
    }

    private long replicatePut(String collection, VectorEntry entry) {
        ensureMasterWriter();
        applyLock.lock();
        try {
            WalRecord rec = wal.append(WalRecord.put(collection, entry));
            applying.set(true);
            try {
                inner.getCollection(collection).put(entry);
            } finally {
                applying.set(false);
            }
            noteApplied(rec);
            ReplicationLog.master(rec.applySummary());
            return rec.lsn();
        } finally {
            applyLock.unlock();
        }
    }

    private boolean replicateDelete(String collection, String id) {
        ensureMasterWriter();
        applyLock.lock();
        try {
            WalRecord rec = wal.append(WalRecord.delete(collection, id));
            applying.set(true);
            boolean removed;
            try {
                removed = inner.getCollection(collection).delete(id);
            } finally {
                applying.set(false);
            }
            noteApplied(rec);
            ReplicationLog.master(rec.applySummary());
            return removed;
        } finally {
            applyLock.unlock();
        }
    }

    private void replicateClear(String collection) {
        ensureMasterWriter();
        applyLock.lock();
        try {
            WalRecord rec = wal.append(WalRecord.clear(collection));
            applying.set(true);
            try {
                inner.getCollection(collection).clear();
            } finally {
                applying.set(false);
            }
            noteApplied(rec);
            ReplicationLog.master(rec.applySummary());
        } finally {
            applyLock.unlock();
        }
    }

    private final class ReplicatedCollection implements ICollection {
        private final ICollection delegate;

        private ReplicatedCollection(ICollection delegate) {
            this.delegate = delegate;
        }

        @Override
        public CollectionConfig config() {
            return delegate.config();
        }

        @Override
        public void put(VectorEntry entry) {
            if (applying.get()) {
                delegate.put(entry);
                return;
            }
            replicatePut(delegate.name(), entry);
        }

        @Override
        public Optional<VectorEntry> getById(String id) {
            return delegate.getById(id);
        }

        @Override
        public boolean delete(String id) {
            if (applying.get()) {
                return delegate.delete(id);
            }
            return replicateDelete(delegate.name(), id);
        }

        @Override
        public boolean exists(String id) {
            return delegate.exists(id);
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public void clear() {
            if (applying.get()) {
                delegate.clear();
                return;
            }
            replicateClear(delegate.name());
        }

        @Override
        public List<SearchResult> getNearest(double[] query, int k) {
            return delegate.getNearest(query, k);
        }

        @Override
        public com.db.model.CollectionStats stats() {
            return delegate.stats();
        }
    }
}
