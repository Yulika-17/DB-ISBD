package com.db.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class DbConfig {

    private final String dataDirectory;
    private final int dimension;
    private final DistanceMetric metric;
    private final int maxVectors;
    private final Duration saveInterval;

    private DbConfig(Builder b) {
        this.dataDirectory = b.dataDirectory;
        this.dimension = b.dimension;
        this.metric = b.metric;
        this.maxVectors = b.maxVectors;
        this.saveInterval = b.saveInterval;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String dataDirectory()      { return dataDirectory; }
    public int dimension()             { return dimension; }
    public DistanceMetric metric()     { return metric; }
    public int maxVectors()            { return maxVectors; }
    public Duration saveInterval()     { return saveInterval; }

    public Path dataPath(String fileName) {
        return Path.of(dataDirectory, fileName);
    }

    public static final class Builder {
        private String dataDirectory = "./data";
        private int dimension = 128;
        private DistanceMetric metric = DistanceMetric.COSINE;
        private int maxVectors = 0;
        private Duration saveInterval = Duration.ofSeconds(5);

        public Builder dataDirectory(String dir) {
            this.dataDirectory = Objects.requireNonNull(dir, "dataDirectory");
            return this;
        }

        public Builder dimension(int d) {
            if (d < 1) throw new IllegalArgumentException("dimension must be >= 1");
            this.dimension = d;
            return this;
        }

        public Builder metric(DistanceMetric m) {
            this.metric = Objects.requireNonNull(m, "metric");
            return this;
        }

        public Builder maxVectors(int max) {
            if (max < 0) throw new IllegalArgumentException("maxVectors must be >= 0");
            this.maxVectors = max;
            return this;
        }

        public Builder saveInterval(Duration interval) {
            Objects.requireNonNull(interval, "saveInterval");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("saveInterval must be > 0");
            }
            this.saveInterval = interval;
            return this;
        }

        public DbConfig build() {
            return new DbConfig(this);
        }
    }
}
