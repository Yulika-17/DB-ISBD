package com.db.rest.dto;

import com.db.model.SearchResult;

public record SearchResponse(VectorResponse entry, double distance) {

    public static SearchResponse from(SearchResult result) {
        return new SearchResponse(VectorResponse.from(result.entry()), result.distance());
    }
}
