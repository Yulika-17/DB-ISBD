package com.db.rest.dto;

import com.db.model.VectorEntry;

public record VectorResponse(String id, double[] vector, byte[] data) {

    public static VectorResponse from(VectorEntry entry) {
        return new VectorResponse(entry.id(), entry.vector(), entry.data());
    }
}
