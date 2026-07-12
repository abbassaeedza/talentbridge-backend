package com.talentbridge.service;

import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Transactional
    public void send(User recipient, NotificationType type, String title, String message) {
        notificationRepository.save(Notification.builder()
                .recipient(recipient).type(type).title(title).message(message).read(false).build());
    }

    @Transactional
    public void send(User recipient, NotificationType type, String title, String message,
                     String refId, String refType) {
        notificationRepository.save(Notification.builder()
                .recipient(recipient).type(type).title(title).message(message)
                .referenceId(refId).referenceType(refType).read(false).build());
    }

    public void notifyCoordinatorsNewRegistration(User newUser) {
        userRepository.findByRoleAndStatus(UserRole.COORDINATOR, UserStatus.APPROVED)
                .forEach(c -> send(c, NotificationType.GENERAL, "New registration pending",
                        newUser.getFullName() + " (" + newUser.getRole() + ") awaits approval.",
                        newUser.getId().toString(), "USER"));
    }

    public void notifyUserApproved(User user) {
        send(user, NotificationType.ACCOUNT_APPROVED, "Account Approved!",
                "Welcome to TalentBridge! Your account is now active.");
    }

    public void notifyUserRejected(User user) {
        send(user, NotificationType.ACCOUNT_REJECTED, "Account Not Approved",
                "Your TalentBridge account was not approved." +
                        (user.getRejectionReason() != null ? " Reason: " + user.getRejectionReason() : ""));
    }

    public void notifyPartyProjectAssigned(Party party, Project project) {
        String msg = "Your party '" + party.getName() + "' has been assigned to: " + project.getTitle();
        party.getMembers().forEach(m -> send(m, NotificationType.PROJECT_ASSIGNED,
                "Project Assigned!", msg, project.getId().toString(), "PROJECT"));
    }

    public void notifyPartyProjectRejected(Party party, Project project) {
        send(party.getLeader(), NotificationType.PROJECT_REJECTED, "Project Taken",
                "Project '" + project.getTitle() + "' was assigned to another party.",
                project.getId().toString(), "PROJECT");
    }

    public void notifyPartyProjectRetracted(Party party, Project project) {
        String msg = "Project '" + project.getTitle() + "' was retracted by the coordinator.";
        party.getMembers().forEach(m -> send(m, NotificationType.PROJECT_REJECTED,
                "Project Retracted", msg, project.getId().toString(), "PROJECT"));
    }

    public void notifyEvaluationComplete(Party party, EvaluationReport report) {
        String msg = "Evaluation complete. Total score: " +
                String.format("%.1f", report.getTotalScore()) + "/100.";
        party.getMembers().forEach(m -> send(m, NotificationType.EVALUATION_COMPLETE,
                "Evaluation Complete", msg, report.getId().toString(), "EVALUATION"));
    }

    public void notifySupervisorSubmission(User supervisor, Party party) {
        send(supervisor, NotificationType.SUBMISSION_RECEIVED, "Party Submitted",
                party.getName() + " has submitted their final project.",
                party.getId().toString(), "PARTY");
    }

    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getRecipient().getId().equals(userId)) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllReadByUser(userId);
    }

    @Transactional
    public void clearAll(UUID userId) {
        notificationRepository.deleteAllByRecipientId(userId);
    }

    public List<Notification> getForUser(UUID userId, int page) {
        return notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 20))
                .getContent();
    }

    public long countUnread(UUID userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }
}
