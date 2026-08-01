/*
 * Copyright 2025
 */

package org.secretflow.secretpad.persistence.configuration;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.util.List;

/**
 * Flyway config for BFIA DB, activates only if bfia datasource url is configured.
 * Uses @ConditionalOnProperty (same as BfiaDataSourceConfig) instead of @ConditionalOnBean
 * to avoid bean ordering issues.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaFlywayConfig {

    @Bean
    @ConfigurationProperties(prefix = "flyway.bfia")
    public FlywayProperties bfiaFlywayProperties() {
        return new FlywayProperties();
    }

    @Bean
    public Flyway bfiaFlyway(@Qualifier("bfiaDataSource") DataSource bfiaDataSource) {
        FlywayProperties props = bfiaFlywayProperties();
        List<String> locs = props.getLocations();
        if (locs == null || locs.isEmpty()) {
            locs = List.of("classpath:db/bfia");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(bfiaDataSource)
                .locations(locs.toArray(new String[0]))
                .baselineOnMigrate(true)
                // 方案A：放宽校验 + 自动修复（只保留通用可用项）
                .validateOnMigrate(false)
                .load();
        try {
            flyway.repair();
        } catch (Exception ignored) {
        }
        flyway.migrate();
        return flyway;
    }
}


