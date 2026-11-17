package com.talentbridge.config;

import com.talentbridge.service.AuthService;
import com.talentbridge.service.GitHubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemoRuntimeConfigBindingTest {
    @Test
    void realYamlUsesTheIntendedEnvironmentPlaceholders() throws Exception {
        var source = new YamlPropertySourceLoader().load("real-yaml", new ClassPathResource("application.yml")).get(0);
        assertEquals("${APP_DEMO_MODE:false}", source.getProperty("app.demo-mode"));
        assertEquals("${APP_SEED_RESET_DEMO_DATA:false}", source.getProperty("app.seed.reset-demo-data"));
        assertEquals("${GITHUB_REDIRECT_URI:http://localhost:3000/github/callback}",
                source.getProperty("github.oauth.redirect-uri"));
    }

    private MockEnvironment environment(Map<String, Object> variables) throws IOException {
        // MockEnvironment excludes real process variables and system properties.
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("synthetic-env", variables));
        for (var source : new YamlPropertySourceLoader().load("real-yaml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    void demoAndResetDefaultOffInRealYaml() throws Exception {
        var environment = environment(Map.of());
        assertEquals(false, environment.getRequiredProperty("app.demo-mode", Boolean.class));
        assertEquals(false, environment.getRequiredProperty("app.seed.reset-demo-data", Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void demoEnvironmentFlagResolvesThroughTheCanonicalConsumerProperty(String value) throws Exception {
        var environment = environment(Map.of("APP_DEMO_MODE", value));
        boolean expected = Boolean.parseBoolean(value);
        assertEquals(expected, environment.getRequiredProperty("app.demo-mode", Boolean.class));
        for (Class<?> consumer : new Class<?>[]{AuthService.class, DataSeeder.class}) {
            String expression = consumer.getDeclaredField("demoMode").getAnnotation(Value.class).value();
            assertEquals("${app.demo-mode:false}", expression);
            assertEquals(value, environment.resolveRequiredPlaceholders(expression));
        }
        assertEquals(false, environment.getRequiredProperty("app.seed.reset-demo-data", Boolean.class));
    }

    @Test
    void invalidDemoEnvironmentFlagFailsConversionRatherThanEnablingDemo() throws Exception {
        var environment = environment(Map.of("APP_DEMO_MODE", "invalid-boolean"));
        assertThrows(org.springframework.core.convert.ConversionFailedException.class,
                () -> environment.getRequiredProperty("app.demo-mode", Boolean.class));
    }

    @Test
    void githubRedirectEnvironmentVariableResolvesThroughRealYaml() throws Exception {
        String redirect = "https://synthetic.example.test/settings/github/callback";
        var environment = environment(Map.of("GITHUB_REDIRECT_URI", redirect));
        assertEquals(redirect, environment.getRequiredProperty("github.oauth.redirect-uri"));
        String expression = GitHubService.class.getDeclaredField("redirectUri").getAnnotation(Value.class).value();
        assertEquals("${github.oauth.redirect-uri}", expression);
        assertEquals(redirect, environment.resolveRequiredPlaceholders(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void resetEnvironmentFlagHasItsOwnPlaceholderWithoutRunningAnySeeder(String value) throws Exception {
        var environment = environment(Map.of("APP_SEED_RESET_DEMO_DATA", value));
        assertEquals(Boolean.parseBoolean(value),
                environment.getRequiredProperty("app.seed.reset-demo-data", Boolean.class));
        assertEquals(false, environment.getRequiredProperty("app.demo-mode", Boolean.class));
        String expression = DemoDataSeeder.class.getDeclaredField("resetDemoData").getAnnotation(Value.class).value();
        assertEquals("${app.seed.reset-demo-data:false}", expression);
        assertEquals(value, environment.resolveRequiredPlaceholders(expression));
    }
}
