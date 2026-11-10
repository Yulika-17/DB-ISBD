package com.db.replication;

import com.db.config.NodeRole;

/**
 * Снимок наблюдаемости узла.
 * На ведомом {@code masterLsn} берётся из последней полученной записи WAL,
 * {@code lag = masterLsn - lastAppliedLsn}.
 */
public record ReplicationStatus(
        NodeRole role,
        long lastLsn,
        long lastAppliedLsn,
        long masterLsn,
        long lag,
        int replicationPort,
        long replicationDelayMs
) {
    public static ReplicationStatus standalone() {
        return new ReplicationStatus(NodeRole.STANDALONE, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
        if (role == NodeRole.REPLICA) {
            return "role=" + role
                    + " lastAppliedLsn=" + lastAppliedLsn
                    + " masterLsn=" + masterLsn
                    + " lag=" + lag
                    + " delayMs=" + replicationDelayMs;
        }
        return "role=" + role
                + " lastLsn=" + lastLsn
                + " lag=" + lag
                + (replicationPort > 0 ? " replicationPort=" + replicationPort : "");
    }
}
