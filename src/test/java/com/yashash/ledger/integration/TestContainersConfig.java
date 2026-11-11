package com.yashash.ledger.integration;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers setup for all integration tests.
 * Containers are started once per test run (static) for speed.
 */
public class TestContainersConfig {

    // Static fields so containers are shared across all integration test classes
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers()
            ).applyTo(ctx.getEnvironment());
        }
    }
}
