package com.db.replication;

import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.model.VectorEntry;

import java.util.Arrays;
import java.util.Objects;

/** Одна логическая операция в WAL. LSN монотонно растёт на ведущем. */
public record WalRecord(
        long lsn,
        Op op,
        String collection,
        String id,
        double[] vector,
        byte[] data,
        Integer dimension,
        DistanceMetric metric,
        Integer maxVectors
) {
    public enum Op {
        CREATE_COLLECTION,
        DROP_COLLECTION,
        PUT,
        DELETE,
        CLEAR
    }

    public WalRecord withLsn(long newLsn) {
        return new WalRecord(newLsn, op, collection, id, vector, data, dimension, metric, maxVectors);
    }

    public static WalRecord createCollection(CollectionConfig config) {
        Objects.requireNonNull(config, "config");
        return new WalRecord(0, Op.CREATE_COLLECTION, config.name(), null, null, null,
                config.dimension(), config.metric(), config.maxVectors());
    }

    public static WalRecord dropCollection(String name) {
        return new WalRecord(0, Op.DROP_COLLECTION, name, null, null, null, null, null, null);
    }

    public static WalRecord put(String collection, VectorEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new WalRecord(0, Op.PUT, collection, entry.id(), entry.vector(), entry.data(),
                null, null, null);
    }

    public static WalRecord delete(String collection, String id) {
        return new WalRecord(0, Op.DELETE, collection, id, null, null, null, null, null);
    }

    public static WalRecord clear(String collection) {
        return new WalRecord(0, Op.CLEAR, collection, null, null, null, null, null, null);
    }

    public CollectionConfig collectionConfig() {
        return CollectionConfig.builder(collection, dimension == null ? 1 : dimension)
                .metric(metric == null ? DistanceMetric.COSINE : metric)
                .maxVectors(maxVectors == null ? 0 : maxVectors)
                .build();
    }

    public VectorEntry entry() {
        return new VectorEntry(id, vector == null ? new double[0] : vector, data);
    }

    public String applySummary() {
        return switch (op) {
            case PUT -> "applied PUT id=" + id + " lsn=" + lsn;
            case DELETE -> "applied DELETE id=" + id + " lsn=" + lsn;
            case CLEAR -> "applied CLEAR collection=" + collection + " lsn=" + lsn;
            case CREATE_COLLECTION -> "applied CREATE_COLLECTION name=" + collection + " lsn=" + lsn;
            case DROP_COLLECTION -> "applied DROP_COLLECTION name=" + collection + " lsn=" + lsn;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WalRecord other)) {
            return false;
        }
        return lsn == other.lsn
                && op == other.op
                && Objects.equals(collection, other.collection)
                && Objects.equals(id, other.id)
                && Arrays.equals(vector, other.vector)
                && Arrays.equals(data, other.data)
                && Objects.equals(dimension, other.dimension)
                && metric == other.metric
                && Objects.equals(maxVectors, other.maxVectors);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(lsn, op, collection, id, dimension, metric, maxVectors);
        result = 31 * result + Arrays.hashCode(vector);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
