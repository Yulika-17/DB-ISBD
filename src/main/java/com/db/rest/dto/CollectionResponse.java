package com.db.rest.dto;

import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;

public record CollectionResponse(
        String name,
        int dimension,
        DistanceMetric metric,
        int maxVectors
) {
    public static CollectionResponse from(CollectionConfig config) {
        return new CollectionResponse(
                config.name(),
                config.dimension(),
                config.metric(),
                config.maxVectors()
        );
    }
}
