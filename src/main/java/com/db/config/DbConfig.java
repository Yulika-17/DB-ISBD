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
    private final NodeRole role;
    private final int replicationPort;
    private final String masterHost;
    private final int masterPort;
    private final long replicationDelayMs;

    private DbConfig(Builder b) {
        this.name = b.name;
        this.dataDirectory = b.dataDirectory;
        this.autoSave = b.autoSave;
        this.saveInterval = b.saveInterval;
        this.role = b.role;
        this.replicationPort = b.replicationPort;
        this.masterHost = b.masterHost;
        this.masterPort = b.masterPort;
        this.replicationDelayMs = b.replicationDelayMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name()               { return name; }
    public String dataDirectory()      { return dataDirectory; }
    public boolean autoSave()          { return autoSave; }
    public Duration saveInterval()     { return saveInterval; }
    public NodeRole role()             { return role; }
    public int replicationPort()       { return replicationPort; }
    public String masterHost()         { return masterHost; }
    public int masterPort()            { return masterPort; }
    public long replicationDelayMs()   { return replicationDelayMs; }

    public boolean replicationEnabled() {
        return role == NodeRole.MASTER || role == NodeRole.REPLICA;
    }

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
        private NodeRole role = NodeRole.STANDALONE;
        private int replicationPort = 4700;
        private String masterHost = "127.0.0.1";
        private int masterPort = 4700;
        private long replicationDelayMs = 0;

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

        public Builder role(NodeRole role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        /** Порт, на котором ведущий принимает ведомого. 0 — эфемерный порт (тесты). */
        public Builder replicationPort(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("replicationPort out of range: " + port);
            }
            this.replicationPort = port;
            return this;
        }

        public Builder masterHost(String host) {
            this.masterHost = Objects.requireNonNull(host, "masterHost");
            if (host.isBlank()) {
                throw new IllegalArgumentException("masterHost cannot be blank");
            }
            return this;
        }

        public Builder masterPort(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("masterPort out of range: " + port);
            }
            this.masterPort = port;
            return this;
        }

        public Builder replicationDelayMs(long delayMs) {
            if (delayMs < 0) {
                throw new IllegalArgumentException("replicationDelayMs must be >= 0");
            }
            this.replicationDelayMs = delayMs;
            return this;
        }

        public DbConfig build() {
            return new DbConfig(this);
        }
    }
}
