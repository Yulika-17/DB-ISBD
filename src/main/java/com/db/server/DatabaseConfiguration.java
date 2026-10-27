package com.db.server;

import com.db.api.IVectorDB;
import com.db.config.DbConfig;
import com.db.storage.InMemoryVectorDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DatabaseConfiguration {

    @Bean(destroyMethod = "close")
    IVectorDB vectorDB(
            @Value("${vecdb.name}") String name,
            @Value("${vecdb.data-directory}") String dataDirectory,
            @Value("${vecdb.auto-save}") boolean autoSave,
            @Value("${vecdb.save-interval}") Duration saveInterval
    ) {
        DbConfig config = DbConfig.builder()
                .name(name)
                .dataDirectory(dataDirectory)
                .autoSave(autoSave)
                .saveInterval(saveInterval)
                .build();
        return InMemoryVectorDB.open(config);
    }
}
