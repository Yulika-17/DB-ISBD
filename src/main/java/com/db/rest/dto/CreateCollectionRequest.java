package com.db.rest.dto;

import com.db.config.DistanceMetric;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateCollectionRequest(
        @NotBlank String name,
        @Min(1) int dimension,
        DistanceMetric metric,
        @Min(0) int maxVectors
) {
    public CreateCollectionRequest {
        if (metric == null) {
            metric = DistanceMetric.COSINE;
        }
    }
}
