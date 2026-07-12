package com.talentbridge.service;

import com.talentbridge.dto.request.ProjectRequest;
import com.talentbridge.dto.response.PageResponse;
import com.talentbridge.dto.response.ProjectResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PartyRepository partyRepository;
    private final ApplicationRepository applicationRepository;
    private final NotificationService notificationService;

    @Transactional
    public ProjectResponse create(UUID creatorId, ProjectRequest req) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", creatorId.toString()));

        Project project = Project.builder()
                .title(req.getTitle()).description(req.getDescription())
                .scope(req.getScope()).deliverables(req.getDeliverables())
                .evaluationCriteria(req.getEvaluationCriteria())
                .tools(req.getTools() != null ? req.getTools() : new ArrayList<>())
                .status(creator.getRole() == UserRole.COORDINATOR ? ProjectStatus.OPEN : ProjectStatus.PENDING_REVIEW)
                .createdBy(creator).deadline(req.getDeadline())
                .internalName(req.getInternalName())
                .projectField(req.getProjectField())
                .build();

        if (creator.getRole() == UserRole.COMPANY) project.setCompany(creator.getCompanyProfile());
        if (creator.getRole() == UserRole.COORDINATOR) project.setApprovedBy(creator);
        if (req.getProjectSupervisorId() != null) {
            project.setProjectSupervisor(userRepository.findById(req.getProjectSupervisorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Supervisor", req.getProjectSupervisorId().toString())));
        }
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public ProjectResponse update(UUID projectId, UUID userId, ProjectRequest req) {
        Project project = getOrThrow(projectId);

        if (!project.getCreatedBy().getId().equals(userId))
            throw new ForbiddenException("You can only edit your own projects");

        if (project.getStatus() == ProjectStatus.OPEN || project.getStatus() == ProjectStatus.ASSIGNED)
            throw new BadRequestException("Approved or assigned projects cannot be edited");

        project.setTitle(req.getTitle());
        project.setDescription(req.getDescription());
        project.setScope(req.getScope());
        project.setDeliverables(req.getDeliverables());
        project.setEvaluationCriteria(req.getEvaluationCriteria());
        if (req.getTools() != null) project.setTools(req.getTools());
        if (req.getDeadline() != null) project.setDeadline(req.getDeadline());
        if (req.getInternalName() != null) project.setInternalName(req.getInternalName());
        project.setProjectField(req.getProjectField());

        // Resubmit for review if it was archived/rejected
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            project.setStatus(ProjectStatus.PENDING_REVIEW);
        }

        return toResponse(projectRepository.save(project));
    }

    // Updates ONLY internalName — works for all statuses, no resubmit triggered
    @Transactional
    public ProjectResponse patchInternalName(UUID projectId, UUID userId, String internalName) {
        Project project = getOrThrow(projectId);
        if (!project.getCreatedBy().getId().equals(userId))
            throw new ForbiddenException("You can only edit your own projects");
        project.setInternalName(internalName != null ? internalName.trim() : null);
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public void delete(UUID projectId, UUID userId) {
        Project project = getOrThrow(projectId);
        if (!project.getCreatedBy().getId().equals(userId))
            throw new ForbiddenException("You can only delete your own projects");
        if (project.getStatus() != ProjectStatus.PENDING_REVIEW && project.getStatus() != ProjectStatus.ARCHIVED)
            throw new BadRequestException("Only projects awaiting approval or rejected can be deleted");
        projectRepository.delete(project);
    }

    @Transactional
    public ProjectResponse approve(UUID projectId, UUID coordinatorId) {
        Project project = getOrThrow(projectId);
        User coordinator = userRepository.findById(coordinatorId).orElseThrow();

        if (project.getStatus() != ProjectStatus.PENDING_REVIEW && project.getStatus() != ProjectStatus.ARCHIVED)
            throw new BadRequestException("Project must be in PENDING_REVIEW or ARCHIVED status to approve");

        project.setStatus(ProjectStatus.OPEN);
        project.setApprovedBy(coordinator);
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public ProjectResponse disapprove(UUID projectId, UUID coordinatorId) {
        Project project = getOrThrow(projectId);

        if (project.getStatus() != ProjectStatus.PENDING_REVIEW)
            throw new BadRequestException("Only pending projects can be disapproved");

        project.setStatus(ProjectStatus.ARCHIVED);
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public ProjectResponse retract(UUID projectId, UUID coordinatorId) {
        Project project = getOrThrow(projectId);
        userRepository.findById(coordinatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", coordinatorId.toString()));

        if (project.getStatus() != ProjectStatus.OPEN)
            throw new BadRequestException("Only open projects can be retracted");

        applicationRepository.findByProjectIdAndStatus(projectId, ApplicationStatus.PENDING)
                .forEach(a -> {
                    a.setStatus(ApplicationStatus.REASSIGNED);
                    applicationRepository.save(a);
                    notificationService.notifyPartyProjectRetracted(a.getParty(), project);
                });

        project.setStatus(ProjectStatus.ARCHIVED);
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public ProjectResponse setDeadline(UUID projectId, LocalDateTime deadline) {
        Project p = getOrThrow(projectId);
        p.setDeadline(deadline);
        return toResponse(projectRepository.save(p));
    }

    @Transactional
    public void setGlobalDeadline(LocalDateTime deadline) {
        List<Project> open = projectRepository.findByStatus(ProjectStatus.OPEN, Pageable.unpaged()).getContent();
        open.forEach(p -> p.setDeadline(deadline));
        projectRepository.saveAll(open);
    }

    @Transactional
    public ProjectResponse assignToParty(UUID projectId, UUID partyId, UUID coordinatorId) {
        Project project = getOrThrow(projectId);
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new ResourceNotFoundException("Party", partyId.toString()));

        if (project.getStatus() == ProjectStatus.ASSIGNED)
            throw new BadRequestException("Project already assigned");
        if (party.getAssignedProject() != null)
            throw new BadRequestException("Party already has a project");

        applicationRepository.findByPartyIdAndProjectId(partyId, projectId).ifPresent(app -> {
            app.setStatus(ApplicationStatus.ASSIGNED);
            applicationRepository.save(app);
        });

        applicationRepository.findByProjectIdAndStatus(projectId, ApplicationStatus.PENDING)
                .stream().filter(a -> !a.getParty().getId().equals(partyId))
                .forEach(a -> {
                    a.setStatus(ApplicationStatus.REASSIGNED);
                    applicationRepository.save(a);
                    notificationService.notifyPartyProjectRejected(a.getParty(), project);
                });

        project.setStatus(ProjectStatus.ASSIGNED);
        party.setAssignedProject(project);
        party.setStatus(PartyStatus.ASSIGNED);
        projectRepository.save(project);
        partyRepository.save(party);
        notificationService.notifyPartyProjectAssigned(party, project);
        return toResponse(project);
    }

    public PageResponse<ProjectResponse> getOpen(String search, String sortBy, int page, int size) {
        Sort sort = "deadline".equals(sortBy) ? Sort.by("deadline").ascending() : Sort.by("createdAt").descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Project> result = (search != null && !search.isBlank())
                ? projectRepository.searchOpen(search, pageable)
                : projectRepository.findByStatus(ProjectStatus.OPEN, pageable);
        return toPageResponse(result.map(this::toPublicResponse));
    }

    public PageResponse<ProjectResponse> getAll(int page, int size, String statusFilter) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Project> result;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                ProjectStatus status = ProjectStatus.valueOf(statusFilter.toUpperCase());
                result = projectRepository.findByStatus(status, pageable);
            } catch (IllegalArgumentException e) {
                result = projectRepository.findAll(pageable);
            }
        } else {
            result = projectRepository.findAll(pageable);
        }
        return toPageResponse(result.map(this::toResponse));
    }

    // All projects by the creator (company sees their own, including archived)
    public List<ProjectResponse> getByCreator(UUID userId) {
        return projectRepository.findByCreatedById(userId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ProjectResponse> getByCompany(UUID companyId) {
        return projectRepository.findByCompanyId(companyId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public ProjectResponse getById(UUID id) { return toResponse(getOrThrow(id)); }

    private Project getOrThrow(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id.toString()));
    }

    public ProjectResponse toResponse(Project p) {
        return ProjectResponse.builder()
                .id(p.getId()).title(p.getTitle()).description(p.getDescription())
                .scope(p.getScope()).deliverables(p.getDeliverables())
                .evaluationCriteria(p.getEvaluationCriteria()).tools(p.getTools())
                .status(p.getStatus()).deadline(p.getDeadline())
                .companyName(p.getCompany() != null ? p.getCompany().getCompanyName() : "TalentBridge")
                .companyIndustry(p.getCompany() != null ? p.getCompany().getIndustry() : null)
                .companyId(p.getCompany() != null ? p.getCompany().getId() : null)
                .projectSupervisorName(p.getProjectSupervisor() != null ? p.getProjectSupervisor().getFullName() : null)
                .projectSupervisorId(p.getProjectSupervisor() != null ? p.getProjectSupervisor().getId() : null)
                .createdByName(p.getCreatedBy().getFullName())
                .partyApplicationCount(projectRepository.countApplicationsByProjectId(p.getId()))
                .projectField(p.getProjectField())
                .createdAt(p.getCreatedAt())
                .internalName(p.getInternalName())
                .build();
    }

    // Public response — strips internalName (used for student-facing endpoints)
    public ProjectResponse toPublicResponse(Project p) {
        ProjectResponse r = toResponse(p);
        r.setInternalName(null);
        return r;
    }

    private <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent()).totalPages(page.getTotalPages())
                .totalElements(page.getTotalElements()).page(page.getNumber())
                .size(page.getSize()).first(page.isFirst()).last(page.isLast())
                .build();
    }
}
