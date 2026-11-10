package com.db.rest;

import com.db.api.ICollection;
import com.db.api.IVectorDB;
import com.db.config.CollectionConfig;
import com.db.exception.CollectionNotFoundException;
import com.db.model.CollectionStats;
import com.db.rest.dto.CollectionResponse;
import com.db.rest.dto.CreateCollectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/collections")
@Tag(name = "Collections")
public class CollectionController {

    private final IVectorDB database;

    public CollectionController(IVectorDB database) {
        this.database = database;
    }

    @PostMapping
    @Operation(summary = "Create a collection with the specified vector dimension")
    public ResponseEntity<CollectionResponse> create(@Valid @RequestBody CreateCollectionRequest request) {
        CollectionConfig config = new CollectionConfig(
                request.name(),
                request.dimension(),
                request.metric(),
                request.maxVectors()
        );
        ICollection collection = database.createCollection(config);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CollectionResponse.from(collection.config()));
    }

    @GetMapping
    @Operation(summary = "List collections")
    public List<CollectionResponse> list() {
        return database.listCollections().stream()
                .map(database::getCollection)
                .map(ICollection::config)
                .map(CollectionResponse::from)
                .toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get collection configuration")
    public CollectionResponse get(@PathVariable String name) {
        return CollectionResponse.from(database.getCollection(name).config());
    }

    @GetMapping("/{name}/stats")
    @Operation(summary = "Get collection statistics")
    public CollectionStats stats(@PathVariable String name) {
        return database.getCollection(name).stats();
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Drop a collection and its data")
    public ResponseEntity<Void> drop(@PathVariable String name) {
        if (!database.dropCollection(name)) {
            throw new CollectionNotFoundException(name);
        }
        return ResponseEntity.noContent().build();
    }
}
