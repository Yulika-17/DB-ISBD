package com.db.model;

import java.util.Objects;

public record SearchResult(VectorEntry entry, double distance) {

    public SearchResult {
        Objects.requireNonNull(entry, "entry");
    }
}
