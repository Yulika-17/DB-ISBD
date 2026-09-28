package com.db.model;

import java.time.Instant;

public record DbStats(
        String engine,
        int dimension,
        long liveVectors,
        long totalPuts,
        long totalSearches,
        long hits,
        long misses,
        long evictedVectors,
        Instant lastSaveAt,
        long lastSaveDurationMs
) {
    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
