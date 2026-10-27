package com.db.rest;

import com.db.api.ICollection;
import com.db.api.IVectorDB;
import com.db.exception.IdNotFoundException;
import com.db.model.VectorEntry;
import com.db.rest.dto.PutVectorRequest;
import com.db.rest.dto.SearchRequest;
import com.db.rest.dto.SearchResponse;
import com.db.rest.dto.VectorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/collections/{collection}/vectors")
@Tag(name = "Vectors")
public class VectorController {

    private final IVectorDB database;

    public VectorController(IVectorDB database) {
        this.database = database;
    }

    @PutMapping("/{id}")
    @Operation(summary = "Create or replace a vector")
    public VectorResponse put(
            @PathVariable String collection,
            @PathVariable String id,
            @Valid @RequestBody PutVectorRequest request
    ) {
        ICollection target = database.getCollection(collection);
        target.put(new VectorEntry(id, request.vector(), request.data()));
        return VectorResponse.from(target.getById(id).orElseThrow(() -> new IdNotFoundException(id)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a vector by id")
    public VectorResponse get(@PathVariable String collection, @PathVariable String id) {
        return VectorResponse.from(database.getCollection(collection).getById(id)
                .orElseThrow(() -> new IdNotFoundException(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a vector by id")
    public ResponseEntity<Void> delete(@PathVariable String collection, @PathVariable String id) {
        if (!database.getCollection(collection).delete(id)) {
            throw new IdNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/exists")
    @Operation(summary = "Check whether a vector exists")
    public Map<String, Boolean> exists(@PathVariable String collection, @PathVariable String id) {
        return Map.of("exists", database.getCollection(collection).exists(id));
    }

    @DeleteMapping
    @Operation(summary = "Delete all vectors from a collection")
    public ResponseEntity<Void> clear(@PathVariable String collection) {
        database.getCollection(collection).clear();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    @Operation(summary = "Find the nearest vectors")
    public List<SearchResponse> search(
            @PathVariable String collection,
            @Valid @RequestBody SearchRequest request
    ) {
        return database.getCollection(collection).getNearest(request.vector(), request.k()).stream()
                .map(SearchResponse::from)
                .toList();
    }
}
