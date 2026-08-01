package com.talentbridge.service;

import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SubmissionService {
    private final SubmissionRepository submissionRepository;
    private final PartyRepository partyRepository;
    private final FileStorageService fileStorageService;
    private final NotificationService notificationService;

    @Transactional
    public Submission saveDraft(UUID partyId, UUID leaderId, String repoUrl,
                                String branch, List<MultipartFile> documents, String notes) {
        Party party = getParty(partyId);
        assertLeader(party, leaderId);
        if (party.getAssignedProject() == null) throw new BadRequestException("Party has no assigned project");

        Submission sub = submissionRepository.findByPartyId(partyId)
            .orElse(Submission.builder().party(party).project(party.getAssignedProject())
                .status(SubmissionStatus.DRAFT).documentUrls(new ArrayList<>()).build());

        if (repoUrl != null && !repoUrl.isBlank()) sub.setRepoUrl(repoUrl);
        if (branch != null && !branch.isBlank()) sub.setRepoBranch(branch);
        if (notes != null) sub.setNotes(notes);
        if (documents != null)
            documents.stream().filter(f -> !f.isEmpty())
                .forEach(f -> sub.getDocumentUrls().add(fileStorageService.upload(f, "submissions/" + partyId)));

        return submissionRepository.save(sub);
    }

    @Transactional
    public Submission finalSubmit(UUID partyId, UUID leaderId) {
        Party party = getParty(partyId);
        assertLeader(party, leaderId);
        Project project = party.getAssignedProject();
        if (project == null) throw new BadRequestException("Party has no assigned project");
        if (project.getDeadline() != null && !project.getDeadline().isAfter(LocalDateTime.now()))
            throw new BadRequestException("The project deadline has expired");
        Submission sub = submissionRepository.findByPartyId(partyId)
            .orElseThrow(() -> new BadRequestException("No draft found. Save your submission first."));
        if (sub.getRepoUrl() == null || sub.getRepoUrl().isBlank())
            throw new BadRequestException("GitHub repository URL is required");
        sub.setStatus(SubmissionStatus.SUBMITTED);
        sub.setSubmittedAt(LocalDateTime.now());
        party.setStatus(PartyStatus.SUBMITTED);
        partyRepository.save(party);
        if (party.getSupervisor() != null)
            notificationService.notifySupervisorSubmission(party.getSupervisor(), party);
        if (party.getAssignedProject() != null && party.getAssignedProject().getProjectSupervisor() != null)
            notificationService.notifySupervisorSubmission(party.getAssignedProject().getProjectSupervisor(), party);
        return submissionRepository.save(sub);
    }

    public Submission getByPartyId(UUID partyId) {
        return submissionRepository.findByPartyId(partyId)
            .orElseThrow(() -> new ResourceNotFoundException("No submission for this party"));
    }

    public List<Submission> getByProjectId(UUID projectId) {
        return submissionRepository.findByProjectId(projectId);
    }

    private Party getParty(UUID id) {
        return partyRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Party", id.toString()));
    }

    private void assertLeader(Party party, UUID userId) {
        if (!party.getLeader().getId().equals(userId))
            throw new ForbiddenException("Only the party leader can manage submissions");
    }
}
