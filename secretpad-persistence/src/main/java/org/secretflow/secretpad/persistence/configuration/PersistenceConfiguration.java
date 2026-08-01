/*
 * Copyright 2023 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.persistence.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;

/**
 * Configuration for persistence layer
 *
 * @author yansi
 * @date 2023/5/9
 */
@Slf4j
@EntityScan(basePackages = "org.secretflow.secretpad.persistence.*")
@EnableJpaRepositories(
        basePackages = {"org.secretflow.secretpad.persistence.*"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "org\\.secretflow\\.secretpad\\.persistence\\.repository\\.bfia\\..*"
        )
)
@Configuration
public class PersistenceConfiguration {

    /**
     * Standard (non-BFIA) EntityManagerFactory bound to the default datasource.
     * Explicitly defined here because the BFIA datasource activates
     * {@link BfiaJpaConfig} which registers its own
     * {@code bfiaEntityManagerFactory}; that disables Spring Boot's
     * {@code HibernateAutoConfiguration} (it backs off when any
     * {@link LocalContainerEntityManagerFactoryBean} exists), so the default
     * {@code entityManagerFactory} bean is no longer auto-created.
     */
    @Primary
    @Bean(name = "entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(@Qualifier("defaultDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("org.secretflow.secretpad.persistence.entity");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        // Spring Boot's CamelCaseToUnderscoresNamingStrategy is not auto-applied
        // when we manually create the EMF (HibernateAutoConfiguration backs off
        // because a LocalContainerEntityManagerFactoryBean already exists).
        // Set it explicitly so entity fields without @Column(name=...) are
        // correctly mapped from camelCase to snake_case (e.g. controlNodeId →
        // control_node_id).
        java.util.Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        emf.setJpaPropertyMap(jpaProps);
        return emf;
    }

    @Primary
    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(@Qualifier("entityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public DataSourceInitializer dataSourceInitializer(@Qualifier("defaultDataSource") DataSource dataSource) {
        log.info("making sure database is WAL mode");
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL;");
            }
        });
        return initializer;
    }

}
