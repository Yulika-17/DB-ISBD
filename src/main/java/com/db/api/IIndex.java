package com.db.api;

import com.db.model.SearchResult;
import com.db.model.VectorEntry;

import java.util.List;

public interface IIndex {

    void add(VectorEntry entry);

    boolean remove(String id);

    List<SearchResult> getNearest(double[] query, int k);

    int size();

    void clear();
}
