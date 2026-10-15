package com.db.model;

import java.time.Instant;
import java.util.Map;

/** Агрегированная статистика инстанса БД по всем коллекциям. */
public record DbStats(
        String engine,
        int collections,
        long liveVectors,
        long totalPuts,
        long totalSearches,
        long hits,
        long misses,
        long evictedVectors,
        Instant lastSaveAt,
        long lastSaveDurationMs,
        Map<String, CollectionStats> perCollection
) {
    public DbStats {
        perCollection = perCollection == null ? Map.of() : Map.copyOf(perCollection);
    }

    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
