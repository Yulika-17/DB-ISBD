package com.db.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotNull @Size(min = 1) double[] vector,
        @Min(1) int k
) {
}
