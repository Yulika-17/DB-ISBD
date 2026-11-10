package com.db.rest;

import com.db.api.IVectorDB;
import com.db.replication.IReplicationNode;
import com.db.replication.ReplicationStatus;
import com.db.replication.WalJson;
import com.db.rest.dto.ReplicationStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/replication")
@Tag(name = "Replication")
public class ReplicationController {

    private final IVectorDB database;

    public ReplicationController(IVectorDB database) {
        this.database = database;
    }

    @GetMapping("/status")
    @Operation(summary = "Replication role, LSN and lag")
    public ReplicationStatusResponse status() {
        return ReplicationStatusResponse.from(node() == null
                ? ReplicationStatus.standalone()
                : node().replicationStatus());
    }

    @GetMapping("/wal")
    @Operation(summary = "Last N WAL records as JSON lines")
    public List<String> walDump(@RequestParam(name = "n", defaultValue = "20") int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (node() == null) {
            return List.of();
        }
        return node().walDump(n).stream().map(WalJson::toJson).toList();
    }

    @PostMapping("/promote")
    @Operation(summary = "Promote replica to master (manual failover)")
    public ResponseEntity<ReplicationStatusResponse> promote() {
        if (node() == null) {
            return ResponseEntity.badRequest().build();
        }
        node().promote();
        return ResponseEntity.ok(ReplicationStatusResponse.from(node().replicationStatus()));
    }

    private IReplicationNode node() {
        return database instanceof IReplicationNode n ? n : null;
    }
}
