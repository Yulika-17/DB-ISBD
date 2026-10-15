package com.db.storage;

import com.db.api.ICollection;
import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCollectionTest extends VectorStoreContractTest {

    @Override
    protected ICollection createStore(CollectionConfig config) {
        return new InMemoryCollection(config);
    }

    @Test
    void storedDataIsIsolatedFromCallerArrays() {
        double[] v = {1, 2, 3};
        byte[] data = {7};
        store.put(new VectorEntry("a", v, data));
        v[0] = 100;
        data[0] = 0;
        VectorEntry got = store.getById("a").orElseThrow();
        assertArrayEquals(new double[]{1, 2, 3}, got.vector());
        assertArrayEquals(new byte[]{7}, got.data());

        got.vector()[1] = 100; // изменение выданной копии не влияет на БД
        assertArrayEquals(new double[]{1, 2, 3}, store.getById("a").orElseThrow().vector());
    }

    @Test
    void fifoEvictionWhenFull() {
        ICollection c = new InMemoryCollection(
                CollectionConfig.builder("small", 1).metric(DistanceMetric.EUCLIDEAN).maxVectors(2).build());
        c.put(new VectorEntry("a", new double[]{1}, null));
        c.put(new VectorEntry("b", new double[]{2}, null));
        c.put(new VectorEntry("a", new double[]{1.5}, null)); // перезапись: размер не растёт, «a» становится новее «b»
        assertEquals(2, c.size());
        c.put(new VectorEntry("c", new double[]{3}, null));   // вытесняет самую старую — «b»
        assertEquals(2, c.size());
        assertFalse(c.exists("b"));
        assertTrue(c.exists("a") && c.exists("c"));
        assertEquals(1, c.stats().evictedVectors());
        assertEquals(List.of("a", "c"),
                c.getNearest(new double[]{0}, 10).stream().map(r -> r.entry().id()).toList());
    }

    @Test
    void versionChangesOnlyOnMutation() {
        InMemoryCollection c = (InMemoryCollection) store;
        long v0 = c.version();
        c.getById("x");
        c.getNearest(new double[]{0, 0, 0}, 1);
        c.clear(); // пустая — изменений нет
        assertEquals(v0, c.version());
        c.put(entry("a", 1, 1, 1));
        assertTrue(c.version() > v0);
    }

    @Test
    void concurrentWritersAndReaders() throws Exception {
        int threads = 8, perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int tt = t;
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        store.put(entry("t" + tt + "-" + i, tt, i, 1));
                        store.getNearest(new double[]{tt, i, 1}, 3);
                        if (i % 5 == 0) {
                            store.delete("t" + tt + "-" + (i / 2));
                        }
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // хранилище и индекс согласованы: поиск с k = size возвращает ровно size записей
        int size = (int) store.size();
        assertEquals(size, store.getNearest(new double[]{0, 0, 0}, size + 10).size());
        assertEquals((long) threads * perThread, store.stats().totalPuts());
    }
}
