package com.db.rest.dto;

import com.db.model.VectorEntry;

public record VectorResponse(String id, double[] vector, byte[] data, Long lsn) {

    public static VectorResponse from(VectorEntry entry) {
        return from(entry, null);
    }

    public static VectorResponse from(VectorEntry entry, Long lsn) {
        return new VectorResponse(entry.id(), entry.vector(), entry.data(), lsn);
    }
}
