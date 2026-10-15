package com.db.index;

import com.db.api.IIndex;
import com.db.config.DistanceMetric;
import com.db.distance.DistanceFunctions;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Точный k-NN полным перебором: O(n·d) на запрос, O(n·log k) на отбор top-k через кучу.
 * Не потокобезопасен — синхронизацию обеспечивает владелец (коллекция).
 */
public final class BruteForceIndex implements IIndex {

    /** Порядок результатов: по расстоянию, при равенстве — по id (детерминированно). */
    private static final Comparator<SearchResult> BY_DISTANCE =
            Comparator.comparingDouble(SearchResult::distance)
                    .thenComparing(r -> r.entry().id());

    private final DistanceMetric metric;
    private final Map<String, VectorEntry> entries = new HashMap<>();

    public BruteForceIndex(DistanceMetric metric) {
        this.metric = Objects.requireNonNull(metric, "metric");
    }

    public DistanceMetric metric() {
        return metric;
    }

    @Override
    public void add(VectorEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.id(), entry);
    }

    @Override
    public boolean remove(String id) {
        return entries.remove(id) != null;
    }

    @Override
    public List<SearchResult> getNearest(double[] query, int k) {
        Objects.requireNonNull(query, "query");
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }
        // max-heap размера k: в вершине — самый дальний из текущих кандидатов
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(k + 1, BY_DISTANCE.reversed());
        for (VectorEntry e : entries.values()) {
            double d = DistanceFunctions.compute(query, e.vector(), metric);
            SearchResult candidate = new SearchResult(e, d);
            if (heap.size() < k) {
                heap.add(candidate);
            } else if (BY_DISTANCE.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.add(candidate);
            }
        }
        List<SearchResult> result = new ArrayList<>(heap);
        result.sort(BY_DISTANCE);
        return result;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
