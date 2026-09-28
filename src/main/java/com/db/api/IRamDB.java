package com.db.api;

import com.db.model.VectorEntry;

import java.util.Optional;

public interface IRamDB {

    void put(VectorEntry entry);

    Optional<VectorEntry> getById(String id);

    boolean delete(String id);

    boolean exists(String id);

    long size();

    void clear();
}
