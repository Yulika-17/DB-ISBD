package com.db.replication;

import java.time.Duration;
import java.util.List;

/** Наблюдаемость и ручной failover поверх {@link com.db.api.IVectorDB}. */
public interface IReplicationNode {

    ReplicationStatus replicationStatus();

    List<WalRecord> walDump(int n);

    void promote();

    long lastWriteLsn();

    /** Ждёт, пока ведомый применит {@code lsn} (на ведущем возвращает сразу, если lastLsn >= lsn). */
    boolean awaitApplied(long lsn, Duration timeout) throws InterruptedException;
}
