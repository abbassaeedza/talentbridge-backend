package com.talentbridge.service;

import com.talentbridge.entity.Notification;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.User;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.repository.NotificationRepository;
import com.talentbridge.repository.NotificationPreferenceRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private NotificationPreferenceRepository notificationPreferenceRepository;
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

    @Test
    void keepsInAppNotificationWhenItsEmailPreferenceIsDisabled() {
        when(notificationPreferenceRepository.findByUserIdAndType(any(), any()))
                .thenReturn(Optional.of(com.talentbridge.entity.NotificationPreference.builder()
                        .emailEnabled(false).build()));

        service.send(recipient, NotificationType.GENERAL, "Title", "Message");

        verify(notificationRepository).save(any(Notification.class));
        verify(emailService, never()).send(any(), any(), any());
    }

    @Test
    void usesTheStudentsIndividualScoreInEvaluationNotifications() {
        recipient.setId(UUID.randomUUID());
        EvaluationReport report = EvaluationReport.builder()
                .totalScore(63.0)
                .studentScores(List.of(StudentEvaluationScore.builder()
                        .student(recipient)
                        .individualScore(54.0)
                        .build()))
                .build();
        report.setId(UUID.randomUUID());
        Party party = Party.builder().members(Set.of(recipient)).build();
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);

        service.notifyEvaluationComplete(party, report);

        verify(notificationRepository).save(notification.capture());
        assertEquals(
                "Your score: 54.0/100. Team project score: 63.0/100.",
                notification.getValue().getMessage());
    }
}
