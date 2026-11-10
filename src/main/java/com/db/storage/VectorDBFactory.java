package com.db.storage;

import com.db.api.IVectorDB;
import com.db.config.DbConfig;
import com.db.replication.ReplicatedVectorDB;

/** Открывает обычный инстанс или узел репликации в зависимости от {@link DbConfig#role()}. */
public final class VectorDBFactory {

    private VectorDBFactory() {
    }

    public static IVectorDB open(DbConfig config) {
        if (config.replicationEnabled()) {
            return ReplicatedVectorDB.open(config);
        }
        return InMemoryVectorDB.open(config);
    }
}
