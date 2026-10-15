package com.db;

import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.config.DistanceMetric;
import com.db.model.VectorEntry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        DbConfig config = DbConfig.builder()
                .dataDirectory("./data")
                .saveInterval(Duration.ofSeconds(5))
                .build();

        CollectionConfig docs = CollectionConfig.builder("docs", 4)
                .metric(DistanceMetric.COSINE)
                .maxVectors(100_000)
                .build();

        System.out.println("lab-db skeleton (vector)");
        System.out.println("  dataDirectory : " + config.dataDirectory());
        System.out.println("  saveInterval  : " + config.saveInterval());
        System.out.println();
        System.out.println("Collection '" + docs.name() + "':");
        System.out.println("  path       : " + config.collectionPath(docs.name()));
        System.out.println("  dimension  : " + docs.dimension());
        System.out.println("  metric     : " + docs.metric());
        System.out.println("  maxVectors : " + docs.maxVectors());

        VectorEntry example = new VectorEntry(
                "example-1",
                new double[]{0.1, 0.2, 0.3, 0.4},
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        System.out.println();
        System.out.println("Sample entry:");
        System.out.println("  id        : " + example.id());
        System.out.println("  dimension : " + example.dimension());
        System.out.println("  data      : " + new String(example.data(), StandardCharsets.UTF_8));

        System.out.println();
        System.out.println("No storage engine yet - see lab 2.");
    }
}
