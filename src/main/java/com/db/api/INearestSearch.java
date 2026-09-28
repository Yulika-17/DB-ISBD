package com.db.api;

import com.db.model.SearchResult;

import java.util.List;

public interface INearestSearch {

    List<SearchResult> getNearest(double[] query, int k);
}
