package com.db.replication;

import com.db.config.DbConfig;
import com.db.config.DistanceMetric;
import com.db.config.NodeRole;
import com.db.exception.ReplicaReadOnlyException;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicatedVectorDBTest {

    @TempDir
    Path dir;

    private ReplicatedVectorDB master(String name) {
        return ReplicatedVectorDB.open(DbConfig.builder()
                .name(name)
                .dataDirectory(dir.resolve(name).toString())
                .autoSave(false)
                .role(NodeRole.MASTER)
                .replicationPort(0)
                .build());
    }

    private ReplicatedVectorDB replica(String name, int masterPort, long delayMs) {
        return ReplicatedVectorDB.open(DbConfig.builder()
                .name(name)
                .dataDirectory(dir.resolve(name).toString())
                .autoSave(false)
                .role(NodeRole.REPLICA)
                .replicationPort(0)
                .masterHost("127.0.0.1")
                .masterPort(masterPort)
                .replicationDelayMs(delayMs)
                .build());
    }

    @Test
    void reopenAfterCloseReplaysFromCheckpointOnly() {
        ReplicatedVectorDB master = master("ckpt-m");
        master.createCollection("docs", 2, DistanceMetric.COSINE);
        master.getCollection("docs").put(new VectorEntry("a", new double[]{1, 0}, null));
        master.close();

        ReplicatedVectorDB reopened = master("ckpt-m");
        try {
            assertEquals(2, reopened.replicationStatus().lastLsn());
            assertEquals(2, reopened.replicationStatus().lastAppliedLsn());
            assertTrue(reopened.getCollection("docs").exists("a"));
        } finally {
            reopened.close();
        }
    }

    @Test
    void recoversMasterFromWalWithoutSnapshot() {
        ReplicatedVectorDB master = master("wal-m");
        master.createCollection("docs", 3, DistanceMetric.COSINE);
        master.getCollection("docs").put(new VectorEntry("x", new double[]{1, 0, 0}, "p".getBytes()));
        assertEquals(2, master.replicationStatus().lastLsn());
        master.crashUnsaved();

        ReplicatedVectorDB recovered = master("wal-m");
        try {
            assertEquals("x", recovered.getCollection("docs").getById("x").orElseThrow().id());
            assertEquals(2, recovered.replicationStatus().lastLsn());
            assertFalse(recovered.walDump(10).isEmpty());
        } finally {
            recovered.close();
        }
    }

    @Test
    void replicaLagsThenCatchesUpAndRejectsWrites() throws Exception {
        ReplicatedVectorDB master = master("lag-m");
        ReplicatedVectorDB replica = replica("lag-r", master.replicationPort(), 400);
        try {
            master.createCollection("docs", 2, DistanceMetric.COSINE);
            master.getCollection("docs").put(new VectorEntry("7", new double[]{1, 0}, null));
            long lsn = master.lastWriteLsn();
            Thread.sleep(80);
            ReplicationStatus delayed = replica.replicationStatus();
            assertEquals(NodeRole.REPLICA, delayed.role());
            assertTrue(delayed.masterLsn() >= delayed.lastAppliedLsn());
            assertTrue(delayed.lag() >= 0);
            assertFalse(replica.findCollection("docs").flatMap(c -> c.getById("7")).isPresent(),
                    "replica should not have applied the PUT yet");

            assertTrue(replica.awaitApplied(lsn, Duration.ofSeconds(5)));
            assertEquals(0, replica.replicationStatus().lag());
            assertTrue(replica.getCollection("docs").exists("7"));

            assertThrows(ReplicaReadOnlyException.class,
                    () -> replica.getCollection("docs").put(new VectorEntry("nope", new double[]{0, 1}, null)));
        } finally {
            replica.close();
            master.close();
        }
    }

    @Test
    void replicaCatchesUpAfterReconnect() throws Exception {
        ReplicatedVectorDB master = master("cu-m");
        ReplicatedVectorDB replica = replica("cu-r", master.replicationPort(), 0);
        master.createCollection("docs", 2, DistanceMetric.COSINE);
        master.getCollection("docs").put(new VectorEntry("a", new double[]{1, 0}, null));
        assertTrue(replica.awaitApplied(master.lastWriteLsn(), Duration.ofSeconds(5)));
        replica.crashUnsaved();

        master.getCollection("docs").put(new VectorEntry("b", new double[]{0, 1}, null));
        replica = replica("cu-r", master.replicationPort(), 0);
        try {
            assertTrue(replica.awaitApplied(master.lastWriteLsn(), Duration.ofSeconds(5)));
            assertTrue(replica.getCollection("docs").exists("b"));
            assertEquals(0, replica.replicationStatus().lag());
        } finally {
            replica.close();
            master.close();
        }
    }

    @Test
    void promoteTurnsReplicaIntoMaster() throws Exception {
        ReplicatedVectorDB master = master("fo-m");
        ReplicatedVectorDB replica = replica("fo-r", master.replicationPort(), 0);
        try {
            master.createCollection("docs", 2, DistanceMetric.COSINE);
            assertTrue(replica.awaitApplied(master.lastWriteLsn(), Duration.ofSeconds(5)));
            master.crashUnsaved();
            replica.promote();
            assertEquals(NodeRole.MASTER, replica.replicationStatus().role());
            replica.getCollection("docs").put(new VectorEntry("z", new double[]{1, 1}, null));
            assertTrue(replica.getCollection("docs").exists("z"));
        } finally {
            replica.close();
        }
    }
}
