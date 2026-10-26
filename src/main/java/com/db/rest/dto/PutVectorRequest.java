package com.db.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PutVectorRequest(
        @NotNull @Size(min = 1) double[] vector,
        byte[] data
) {
}
