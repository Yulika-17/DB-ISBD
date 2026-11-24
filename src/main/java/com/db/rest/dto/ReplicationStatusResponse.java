package com.db.rest.dto;

import com.db.config.NodeRole;
import com.db.replication.ReplicationStatus;

public record ReplicationStatusResponse(
        NodeRole role,
        long lastLsn,
        long lastAppliedLsn,
        long masterLsn,
        long lag,
        int replicationPort,
        long replicationDelayMs
) {
    public static ReplicationStatusResponse from(ReplicationStatus status) {
        return new ReplicationStatusResponse(
                status.role(),
                status.lastLsn(),
                status.lastAppliedLsn(),
                status.masterLsn(),
                status.lag(),
                status.replicationPort(),
                status.replicationDelayMs());
    }
}
