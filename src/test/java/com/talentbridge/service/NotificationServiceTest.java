package com.talentbridge.service;

import com.talentbridge.entity.Notification;
import com.talentbridge.entity.User;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.repository.NotificationRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @InjectMocks private NotificationService service;

    private User recipient;

    @BeforeEach
    void setUp() {
        recipient = User.builder()
                .email("student@example.com")
                .firstName("Test")
                .lastName("Student")
                .build();
    }

    @Test
    void emailsEveryBasicNotification() {
        service.send(recipient, NotificationType.GENERAL, "Title", "Message");

        verify(notificationRepository).save(any(Notification.class));
        verify(emailService).send("student@example.com", "Title", "Message");
    }

    @Test
    void emailsEveryReferencedNotification() {
        service.send(recipient, NotificationType.PROJECT_ASSIGNED,
                "Assigned", "Message", "id", "PROJECT");

        verify(notificationRepository).save(any(Notification.class));
        verify(emailService).send("student@example.com", "Assigned", "Message");
    }
}
