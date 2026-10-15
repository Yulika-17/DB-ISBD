package com.db.model;

import java.util.Objects;

public record VectorEntry(String id, double[] vector, byte[] data) {

    public VectorEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vector, "vector");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (vector.length == 0) {
            throw new IllegalArgumentException("vector cannot be empty");
        }
    }

    public int dimension() {
        return vector.length;
    }
}
