package com.db.index;

import com.db.config.DistanceMetric;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruteForceIndexTest {

    private static VectorEntry e(String id, double... v) {
        return new VectorEntry(id, v, null);
    }

    @Test
    void returnsKNearestSortedByDistance() {
        BruteForceIndex idx = new BruteForceIndex(DistanceMetric.EUCLIDEAN);
        idx.add(e("far", 10, 10));
        idx.add(e("near", 1, 0));
        idx.add(e("mid", 3, 0));
        idx.add(e("origin", 0, 0));

        List<SearchResult> r = idx.getNearest(new double[]{0, 0}, 3);
        assertEquals(List.of("origin", "near", "mid"), r.stream().map(x -> x.entry().id()).toList());
        assertEquals(0.0, r.get(0).distance(), 1e-12);
        assertEquals(3.0, r.get(2).distance(), 1e-12);
    }

    @Test
    void kLargerThanSizeReturnsAll() {
        BruteForceIndex idx = new BruteForceIndex(DistanceMetric.COSINE);
        idx.add(e("a", 1, 0));
        assertEquals(1, idx.getNearest(new double[]{1, 1}, 10).size());
        assertTrue(new BruteForceIndex(DistanceMetric.COSINE).getNearest(new double[]{1, 1}, 5).isEmpty());
    }

    @Test
    void tiesBrokenById() {
        BruteForceIndex idx = new BruteForceIndex(DistanceMetric.EUCLIDEAN);
        idx.add(e("b", 1, 0));
        idx.add(e("a", 0, 1));
        idx.add(e("c", -1, 0));
        assertEquals(List.of("a", "b"), idx.getNearest(new double[]{0, 0}, 2).stream().map(x -> x.entry().id()).toList());
    }

    @Test
    void addReplacesAndRemoveWorks() {
        BruteForceIndex idx = new BruteForceIndex(DistanceMetric.EUCLIDEAN);
        idx.add(e("a", 0, 0));
        idx.add(e("a", 5, 5));
        assertEquals(1, idx.size());
        assertEquals(5.0, idx.getNearest(new double[]{5, 5}, 1).get(0).entry().vector()[0], 0);
        assertTrue(idx.remove("a"));
        assertFalse(idx.remove("a"));
        assertEquals(0, idx.size());
    }

    @Test
    void invalidKRejected() {
        BruteForceIndex idx = new BruteForceIndex(DistanceMetric.EUCLIDEAN);
        assertThrows(IllegalArgumentException.class, () -> idx.getNearest(new double[]{0}, 0));
    }

    @Test
    void matchesFullSortOnRandomData() {
        Random rnd = new Random(42);
        for (DistanceMetric m : DistanceMetric.values()) {
            BruteForceIndex idx = new BruteForceIndex(m);
            List<VectorEntry> all = new java.util.ArrayList<>();
            for (int i = 0; i < 500; i++) {
                VectorEntry v = e("v" + i, rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
                all.add(v);
                idx.add(v);
            }
            double[] q = {rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()};
            List<String> expected = all.stream()
                    .sorted(java.util.Comparator.comparingDouble((VectorEntry v) ->
                            com.db.distance.DistanceFunctions.compute(q, v.vector(), m)).thenComparing(VectorEntry::id))
                    .limit(10).map(VectorEntry::id).toList();
            assertEquals(expected, idx.getNearest(q, 10).stream().map(r -> r.entry().id()).toList(), m.name());
        }
    }
}
