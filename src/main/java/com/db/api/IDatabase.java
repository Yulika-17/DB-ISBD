package com.db.api;

import com.db.model.DbStats;

public interface IDatabase extends AutoCloseable {

    String name();

    boolean isClosed();

    DbStats stats();

    @Override
    void close();
}
