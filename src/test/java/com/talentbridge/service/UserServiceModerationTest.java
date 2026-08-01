package com.talentbridge.service;

import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.entity.User;
import com.talentbridge.entity.UserModerationEvent;
import com.talentbridge.enums.ModerationEventType;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.StudentProfileRepository;
import com.talentbridge.repository.UserModerationEventRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceModerationTest {

    @Mock private UserRepository userRepository;
    @Mock private StudentProfileRepository studentProfileRepository;
    @Mock private ScorecardRepository scorecardRepository;
    @Mock private UserModerationEventRepository moderationEventRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private UserService userService;

    @Test
    void recordsOneSuspensionEventPerStatusTransition() {
        UUID userId = UUID.randomUUID();
        UUID coordinatorId = UUID.randomUUID();
        User user = User.builder()
                .email(" Student@Example.COM ")
                .firstName("Demo")
                .lastName("Student")
                .role(UserRole.STUDENT)
                .status(UserStatus.APPROVED)
                .build();
        user.setId(userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(moderationEventRepository.countByNormalizedEmailAndEventType(
                "student@example.com", ModerationEventType.SUSPENDED)).thenReturn(1L);

        UserResponse first = userService.suspendUser(userId, coordinatorId);
        userService.suspendUser(userId, coordinatorId);

        ArgumentCaptor<UserModerationEvent> event = ArgumentCaptor.forClass(UserModerationEvent.class);
        verify(moderationEventRepository, times(1)).save(event.capture());
        assertEquals("student@example.com", event.getValue().getNormalizedEmail());
        assertEquals(ModerationEventType.SUSPENDED, event.getValue().getEventType());
        assertEquals(1L, first.getSuspensionCount());
    }

    @Test
    void deletesTheScorecardBeforeARejectedStudent() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .email("rejected@example.com")
                .firstName("Rejected")
                .lastName("Student")
                .role(UserRole.STUDENT)
                .status(UserStatus.REJECTED)
                .build();
        user.setId(userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(scorecardRepository.findByStudentId(userId)).thenReturn(Optional.empty());

        userService.deleteRejectedUser(userId);

        verify(userRepository).delete(user);
    }

    @Test
    void deniesACompanyWithoutAnApplicationRelationship() {
        UUID companyId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User company = User.builder().role(UserRole.COMPANY).status(UserStatus.APPROVED).build();
        User student = User.builder().role(UserRole.STUDENT).status(UserStatus.APPROVED).build();
        when(userRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(userRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(applicationRepository.existsForCompanyAndStudent(companyId, studentId)).thenReturn(false);

        assertThrows(com.talentbridge.exception.ForbiddenException.class,
                () -> userService.getStudentProfile(studentId, companyId));
    }

    @Test
    void refusesToRejectAnApprovedAccount() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, UserRole.STUDENT, UserStatus.APPROVED);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> userService.rejectUser(userId, "Incomplete profile", UUID.randomUUID()));

        assertEquals("Only pending or rejected users can be rejected", error.getMessage());
    }

    @Test
    void refusesToSuspendAPendingAccount() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, UserRole.STUDENT, UserStatus.PENDING);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> userService.suspendUser(userId, UUID.randomUUID()));

        assertEquals("Only approved users can be suspended", error.getMessage());
    }

    @Test
    void refusesToModerateACoordinatorAccount() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, UserRole.COORDINATOR, UserStatus.APPROVED);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        assertThrows(BadRequestException.class,
                () -> userService.suspendUser(userId, UUID.randomUUID()));
    }

    @Test
    void refusesToUnsuspendAnAccountThatIsNotSuspended() {
        UUID userId = UUID.randomUUID();
        User user = user(userId, UserRole.STUDENT, UserStatus.REJECTED);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> userService.unsuspendUser(userId));

        assertEquals("Only suspended users can be unsuspended", error.getMessage());
    }

    private User user(UUID id, UserRole role, UserStatus status) {
        User user = User.builder()
                .email(id + "@example.com")
                .firstName("Demo")
                .lastName("User")
                .role(role)
                .status(status)
                .build();
        user.setId(id);
        return user;
    }
}
