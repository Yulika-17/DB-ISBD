package com.db.distance;

import com.db.config.DistanceMetric;

import java.util.Objects;

public final class DistanceFunctions {

    private DistanceFunctions() {
    }

    public static double compute(double[] a, double[] b, DistanceMetric metric) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(metric, "metric");
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vector dimensions differ: " + a.length + " vs " + b.length);
        }
        return switch (metric) {
            case COSINE -> cosine(a, b);
            case EUCLIDEAN -> euclidean(a, b);
            case DOT_PRODUCT -> dotProduct(a, b);
        };
    }

    public static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 1.0;
        }
        return 1.0 - dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static double euclidean(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    public static double dotProduct(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return -sum;
    }
}
