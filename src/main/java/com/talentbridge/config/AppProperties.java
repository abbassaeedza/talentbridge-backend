package com.talentbridge.config;
import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration @ConfigurationProperties(prefix = "app") @Getter @Setter
public class AppProperties {
    private Party party = new Party();

    @Getter @Setter public static class Party { private int minSize = 2; private int maxSize = 3; }
}
