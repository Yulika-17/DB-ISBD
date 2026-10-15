package com.db.model;

import com.db.config.DistanceMetric;

public record CollectionStats(
        String name,
        int dimension,
        DistanceMetric metric,
        long liveVectors,
        long totalPuts,
        long totalSearches,
        long hits,
        long misses,
        long evictedVectors
) {
    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
