package com.talentbridge.service;

import com.talentbridge.config.AppProperties;
import com.talentbridge.dto.request.ApplicationRequest;
import com.talentbridge.dto.request.PartyRequest;
import com.talentbridge.dto.response.ApplicationResponse;
import com.talentbridge.dto.response.PartyResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PartyService {

    private final PartyRepository partyRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;
    private final SubmissionRepository submissionRepository;
    private final NotificationService notificationService;
    private final AppProperties appProperties;

    @Transactional
    public PartyResponse create(UUID leaderId, PartyRequest req) {
        User leader = getUser(leaderId);
        if (partyRepository.findByMemberId(leaderId).isPresent())
            throw new BadRequestException("You are already in a party");

        Party party = Party.builder()
                .name(req.getName()).leader(leader)
                .status(PartyStatus.FORMING)
                .semester(req.getSemester())
                .academicYear(req.getAcademicYear())
                .build();
        party.getMembers().add(leader);
        return toResponse(partyRepository.save(party));
    }

    @Transactional
    public PartyResponse join(UUID partyId, UUID studentId) {
        Party party = getPartyForUpdate(partyId);
        int maxSize = appProperties.getParty().getMaxSize();

        if (party.getMembers().size() >= maxSize)
            throw new BadRequestException("Party is full (max " + maxSize + " members)");
        if (party.getStatus() != PartyStatus.FORMING)
            throw new BadRequestException("Party is no longer open for new members");
        if (partyRepository.findByMemberId(studentId).isPresent())
            throw new BadRequestException("You are already in a party");
        User student = getUser(studentId);

        party.getMembers().add(student);
        if (party.getMembers().size() >= appProperties.getParty().getMinSize())
            party.setStatus(PartyStatus.ACTIVE);

        party = partyRepository.save(party);
        notificationService.send(party.getLeader(), NotificationType.PARTY_JOINED,
                "New member joined",
                student.getFullName() + " joined your party.",
                partyId.toString(), "PARTY");
        return toResponse(party);
    }

    @Transactional
    public void leave(UUID partyId, UUID studentId) {
        // Once a student joins a party they cannot leave on their own.
        // Only a coordinator can remove them (Phase 4).
        throw new BadRequestException(
            "You cannot leave a party once joined. Contact your coordinator to be removed.");
    }

    @Transactional
    public PartyResponse changeLeader(UUID partyId, UUID currentLeaderId, UUID newLeaderId) {
        Party party = getPartyForUpdate(partyId);

        if (!party.getLeader().getId().equals(currentLeaderId))
            throw new ForbiddenException("Only the current leader can transfer leadership");

        boolean newLeaderIsMember = party.getMembers().stream()
                .anyMatch(m -> m.getId().equals(newLeaderId));
        if (!newLeaderIsMember)
            throw new BadRequestException("New leader must be a member of the party");

        User newLeader = getUser(newLeaderId);
        party.setLeader(newLeader);

        notificationService.send(newLeader, NotificationType.GENERAL,
                "You are now the party leader",
                "Leadership of " + party.getName() + " has been transferred to you.",
                partyId.toString(), "PARTY");

        return toResponse(partyRepository.save(party));
    }

    @Transactional
    public ApplicationResponse applyToProject(UUID partyId, UUID leaderId, ApplicationRequest req) {
        Party party = getPartyForUpdate(partyId);
        if (!party.getLeader().getId().equals(leaderId))
            throw new ForbiddenException("Only party leader can apply");
        if (party.getMembers().size() < appProperties.getParty().getMinSize())
            throw new BadRequestException("Party needs minimum " + appProperties.getParty().getMinSize() + " members");
        if (party.getAssignedProject() != null)
            throw new BadRequestException("Party already has an assigned project");
        if (applicationRepository.existsByPartyIdAndProjectId(partyId, req.getProjectId()))
            throw new BadRequestException("Already applied to this project");

        long count = applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId).size();
        if (count >= 5) throw new BadRequestException("Maximum 5 applications allowed");

        applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId).stream()
                .filter(a -> a.getRankPosition().equals(req.getRankPosition())).findAny()
                .ifPresent(a -> { throw new BadRequestException("Rank " + req.getRankPosition() + " already taken"); });

        Project project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", req.getProjectId().toString()));
        if (project.getStatus() != ProjectStatus.OPEN)
            throw new BadRequestException("Project is not open for applications");

        Application app = applicationRepository.save(Application.builder()
                .party(party).project(project)
                .rankPosition(req.getRankPosition())
                .proposalText(req.getProposalText())
                .status(ApplicationStatus.PENDING)
                .build());
        return toApplicationResponse(app);
    }

    @Transactional
    public PartyResponse assignSupervisor(UUID partyId, UUID supervisorId) {
        if (supervisorId == null) throw new BadRequestException("Supervisor is required");
        Party party = getPartyForUpdate(partyId);
        User supervisor = getUserForUpdate(supervisorId);

        return setSupervisor(partyId, party, supervisor);
    }

    @Transactional
    public PartyResponse claimSupervisor(UUID partyId, UUID supervisorId) {
        Party party = getPartyForUpdate(partyId);
        if (party.getSupervisor() != null)
            throw new BadRequestException("Party already has a supervisor");
        return setSupervisor(partyId, party, getUserForUpdate(supervisorId));
    }

    private PartyResponse setSupervisor(UUID partyId, Party party, User supervisor) {
        UUID supervisorId = supervisor.getId();

        if (supervisor.getRole() != UserRole.PARTY_SUPERVISOR)
            throw new BadRequestException("User is not a party supervisor");
        if (party.getSupervisor() != null && party.getSupervisor().getId().equals(supervisorId))
            return toResponse(party);
        if (party.getSemester() == null || party.getAcademicYear() == null)
            throw new BadRequestException("Party semester and academic year are required");

        int maxParties = appProperties.getParty().getSupervisorMaxParties();
        long assignedCount = partyRepository
                .countBySupervisorIdAndSemesterAndAcademicYearAndIdNot(
                        supervisorId, party.getSemester(), party.getAcademicYear(), partyId);
        if (assignedCount >= maxParties)
            throw new BadRequestException("Supervisor already has " + maxParties + " parties this academic term");

        party.setSupervisor(supervisor);
        return toResponse(partyRepository.save(party));
    }

    @Transactional
    public PartyResponse renameParty(UUID partyId, String name) {
        if (name == null || name.isBlank()) throw new BadRequestException("Party name is required");
        if (name.trim().length() > 100) throw new BadRequestException("Party name must be 100 characters or fewer");
        Party party = getPartyOrThrow(partyId);
        party.setName(name.trim());
        return toResponse(partyRepository.save(party));
    }

    @Transactional
    public PartyResponse removeMember(UUID partyId, UUID userId) {
        Party party = getPartyOrThrow(partyId);
        User member = getUser(userId);
        boolean isMember = party.getMembers().stream().anyMatch(m -> m.getId().equals(userId));
        if (!isMember) throw new BadRequestException("User is not a member of this party");

        int nextSize = party.getMembers().size() - 1;
        if (nextSize <= 0) throw new BadRequestException("Cannot remove the last party member");
        if (party.getAssignedProject() != null && nextSize < appProperties.getParty().getMinSize()) {
            throw new BadRequestException("Unassign the project before removing this member");
        }

        party.getMembers().removeIf(m -> m.getId().equals(userId));

        if (party.getLeader().getId().equals(userId)) {
            User nextLeader = party.getMembers().stream()
                    .min(Comparator.comparing(User::getCreatedAt))
                    .orElseThrow();
            party.setLeader(nextLeader);
            notificationService.send(nextLeader, NotificationType.GENERAL,
                    "You are now the party leader",
                    "You were made leader of " + party.getName() + " after a coordinator update.",
                    partyId.toString(), "PARTY");
        }

        if (party.getAssignedProject() == null && party.getMembers().size() < appProperties.getParty().getMinSize()) {
            party.setStatus(PartyStatus.FORMING);
        }

        notificationService.send(member, NotificationType.GENERAL,
                "Removed from party",
                "A coordinator removed you from " + party.getName() + ".",
                partyId.toString(), "PARTY");

        return toResponse(partyRepository.save(party));
    }

    @Transactional
    public PartyResponse unassignProject(UUID partyId) {
        Party party = getPartyForUpdate(partyId);
        Project project = party.getAssignedProject();
        if (project == null) throw new BadRequestException("Party does not have an assigned project");
        if (submissionRepository.findByPartyId(partyId).isPresent())
            throw new BadRequestException("A party with a submission cannot be unassigned");

        applicationRepository.findByProjectIdAndStatusIn(project.getId(),
                        List.of(ApplicationStatus.ASSIGNED, ApplicationStatus.REASSIGNED))
                .forEach(app -> app.setStatus(ApplicationStatus.PENDING));

        project.setStatus(ProjectStatus.OPEN);
        party.setAssignedProject(null);
        party.setStatus(party.getMembers().size() >= appProperties.getParty().getMinSize()
                ? PartyStatus.ACTIVE
                : PartyStatus.FORMING);

        projectRepository.save(project);
        party.getMembers().forEach(m -> notificationService.send(m, NotificationType.GENERAL,
                "Project unassigned",
                "A coordinator unassigned " + project.getTitle() + " from your party.",
                project.getId().toString(), "PROJECT"));
        return toResponse(partyRepository.save(party));
    }

    public PartyResponse getMyParty(UUID userId) {
        return toResponse(partyRepository.findByMemberId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("You are not in any party yet")));
    }

    public PartyResponse getById(UUID id) { return toResponse(getPartyOrThrow(id)); }

    public List<PartyResponse> getAll() {
        return partyRepository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public Map<String, Integer> getRules() {
        return Map.of(
                "minSize", appProperties.getParty().getMinSize(),
                "maxSize", appProperties.getParty().getMaxSize(),
                "supervisorMaxParties", appProperties.getParty().getSupervisorMaxParties());
    }

    public List<PartyResponse> getSupervised(UUID supervisorId) {
        User supervisor = getUser(supervisorId);
        List<Party> parties = supervisor.getRole() == UserRole.PROJECT_SUPERVISOR
                ? partyRepository.findByProjectSupervisorId(supervisorId)
                : partyRepository.findBySupervisorId(supervisorId);
        return parties
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ApplicationResponse> getApplicationsByParty(UUID partyId) {
        return applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId)
                .stream().map(this::toApplicationResponse).collect(Collectors.toList());
    }

    public List<ApplicationResponse> getApplicationsByProject(UUID projectId, UUID viewerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId.toString()));
        User viewer = getUser(viewerId);
        boolean allowed = viewer.getRole() == UserRole.COORDINATOR
                || (viewer.getRole() == UserRole.COMPANY
                && project.getCreatedBy().getId().equals(viewerId))
                || (viewer.getRole() == UserRole.PROJECT_SUPERVISOR
                && project.getProjectSupervisor() != null
                && project.getProjectSupervisor().getId().equals(viewerId));
        if (!allowed) throw new ForbiddenException("You cannot view applications for this project");
        return applicationRepository.findByProjectIdOrderByCreatedAtAsc(projectId)
                .stream().map(this::toApplicationResponse).collect(Collectors.toList());
    }

    private Party getPartyOrThrow(UUID id) {
        return partyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Party", id.toString()));
    }

    private Party getPartyForUpdate(UUID id) {
        return partyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Party", id.toString()));
    }

    private User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    private User getUserForUpdate(UUID id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    public PartyResponse toResponse(Party p) {
        List<PartyResponse.MemberDto> members = p.getMembers().stream()
                .map(m -> PartyResponse.MemberDto.builder()
                        .id(m.getId())
                        .firstName(m.getFirstName())
                        .lastName(m.getLastName())
                        .email(m.getEmail())
                        .skills(m.getStudentProfile() != null ? m.getStudentProfile().getSkills() : List.of())
                        .build())
                .collect(Collectors.toList());

        return PartyResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .status(p.getStatus())
                .semester(p.getSemester())
                .academicYear(p.getAcademicYear())
                .leader(members.stream()
                        .filter(m -> m.getId().equals(p.getLeader().getId()))
                        .findFirst().orElse(null))
                .members(members)
                .supervisorName(p.getSupervisor() != null ? p.getSupervisor().getFullName() : null)
                .supervisorId(p.getSupervisor() != null ? p.getSupervisor().getId() : null)
                .assignedProjectId(p.getAssignedProject() != null ? p.getAssignedProject().getId() : null)
                .assignedProjectTitle(p.getAssignedProject() != null ? p.getAssignedProject().getTitle() : null)
                .assignedProjectDeadline(p.getAssignedProject() != null ? p.getAssignedProject().getDeadline() : null)
                .build();
    }

    private ApplicationResponse toApplicationResponse(Application a) {
        return ApplicationResponse.builder()
                .id(a.getId())
                .projectId(a.getProject().getId())
                .projectTitle(a.getProject().getTitle())
                .companyName(a.getProject().getCompany() != null
                        ? a.getProject().getCompany().getCompanyName() : "TalentBridge")
                .partyId(a.getParty().getId())
                .partyName(a.getParty().getName())
                .rankPosition(a.getRankPosition())
                .proposalText(a.getProposalText())
                .status(a.getStatus())
                .coordinatorNotes(a.getCoordinatorNotes())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
