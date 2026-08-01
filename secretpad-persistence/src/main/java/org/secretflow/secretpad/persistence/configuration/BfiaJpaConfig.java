/*
 * Copyright 2025
 */

package org.secretflow.secretpad.persistence.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;

/**
 * JPA config for BFIA repositories and entities.
 * Uses @ConditionalOnProperty (same as BfiaDataSourceConfig) instead of @ConditionalOnBean
 * to avoid bean ordering issues — @ConditionalOnBean is unreliable in regular @Configuration
 * classes because the target bean may not have been registered yet when the condition is
 * evaluated.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.bfia", name = "url")
@EnableJpaRepositories(
        basePackages = "org.secretflow.secretpad.persistence.repository.bfia",
        entityManagerFactoryRef = "bfiaEntityManagerFactory",
        transactionManagerRef = "bfiaTransactionManager"
)
public class BfiaJpaConfig {

    @Bean(name = "bfiaEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean bfiaEntityManagerFactory(@Qualifier("bfiaDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("org.secretflow.secretpad.persistence.entity.bfia");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(new HashMap<>());
        return emf;
    }

    @Bean(name = "bfiaTransactionManager")
    public JpaTransactionManager bfiaTransactionManager(@Qualifier("bfiaEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}


