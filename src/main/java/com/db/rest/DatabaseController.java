package com.db.rest;

import com.db.api.IVectorDB;
import com.db.model.DbStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Database")
public class DatabaseController {

    private final IVectorDB database;

    public DatabaseController(IVectorDB database) {
        this.database = database;
    }

    @GetMapping("/health")
    @Operation(summary = "Check server availability")
    public Map<String, String> health() {
        return Map.of("status", "UP", "database", database.name());
    }

    @GetMapping("/stats")
    @Operation(summary = "Get database statistics")
    public DbStats stats() {
        return database.stats();
    }

    @PostMapping("/save")
    @Operation(summary = "Save database to disk")
    public ResponseEntity<Void> save() throws IOException {
        database.save();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/load")
    @Operation(summary = "Reload database from disk")
    public ResponseEntity<Void> load() throws IOException {
        database.load();
        return ResponseEntity.noContent().build();
    }
}
