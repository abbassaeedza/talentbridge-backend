package com.talentbridge.config;
import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration @ConfigurationProperties(prefix = "app") @Getter @Setter
public class AppProperties {
    private String frontendUrl;
    private String fromEmail;
    private String fromName;
    private Party party = new Party();
    private Supervisor supervisor = new Supervisor();

    @Getter @Setter public static class Party { private int minSize = 2; private int maxSize = 3; }
    @Getter @Setter public static class Supervisor { private int maxPartiesPerSemester = 2; }
}
