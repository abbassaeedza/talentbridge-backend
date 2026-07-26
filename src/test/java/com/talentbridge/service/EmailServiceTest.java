package com.talentbridge.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private Resend resend;
    @Mock private Emails emails;

    @Test
    void sendsConfiguredEmailThroughResend() throws Exception {
        when(resend.emails()).thenReturn(emails);
        EmailService service = new EmailService(resend, "TalentBridge <demo@example.com>");

        service.send("student@example.com", "Account approved", "Welcome");

        ArgumentCaptor<CreateEmailOptions> options = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(options.capture());
        assertEquals("TalentBridge <demo@example.com>", options.getValue().getFrom());
        assertEquals("student@example.com", options.getValue().getTo().get(0));
        assertEquals("Account approved", options.getValue().getSubject());
        assertEquals("Welcome", options.getValue().getText());
    }

    @Test
    void keepsApplicationActionSuccessfulWhenResendFails() throws Exception {
        when(resend.emails()).thenReturn(emails);
        when(emails.send(any())).thenThrow(new ResendException("delivery failed"));
        EmailService service = new EmailService(resend, "TalentBridge <demo@example.com>");

        assertDoesNotThrow(() -> service.send("student@example.com", "Title", "Message"));
    }

    @Test
    void skipsDeliveryWhenResendIsNotConfigured() throws Exception {
        EmailService service = new EmailService((Resend) null, "");

        service.send("student@example.com", "Title", "Message");

        verify(emails, never()).send(any());
    }
}
