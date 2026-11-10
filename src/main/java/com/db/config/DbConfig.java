package com.db.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Глобальные настройки инстанса БД. Всё, что относится к схеме данных
 * (размерность, метрика, лимит записей), задаётся per-collection в {@link CollectionConfig}.
 */
public final class DbConfig {

    private final String name;
    private final String dataDirectory;
    private final boolean autoSave;
    private final Duration saveInterval;

    private DbConfig(Builder b) {
        this.name = b.name;
        this.dataDirectory = b.dataDirectory;
        this.autoSave = b.autoSave;
        this.saveInterval = b.saveInterval;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name()               { return name; }
    public String dataDirectory()      { return dataDirectory; }
    public boolean autoSave()          { return autoSave; }
    public Duration saveInterval()     { return saveInterval; }

    public Path dataPath() {
        return Path.of(dataDirectory);
    }

    public Path dataPath(String fileName) {
        return Path.of(dataDirectory, fileName);
    }

    /** Каталог коллекции: {@code <dataDirectory>/<collectionName>}. */
    public Path collectionPath(String collectionName) {
        return Path.of(dataDirectory, CollectionConfig.validateName(collectionName));
    }

    public static final class Builder {
        private String name = "vecdb";
        private String dataDirectory = "./data";
        private boolean autoSave = true;
        private Duration saveInterval = Duration.ofSeconds(5);

        public Builder name(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name cannot be blank");
            this.name = name;
            return this;
        }

        public Builder dataDirectory(String dir) {
            this.dataDirectory = Objects.requireNonNull(dir, "dataDirectory");
            return this;
        }

        /** Фоновое сохранение изменённых коллекций раз в {@link #saveInterval(Duration)}. */
        public Builder autoSave(boolean enabled) {
            this.autoSave = enabled;
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
