package com.db.api;

import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public abstract class VectorStoreContractTest {

    protected abstract IVectorDB createStore();

    protected static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    protected static double[] vec(double... values) {
        return values;
    }

    @Test
    void putAndGetById() {
        IVectorDB store = createStore();
        VectorEntry e = new VectorEntry("a", vec(1.0, 2.0), bytes("data-a"));
        store.put(e);
        Optional<VectorEntry> got = store.getById("a");
        assertTrue(got.isPresent());
        assertEquals("a", got.get().id());
        assertArrayEquals(vec(1.0, 2.0), got.get().vector());
        assertArrayEquals(bytes("data-a"), got.get().data());
        store.close();
    }

    @Test
    void getMissingReturnsEmpty() {
        IVectorDB store = createStore();
        assertTrue(store.getById("missing").isEmpty());
        store.close();
    }

    @Test
    void putOverwrites() {
        IVectorDB store = createStore();
        store.put(new VectorEntry("a", vec(1.0, 0.0), bytes("v1")));
        store.put(new VectorEntry("a", vec(0.0, 1.0), bytes("v2")));
        assertArrayEquals(vec(0.0, 1.0), store.getById("a").orElseThrow().vector());
        assertEquals(1, store.size());
        store.close();
    }

    @Test
    void deleteRemoves() {
        IVectorDB store = createStore();
        store.put(new VectorEntry("a", vec(1.0, 0.0), bytes("v")));
        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertFalse(store.exists("a"));
        store.close();
    }

    @Test
    void sizeAndClear() {
        IVectorDB store = createStore();
        store.put(new VectorEntry("a", vec(1.0, 0.0), bytes("1")));
        store.put(new VectorEntry("b", vec(0.0, 1.0), bytes("2")));
        assertEquals(2, store.size());
        store.clear();
        assertEquals(0, store.size());
        store.close();
    }

    @Test
    void getNearestReturnsClosest() {
        IVectorDB store = createStore();
        store.put(new VectorEntry("near", vec(1.0, 1.0), bytes("n")));
        store.put(new VectorEntry("far", vec(10.0, 10.0), bytes("f")));

        List<SearchResult> results = store.getNearest(vec(1.0, 1.0), 1);
        assertEquals(1, results.size());
        assertEquals("near", results.get(0).entry().id());
        store.close();
    }

    @Test
    void getNearestRespectsK() {
        IVectorDB store = createStore();
        store.put(new VectorEntry("a", vec(1.0, 0.0), bytes("a")));
        store.put(new VectorEntry("b", vec(0.0, 1.0), bytes("b")));
        store.put(new VectorEntry("c", vec(1.0, 1.0), bytes("c")));

        List<SearchResult> results = store.getNearest(vec(1.0, 1.0), 2);
        assertEquals(2, results.size());
        store.close();
    }

    @Test
    void getNearestOnEmptyReturnsEmpty() {
        IVectorDB store = createStore();
        assertTrue(store.getNearest(vec(1.0, 1.0), 5).isEmpty());
        store.close();
    }

    @Test
    void blankIdRejected() {
        IVectorDB store = createStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.put(new VectorEntry("", vec(1.0), bytes("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> store.put(new VectorEntry("   ", vec(1.0), bytes("x"))));
        store.close();
    }
}
