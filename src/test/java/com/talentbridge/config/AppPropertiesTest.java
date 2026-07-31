package com.talentbridge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPropertiesTest {

    @Test
    void capsDatabaseConnectionsForOverlappingCloudRunRevisions() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertEquals("5", context.getEnvironment()
                            .getProperty("spring.datasource.hikari.maximum-pool-size"));
                    assertEquals("0", context.getEnvironment()
                            .getProperty("spring.datasource.hikari.minimum-idle"));
                });
    }

    @Test
    void bindsPartyLimitsFromEnvironment() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(AppProperties.class)
                .withSystemProperties("PARTY_MIN_SIZE=4", "PARTY_MAX_SIZE=6")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertEquals(4, properties.getParty().getMinSize());
                    assertEquals(6, properties.getParty().getMaxSize());
                });
    }
}
