package com.db.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalLogTest {

    @TempDir
    Path dir;

    @Test
    void appendAssignsLsnPersistsAndDumpsTail() {
        WalLog log = WalLog.open(dir);
        WalRecord a = log.append(WalRecord.clear("docs"));
        WalRecord b = log.append(WalRecord.dropCollection("docs"));
        assertEquals(1, a.lsn());
        assertEquals(2, b.lsn());
        assertEquals(2, log.lastLsn());
        assertEquals(2, log.last(1).get(0).lsn());
        log.writeCheckpoint(2);
        log.close();

        WalLog reopened = WalLog.open(dir);
        assertEquals(2, reopened.lastLsn());
        assertEquals(2, reopened.checkpointLsn());
        assertTrue(WalJson.toJson(reopened.last(2).get(0)).contains("\"op\":\"CLEAR\""));
        reopened.close();
    }

    @Test
    void jsonRoundTripPut() {
        WalRecord rec = WalRecord.put("docs", new com.db.model.VectorEntry(
                "id-1", new double[]{1.0, 0.5, 0.0}, "hi".getBytes())).withLsn(7);
        WalRecord parsed = WalJson.parseRecord(WalJson.toJson(rec));
        assertEquals(rec, parsed);
    }
}
