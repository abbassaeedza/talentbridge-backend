package com.talentbridge.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNull;

class EmailServiceSpringContextTest {

    @Test
    void createsEmailServiceBeanWithConfiguredConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmailService.class)
                .withPropertyValues("resend.api-key=", "resend.from-email=")
                .run(context -> assertNull(context.getStartupFailure()));
    }
}
