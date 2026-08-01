package com.talentbridge.service;

import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.User;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceDeadlineTest {

    @Mock private SubmissionRepository submissionRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SubmissionService submissionService;

    @Test
    void blocksFinalSubmissionAfterTheAssignedProjectDeadline() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().email("leader@example.com").build();
        leader.setId(leaderId);
        Project project = Project.builder().deadline(LocalDateTime.now().minusMinutes(1)).build();
        Party party = Party.builder().leader(leader).assignedProject(project).build();
        when(partyRepository.findById(partyId)).thenReturn(Optional.of(party));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.finalSubmit(partyId, leaderId));

        assertEquals("The project deadline has expired", error.getMessage());
    }
}
