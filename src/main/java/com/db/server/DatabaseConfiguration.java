package com.db.server;

import com.db.api.IVectorDB;
import com.db.config.DbConfig;
import com.db.config.NodeRole;
import com.db.storage.VectorDBFactory;
import com.db.storage.ShardedVectorDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Configuration
public class DatabaseConfiguration {

    @Bean(destroyMethod = "close")
    IVectorDB vectorDB(
            @Value("${vecdb.name}") String name,
            @Value("${vecdb.data-directory}") String dataDirectory,
            @Value("${vecdb.auto-save}") boolean autoSave,
            @Value("${vecdb.save-interval}") Duration saveInterval,
            @Value("${vecdb.role:STANDALONE}") String role,
            @Value("${vecdb.replication-port:4700}") int replicationPort,
            @Value("${vecdb.master-host:127.0.0.1}") String masterHost,
            @Value("${vecdb.master-port:4700}") int masterPort,
            @Value("${vecdb.replication-delay-ms:0}") long replicationDelayMs,
            @Value("${vecdb.shards:1}") int shardCount
    ) {
        DbConfig config = DbConfig.builder()
                .name(name)
                .dataDirectory(dataDirectory)
                .autoSave(autoSave)
                .saveInterval(saveInterval)
                .role(NodeRole.valueOf(role.trim().toUpperCase(Locale.ROOT)))
                .replicationPort(replicationPort)
                .masterHost(masterHost)
                .masterPort(masterPort)
                .replicationDelayMs(replicationDelayMs)
                .build();
        if (shardCount > 1) return ShardedVectorDB.open(config, shardCount);
        if (Files.exists(Path.of(dataDirectory, "shards.count"))) {
            throw new IllegalArgumentException("cannot disable sharding by setting VECDB_SHARDS=1; shard reduction requires a data migration");
        }
        return VectorDBFactory.open(config);
    }
}
