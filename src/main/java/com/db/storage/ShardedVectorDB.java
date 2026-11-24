package com.db.storage;

import com.db.api.ICollection;
import com.db.api.IVectorDB;
import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.exception.CollectionAlreadyExistsException;
import com.db.exception.CollectionNotFoundException;
import com.db.model.CollectionStats;
import com.db.model.DbStats;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import com.db.config.NodeRole;
import com.db.replication.ReplicatedVectorDB;
import com.db.replication.IReplicationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Single-process shard master: routes vector IDs over independent local shard stores. */
public final class ShardedVectorDB implements IVectorDB {
    private static final int VIRTUAL_NODES = 128;
    private final DbConfig config;
    private final List<ReplicatedVectorDB> shards;
    private final List<ReplicatedVectorDB> replicas;
    private final ConsistentHashRing<ReplicatedVectorDB> ring;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<IVectorDB> activeShards;
    private final AtomicLong[] pointCalls;
    private final AtomicLong[] scatterCalls;

    private ShardedVectorDB(DbConfig config, int shardCount) {
        this.config = config;
        int previousCount = readShardCount(config, shardCount);
        if (shardCount < previousCount) throw new IllegalArgumentException("decreasing VECDB_SHARDS is not supported; migrate data first");
        int oldCount = shardCount;
        Map<String, ReplicatedVectorDB> nodes = new LinkedHashMap<>();
        List<ReplicatedVectorDB> opened = new ArrayList<>();
        List<ReplicatedVectorDB> openedReplicas = new ArrayList<>();
        try {
            int portBase = config.replicationPort();
            if (portBase == 0) throw new IllegalArgumentException("sharded replication requires a non-zero replication port base");
            if (portBase + (oldCount * 2) >= 65536) throw new IllegalArgumentException("replication port range exceeded for shard replicas");
            for (int i = 0; i < oldCount; i++) {
                String id = "shard-" + i;
                DbConfig shardConfig = DbConfig.builder().name(config.name() + "-" + id)
                        .dataDirectory(Path.of(config.dataDirectory(), id).toString())
                        .autoSave(config.autoSave()).saveInterval(config.saveInterval())
                        .role(NodeRole.MASTER).replicationPort(portBase + i * 2).build();
                ReplicatedVectorDB shard = ReplicatedVectorDB.open(shardConfig);
                opened.add(shard);
                nodes.put(id, shard);
            }
            for (int i = 0; i < oldCount; i++) {
                Path replicaPath = Path.of(config.dataDirectory(), "replica-" + i);
                seedReplica(opened.get(i), replicaPath);
                DbConfig replicaConfig = DbConfig.builder().name(config.name() + "-replica-" + i)
                        .dataDirectory(replicaPath.toString()).autoSave(config.autoSave())
                        .saveInterval(config.saveInterval()).role(NodeRole.REPLICA)
                        .replicationPort(portBase + i * 2 + 1).masterHost("127.0.0.1")
                        .masterPort(portBase + i * 2).build();
                openedReplicas.add(ReplicatedVectorDB.open(replicaConfig));
            }
            ConsistentHashRing<ReplicatedVectorDB> currentRing = new ConsistentHashRing<>(
                    nodes.entrySet().stream().filter(e -> Integer.parseInt(e.getKey().substring(6)) < shardCount)
                            .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll), VIRTUAL_NODES);
            synchronizeCollections(opened);
            rebalance(opened, currentRing);
            writeShardCount(config, shardCount);
        } catch (RuntimeException e) {
            openedReplicas.forEach(ReplicatedVectorDB::close);
            opened.forEach(ReplicatedVectorDB::close);
            throw e;
        }
        this.shards = List.copyOf(opened);
        this.replicas = List.copyOf(openedReplicas);
        this.activeShards = new ArrayList<>(opened);
        this.pointCalls = counters(shardCount);
        this.scatterCalls = counters(shardCount);
        this.ring = new ConsistentHashRing<>(nodes.entrySet().stream()
                .filter(e -> Integer.parseInt(e.getKey().substring(6)) < shardCount)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll), VIRTUAL_NODES);
    }

    public static ShardedVectorDB open(DbConfig config, int shardCount) {
        if (shardCount < 2) throw new IllegalArgumentException("shardCount must be at least 2");
        return new ShardedVectorDB(config, shardCount);
    }

    public int shardCount() { return shards.size(); }
    public int virtualNodesPerShard() { return VIRTUAL_NODES; }

    public List<Map<String, Object>> ringTokens() {
        Map<ReplicatedVectorDB, String> names = new java.util.IdentityHashMap<>();
        for (int i = 0; i < shards.size(); i++) names.put(shards.get(i), "shard-" + i);
        return ring.tokens().stream().map(t -> Map.<String, Object>of(
                "token", Long.toUnsignedString(t.hash()), "shard", names.get(t.node()))).toList();
    }

    public Map<String, Object> routeInfo(String id) {
        ReplicatedVectorDB primary = ring.locate(id);
        int index = shards.indexOf(primary);
        return Map.of("key", id, "shard", "shard-" + index,
                "replica", "replica-" + index,
                "activeNode", activeShards.get(index) == primary ? "shard-" + index : "replica-" + index,
                "pointCalls", pointCalls[index].get(), "scatterCalls", scatterCalls[index].get());
    }

    public List<Map<String, Object>> shardDistribution(String collection) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
            ICollection c = shards.get(i).findCollection(collection).orElse(null);
            result.add(Map.of("shard", "shard-" + i, "vectors", c == null ? 0L : c.size(),
                    "pointCalls", pointCalls[i].get(), "scatterCalls", scatterCalls[i].get(),
                    "activeNode", activeShards.get(i) == shards.get(i) ? "shard-" + i : "replica-" + i));
        }
        return result;
    }

    public synchronized Map<String, Object> failover(int shardIndex) {
        if (shardIndex < 0 || shardIndex >= shards.size()) throw new IllegalArgumentException("unknown shard index");
        if (activeShards.get(shardIndex) == replicas.get(shardIndex)) return routeShard(shardIndex);
        ReplicatedVectorDB primary = shards.get(shardIndex);
        ReplicatedVectorDB replica = replicas.get(shardIndex);
        try {
            long target = ((IReplicationNode) primary).lastWriteLsn();
            if (!((IReplicationNode) replica).awaitApplied(target, Duration.ofSeconds(20))) {
                throw new IllegalStateException("replica did not catch up to LSN " + target);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for replica", e);
        }
        replica.promote();
        primary.crashUnsaved();
        List<IVectorDB> next = new ArrayList<>(activeShards);
        next.set(shardIndex, replica);
        activeShards = List.copyOf(next);
        return routeShard(shardIndex);
    }

    public void resetRequestCounters() {
        for (int i = 0; i < shards.size(); i++) { pointCalls[i].set(0); scatterCalls[i].set(0); }
    }

    private Map<String, Object> routeShard(int index) {
        return Map.of("shard", "shard-" + index,
                "activeNode", activeShards.get(index) == shards.get(index) ? "shard-" + index : "replica-" + index);
    }

    private static AtomicLong[] counters(int n) {
        AtomicLong[] result = new AtomicLong[n];
        for (int i = 0; i < n; i++) result[i] = new AtomicLong();
        return result;
    }

    private static int readShardCount(DbConfig config, int fallback) {
        Path file = Path.of(config.dataDirectory(), "shards.count");
        if (!java.nio.file.Files.isRegularFile(file)) return fallback;
        try {
            return Integer.parseInt(java.nio.file.Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("cannot read shard topology at " + file, e);
        }
    }

    private static void writeShardCount(DbConfig config, int count) {
        Path root = config.dataPath();
        Path file = root.resolve("shards.count");
        try {
            java.nio.file.Files.createDirectories(root);
            java.nio.file.Files.writeString(file, Integer.toString(count));
        } catch (IOException e) {
            throw new IllegalStateException("cannot save shard topology at " + file, e);
        }
    }

    private static void seedReplica(ReplicatedVectorDB master, Path replicaPath) {
        DbConfig seedConfig = DbConfig.builder().name(master.name() + "-replica-seed")
                .dataDirectory(replicaPath.toString()).autoSave(false).build();
        InMemoryVectorDB seed = InMemoryVectorDB.open(seedConfig);
        try {
            for (String existing : seed.listCollections()) seed.dropCollection(existing);
            for (String name : master.listCollections()) {
                ICollection from = master.getCollection(name);
                ICollection to = seed.createCollection(from.config());
                for (VectorEntry entry : from.entries()) to.put(entry);
            }
        } finally {
            seed.close();
        }
    }

    private static void rebalance(List<ReplicatedVectorDB> allShards,
                                  ConsistentHashRing<ReplicatedVectorDB> ring) {
        for (ReplicatedVectorDB source : allShards) {
            for (String name : source.listCollections()) {
                for (VectorEntry entry : source.getCollection(name).entries()) {
                    ReplicatedVectorDB destination = ring.locate(entry.id());
                    if (destination != source) {
                        destination.getCollection(name).put(entry);
                        source.getCollection(name).delete(entry.id());
                    }
                }
            }
        }
    }

    private static void synchronizeCollections(List<ReplicatedVectorDB> shards) {
        Map<String, CollectionConfig> schemas = new LinkedHashMap<>();
        for (ReplicatedVectorDB shard : shards) {
            for (String name : shard.listCollections()) schemas.putIfAbsent(name, shard.getCollection(name).config());
        }
        for (ReplicatedVectorDB shard : shards) {
            for (CollectionConfig schema : schemas.values()) {
                if (!shard.hasCollection(schema.name())) shard.createCollection(schema);
            }
        }
    }

    @Override public String name() { return config.name(); }
    @Override public boolean isClosed() { return closed.get(); }

    @Override public DbStats stats() {
        Map<String, CollectionStats> collections = new LinkedHashMap<>();
        long vectors = 0, puts = 0, searches = 0, hits = 0, misses = 0, evicted = 0;
        for (ReplicatedVectorDB shard : shards) {
            DbStats s = shard.stats();
            vectors += s.liveVectors(); puts += s.totalPuts(); searches += s.totalSearches();
            hits += s.hits(); misses += s.misses(); evicted += s.evictedVectors();
            s.perCollection().forEach((name, c) -> collections.merge(name, c, (a, b) -> new CollectionStats(
                    name, a.dimension(), a.metric(), a.liveVectors() + b.liveVectors(),
                    a.totalPuts() + b.totalPuts(), a.totalSearches() + b.totalSearches(),
                    a.hits() + b.hits(), a.misses() + b.misses(), a.evictedVectors() + b.evictedVectors())));
        }
        return new DbStats("sharded/consistent-hash", collections.size(), vectors, puts, searches,
                hits, misses, evicted, null, 0, collections);
    }

    @Override public ICollection createCollection(CollectionConfig cfg) {
        if (hasCollection(cfg.name())) throw new CollectionAlreadyExistsException(cfg.name());
        List<ReplicatedVectorDB> created = new ArrayList<>();
        try {
            for (ReplicatedVectorDB shard : shards) { shard.createCollection(cfg); created.add(shard); }
        } catch (RuntimeException e) {
            created.forEach(shard -> shard.dropCollection(cfg.name()));
            throw e;
        }
        return collection(cfg.name());
    }

    @Override public ICollection getCollection(String name) {
        return findCollection(name).orElseThrow(() -> new CollectionNotFoundException(name));
    }
    @Override public Optional<ICollection> findCollection(String name) {
        ensureOpen();
        return shards.getFirst().findCollection(name).map(ignored -> collection(name));
    }
    @Override public boolean dropCollection(String name) {
        ensureOpen();
        boolean found = hasCollection(name);
        if (found) shards.forEach(shard -> shard.dropCollection(name));
        return found;
    }
    @Override public List<String> listCollections() { ensureOpen(); return shards.getFirst().listCollections(); }
    @Override public void save() throws IOException { ensureOpen(); for (ReplicatedVectorDB shard : shards) shard.save(); }
    @Override public void load() throws IOException { ensureOpen(); for (ReplicatedVectorDB shard : shards) shard.load(); }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            replicas.forEach(ReplicatedVectorDB::close);
            shards.forEach(ReplicatedVectorDB::close);
        }
    }

    private ICollection collection(String name) {
        return new ICollection() {
            @Override public CollectionConfig config() { return shards.getFirst().getCollection(name).config(); }
            @Override public void put(VectorEntry entry) { route(entry.id()).getCollection(name).put(entry); }
            @Override public Optional<VectorEntry> getById(String id) { return route(id).getCollection(name).getById(id); }
            @Override public boolean delete(String id) { return route(id).getCollection(name).delete(id); }
            @Override public boolean exists(String id) { return route(id).getCollection(name).exists(id); }
            @Override public long size() { return shards.stream().mapToLong(s -> s.getCollection(name).size()).sum(); }
            @Override public void clear() { shards.forEach(s -> s.getCollection(name).clear()); }
            @Override public List<SearchResult> getNearest(double[] query, int k) {
                if (k < 1) throw new IllegalArgumentException("k must be positive");
                return shards.stream().flatMap(s -> s.getCollection(name).getNearest(query, k).stream())
                        .sorted(java.util.Comparator.comparingDouble(SearchResult::distance))
                        .limit(k).toList();
            }
            @Override public CollectionStats stats() {
                return ShardedVectorDB.this.stats().perCollection().get(name);
            }
            @Override public List<VectorEntry> entries() {
                return shards.stream().flatMap(s -> s.getCollection(name).entries().stream()).toList();
            }
        };
    }

    private ReplicatedVectorDB route(String id) { ensureOpen(); return ring.locate(id); }
    private void ensureOpen() { if (closed.get()) throw new IllegalStateException("sharded database is closed"); }
}
