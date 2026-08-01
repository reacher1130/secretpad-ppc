/*
 * Copyright 2025
 */

package org.secretflow.secretpad.persistence.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * BFIA datasource config. Enabled only when spring.datasource.bfia.* is provided.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
public class BfiaDataSourceConfig {

    @Bean(name = "bfiaDataSourceProperties")
    @ConfigurationProperties("spring.datasource.bfia")
    public DataSourceProperties bfiaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "bfiaDataSource")
    public DataSource bfiaDataSource(@Qualifier("bfiaDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource hikariDataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        hikariDataSource.setMaximumPoolSize(50);
        hikariDataSource.setMinimumIdle(5);
        hikariDataSource.setConnectionTimeout(20000);
        hikariDataSource.setIdleTimeout(60000);
        return hikariDataSource;
    }
}


