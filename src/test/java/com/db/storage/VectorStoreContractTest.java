package com.db.storage;

import com.db.api.ICollection;
import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.exception.DimensionMismatchException;
import com.db.exception.InvalidIdException;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт любой реализации {@link ICollection}. Новая реализация получает весь набор проверок,
 * унаследовав этот класс и реализовав {@link #createStore(CollectionConfig)}.
 */
public abstract class VectorStoreContractTest {

    protected static final int DIM = 3;

    protected ICollection store;

    protected abstract ICollection createStore(CollectionConfig config);

    @BeforeEach
    void setUpStore() {
        store = createStore(CollectionConfig.of("contract", DIM, DistanceMetric.EUCLIDEAN));
    }

    protected static VectorEntry entry(String id, double... v) {
        return new VectorEntry(id, v, ("payload-" + id).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    void putThenGet() {
        store.put(entry("a", 1, 2, 3));
        VectorEntry got = store.getById("a").orElseThrow();
        assertEquals("a", got.id());
        assertArrayEquals(new double[]{1, 2, 3}, got.vector());
        assertArrayEquals("payload-a".getBytes(StandardCharsets.UTF_8), got.data());
        assertEquals(1, store.size());
    }

    @Test
    void getMissingReturnsEmpty() {
        assertTrue(store.getById("nope").isEmpty());
    }

    @Test
    void putOverwritesSameId() {
        store.put(entry("a", 1, 1, 1));
        store.put(entry("a", 2, 2, 2));
        assertEquals(1, store.size());
        assertArrayEquals(new double[]{2, 2, 2}, store.getById("a").orElseThrow().vector());
    }

    @Test
    void deleteAndExists() {
        store.put(entry("a", 1, 1, 1));
        assertTrue(store.exists("a"));
        assertTrue(store.delete("a"));
        assertFalse(store.exists("a"));
        assertFalse(store.delete("a"));
        assertEquals(0, store.size());
    }

    @Test
    void clearRemovesEverything() {
        store.put(entry("a", 1, 1, 1));
        store.put(entry("b", 2, 2, 2));
        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.getNearest(new double[]{1, 1, 1}, 5).isEmpty());
    }

    @Test
    void nullPayloadAllowed() {
        store.put(new VectorEntry("a", new double[]{1, 2, 3}, null));
        assertEquals(null, store.getById("a").orElseThrow().data());
    }

    // ------------------------------------------------------------------- k-NN

    @Test
    void nearestNeighbour() {
        store.put(entry("x", 10, 0, 0));
        store.put(entry("y", 0, 10, 0));
        store.put(entry("z", 0, 0, 10));
        List<SearchResult> r = store.getNearest(new double[]{9, 1, 0}, 1);
        assertEquals(1, r.size());
        assertEquals("x", r.get(0).entry().id());
    }

    @Test
    void searchRespectsKAndOrder() {
        for (int i = 0; i < 10; i++) {
            store.put(entry("v" + i, i, 0, 0));
        }
        List<SearchResult> r = store.getNearest(new double[]{0, 0, 0}, 3);
        assertEquals(List.of("v0", "v1", "v2"), r.stream().map(x -> x.entry().id()).toList());
        assertTrue(r.get(0).distance() <= r.get(1).distance() && r.get(1).distance() <= r.get(2).distance());
    }

    @Test
    void searchOnEmptyStore() {
        assertTrue(store.getNearest(new double[]{1, 2, 3}, 5).isEmpty());
    }

    @Test
    void deletedEntryNotFoundBySearch() {
        store.put(entry("a", 1, 1, 1));
        store.put(entry("b", 5, 5, 5));
        store.delete("a");
        assertEquals("b", store.getNearest(new double[]{1, 1, 1}, 1).get(0).entry().id());
    }

    @Test
    void invalidKRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.getNearest(new double[]{1, 2, 3}, 0));
    }

    // ------------------------------------------------------------- validation

    @Test
    void wrongDimensionOnPut() {
        assertThrows(DimensionMismatchException.class, () -> store.put(entry("a", 1, 2)));
        assertEquals(0, store.size());
    }

    @Test
    void wrongDimensionOnSearch() {
        assertThrows(DimensionMismatchException.class, () -> store.getNearest(new double[]{1, 2, 3, 4}, 1));
    }

    @Test
    void nonFiniteValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.put(entry("a", 1, Double.NaN, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> store.getNearest(new double[]{1, Double.POSITIVE_INFINITY, 3}, 1));
    }

    @Test
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.put(entry(" ", 1, 2, 3)));
        assertThrows(InvalidIdException.class, () -> store.getById(" "));
        assertThrows(InvalidIdException.class, () -> store.delete(null));
    }

    @Test
    void schemaExposed() {
        assertEquals("contract", store.name());
        assertEquals(DIM, store.dimension());
        assertEquals(DistanceMetric.EUCLIDEAN, store.metric());
    }

    @Test
    void statsCountOperations() {
        store.put(entry("a", 1, 1, 1));
        store.getById("a");
        store.getById("missing");
        store.getNearest(new double[]{1, 1, 1}, 1);
        var s = store.stats();
        assertEquals(1, s.liveVectors());
        assertEquals(1, s.totalPuts());
        assertEquals(1, s.totalSearches());
        assertEquals(1, s.hits());
        assertEquals(1, s.misses());
        assertEquals(0.5, s.hitRatio(), 1e-12);
    }
}
