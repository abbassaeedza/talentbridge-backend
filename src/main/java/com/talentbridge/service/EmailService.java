package com.talentbridge.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final Resend resend;
    private final String fromEmail;

    public EmailService(@Value("${resend.api-key:}") String apiKey,
                        @Value("${resend.from-email:}") String fromEmail) {
        this(apiKey == null || apiKey.isBlank() ? null : new Resend(apiKey), fromEmail);
    }

    EmailService(Resend resend, String fromEmail) {
        this.resend = resend;
        this.fromEmail = fromEmail;
    }

    public void send(String recipient, String subject, String message) {
        if (resend == null || fromEmail == null || fromEmail.isBlank()) {
            log.debug("Resend is not configured - skipping email to {}.", recipient);
            return;
        }

        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(fromEmail)
                .to(recipient)
                .subject(subject)
                .text(message)
                .build();

        try {
            resend.emails().send(options);
        } catch (ResendException exception) {
            log.warn("Failed to send email to {}: {}", recipient, exception.getMessage());
        }
    }
}
