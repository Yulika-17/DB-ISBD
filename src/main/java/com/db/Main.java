package com.db;

import com.db.config.DbConfig;
import com.db.config.DistanceMetric;
import com.db.model.VectorEntry;

import java.nio.charset.StandardCharsets;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        DbConfig config = DbConfig.builder()
                .dataDirectory("./data")
                .dimension(4)
                .metric(DistanceMetric.COSINE)
                .maxVectors(100_000)
                .saveInterval(java.time.Duration.ofSeconds(5))
                .build();

        System.out.println("lab-db skeleton (vector)");
        System.out.println("  dataDirectory : " + config.dataDirectory());
        System.out.println("  dimension     : " + config.dimension());
        System.out.println("  metric        : " + config.metric());
        System.out.println("  maxVectors    : " + config.maxVectors());
        System.out.println("  saveInterval  : " + config.saveInterval());

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
