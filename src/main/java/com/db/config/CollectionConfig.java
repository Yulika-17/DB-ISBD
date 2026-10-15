package com.db.config;

import com.db.exception.InvalidCollectionNameException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Схема коллекции. Задаётся один раз при создании и дальше не меняется:
 * от размерности и метрики зависят и валидация векторов, и структура индекса.
 */
public record CollectionConfig(
        String name,
        int dimension,
        DistanceMetric metric,
        int maxVectors
) {

    public static final int MAX_NAME_LENGTH = 64;

    /** Имя коллекции становится именем каталога на диске, поэтому набор символов ограничен. */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_NAME_LENGTH + "}");

    public CollectionConfig {
        validateName(name);
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be >= 1");
        }
        Objects.requireNonNull(metric, "metric");
        if (maxVectors < 0) {
            throw new IllegalArgumentException("maxVectors must be >= 0");
        }
    }

    public static CollectionConfig of(String name, int dimension, DistanceMetric metric) {
        return new CollectionConfig(name, dimension, metric, 0);
    }

    /** Имя и размерность обязательны: у размерности нет разумного значения по умолчанию. */
    public static Builder builder(String name, int dimension) {
        return new Builder(name, dimension);
    }

    public static String validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new InvalidCollectionNameException(name);
        }
        return name;
    }

    public static final class Builder {
        private final String name;
        private final int dimension;
        private DistanceMetric metric = DistanceMetric.COSINE;
        private int maxVectors = 0;

        private Builder(String name, int dimension) {
            this.name = name;
            this.dimension = dimension;
        }

        public Builder metric(DistanceMetric m) {
            this.metric = Objects.requireNonNull(m, "metric");
            return this;
        }

        /** 0 — без лимита. */
        public Builder maxVectors(int max) {
            this.maxVectors = max;
            return this;
        }

        public CollectionConfig build() {
            return new CollectionConfig(name, dimension, metric, maxVectors);
        }
    }
}
