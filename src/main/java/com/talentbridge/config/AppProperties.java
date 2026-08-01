package com.talentbridge.config;
import lombok.*;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration @ConfigurationProperties(prefix = "app") @Validated @Getter @Setter
public class AppProperties {
    private Party party = new Party();

    @Getter @Setter
    public static class Party {
        @Min(1)
        private int minSize = 2;
        @Min(1)
        private int maxSize = 3;
        @Min(1)
        private int supervisorMaxParties = 2;

        @AssertTrue(message = "party minimum size must not exceed maximum size")
        public boolean isSizeRangeValid() {
            return minSize <= maxSize;
        }
    }
}
