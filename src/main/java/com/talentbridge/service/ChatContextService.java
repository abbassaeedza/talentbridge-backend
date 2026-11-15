package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentbridge.dto.response.ChatContextResponse;
import com.talentbridge.dto.response.ChatContextResponse.*;
import com.talentbridge.entity.ApplicationSettings;
import com.talentbridge.entity.User;
import com.talentbridge.enums.*;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ChatContextService {
    private static final PageRequest FIRST_50 = PageRequest.of(0, 50);
    private final ProjectRepository projectRepository;
    private final PartyRepository partyRepository;
    private final SubmissionRepository submissionRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ApplicationSettingsRepository settingsRepository;
    private final ScorecardRepository scorecardRepository;
    private final EvaluationReportRepository evaluationRepository;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public User requireApprovedViewer(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException("Approved account required"));
        if (user.getStatus() != UserStatus.APPROVED || user.getRole() == null)
            throw new ForbiddenException("Approved account required");
        return user;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatContextResponse snapshot(UUID userId) {
        User user = requireApprovedViewer(userId);
        boolean coordinator = user.getRole() == UserRole.COORDINATOR;
        boolean company = user.getRole() == UserRole.COMPANY;
        boolean projectSupervisor = user.getRole() == UserRole.PROJECT_SUPERVISOR;
        Instant now = clock.instant();
        var projectCounts = counts(ProjectStatus.class, projectRepository.countChatProjects(userId, coordinator, false));
        var openCounts = counts(ProjectStatus.class, projectRepository.countChatProjects(userId, coordinator, true));
        var partyCounts = counts(PartyStatus.class, partyRepository.countChatParties(userId, coordinator, false));
        var applicationCounts = counts(ApplicationStatus.class,
                applicationRepository.countChatApplications(userId, coordinator, company, projectSupervisor));
        var submissionCounts = counts(SubmissionStatus.class, submissionRepository.countChatSubmissions(userId, coordinator));
        var evaluationCounts = evaluationRepository.countChatEvaluations(userId, coordinator);
        var open = projectRepository.findChatProjects(userId, coordinator, true, FIRST_50);
        var projects = projectRepository.findChatProjects(userId, coordinator, false, FIRST_50);
        var assigned = user.getRole() == UserRole.STUDENT ? projectRepository.findChatAssignedBrief(userId) : Optional.<ProjectRepository.ChatBrief>empty();
        List<UUID> projectIds = Stream.concat(Stream.concat(open.stream(), projects.stream()), assigned.stream())
                .map(ProjectRepository.ChatProject::getId).distinct().toList();
        Map<UUID, Long> applicationTotals = new HashMap<>();
        if (!projectIds.isEmpty()) projectRepository.countChatApplications(projectIds)
                .forEach(row -> applicationTotals.put((UUID) row[0], ((Number) row[1]).longValue()));
        var parties = partyRepository.findChatParties(userId, coordinator, false, FIRST_50).stream().map(this::party).toList();
        SummarySet<PartySummary> directory = null;
        if (user.getRole() == UserRole.PARTY_SUPERVISOR) {
            var directoryCounts = counts(PartyStatus.class, partyRepository.countChatParties(userId, false, true));
            directory = new SummarySet<>(partyRepository.findChatParties(userId, false, true, FIRST_50)
                    .stream().map(this::party).toList(), total(directoryCounts));
        }
        var applications = applicationRepository.findChatApplications(userId, coordinator, company, projectSupervisor, FIRST_50)
                .stream().map(a -> new ApplicationSummary(a.getId(), a.getPartyId(), a.getPartyName(), a.getProjectId(),
                        a.getProjectTitle(), a.getRankPosition(), a.getStatus())).toList();
        var submissions = submissionRepository.findChatSubmissions(userId, coordinator, FIRST_50).stream()
                .map(s -> new SubmissionSummary(s.getId(), s.getPartyId(), s.getPartyName(), s.getProjectId(),
                        s.getProjectTitle(), s.getStatus(), utc(s.getSubmittedAt()))).toList();
        var evaluations = evaluationRepository.findChatEvaluations(userId, coordinator, FIRST_50).stream()
                .map(e -> new EvaluationSummary(e.getId(), e.getSubmissionId(), e.getProjectId(), e.getFinalized(),
                        utc(e.getEvaluatedAt()), e.getTotalScore(), e.getAiDetectionScore(), e.getCodeQualityScore(),
                        e.getFunctionalityScore(), e.getScopeAlignmentScore(), e.getTeamCollaborationScore(),
                        e.getMyIndividualScore(), e.getMyContributionPercentage(), e.getMyTotalCommits())).toList();
        long evaluationTotal = evaluationCounts.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();
        long finalized = evaluationCounts.stream().filter(row -> Boolean.TRUE.equals(row[0]))
                .mapToLong(row -> ((Number) row[1]).longValue()).sum();
        UserCounts users = null;
        if (coordinator) {
            var roles = counts(UserRole.class, userRepository.countByRoleGrouped());
            users = new UserCounts(total(roles), roles, counts(UserStatus.class, userRepository.countByStatusGrouped()));
        }
        var global = settingsRepository.findById(ApplicationSettings.ID)
                .map(s -> new GlobalDeadline(s.isGlobalDeadlineEnabled(), utc(s.getGlobalDeadline())))
                .orElse(new GlobalDeadline(false, null));
        LocalDateTime nowUTC = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var deadlines = projectRepository.findChatUpcoming(userId, coordinator, nowUTC, FIRST_50).stream()
                .map(p -> new DeadlineSummary(p.getId(), p.getTitle(), utc(p.getDeadline()))).toList();
        return new ChatContextResponse(new Viewer(userId, user.getRole(), user.getFullName()), now, global,
                new SummarySet<>(open.stream().map(p -> project(p, applicationTotals)).toList(), total(openCounts)),
                new SummarySet<>(projects.stream().map(p -> project(p, applicationTotals)).toList(), total(projectCounts)),
                new SummarySet<>(parties, total(partyCounts)), directory,
                new SummarySet<>(applications, total(applicationCounts)), new SummarySet<>(submissions, total(submissionCounts)),
                new SummarySet<>(evaluations, evaluationTotal),
                new Counts(projectCounts, partyCounts, applicationCounts, submissionCounts, finalized, users),
                assigned.map(p -> brief(p, applicationTotals)).orElse(null),
                user.getRole() == UserRole.STUDENT ? scorecard(userId) : null,
                new SummarySet<>(deadlines, projectRepository.countChatUpcoming(userId, coordinator, nowUTC)));
    }

    public String formatForModel(ChatContextResponse snapshot) {
        ObjectNode json = mapper.valueToTree(snapshot);
        ((ObjectNode) json.get("viewer")).remove(List.of("id", "name"));
        removeSupervisorNames(json);
        List<ObjectNode> sets = new ArrayList<>();
        collectSets(json, sets);
        // Preserve valid JSON and exact totals when reducing the model's record budget.
        while (json.toString().length() > 24000) {
            boolean removed = false;
            for (ObjectNode set : sets) {
                ArrayNode items = (ArrayNode) set.get("items");
                if (!items.isEmpty()) {
                    items.remove(items.size() - 1);
                    set.put("truncated", true);
                    removed = true;
                }
            }
            if (!removed) {
                JsonNode brief = json.path("myAssignedProject");
                String largest = null;
                for (String field : List.of("description", "scope", "deliverables", "evaluationCriteria")) {
                    JsonNode value = brief.path(field);
                    if (value.isTextual() && !value.asText().isEmpty()
                            && (largest == null || value.toString().length() > brief.path(largest).toString().length()))
                        largest = field;
                }
                if (largest == null) throw new IllegalStateException("Chat context exceeds its record budget");
                String text = brief.path(largest).asText();
                ((ObjectNode) brief).put(largest, text.substring(0, text.length() / 2));
                ArrayNode clipped = (ArrayNode) brief.path("truncatedFields");
                boolean alreadyMarked = false;
                for (JsonNode field : clipped) if (largest.equals(field.asText())) alreadyMarked = true;
                if (!alreadyMarked) clipped.add(largest);
            }
        }
        return json.toString();
    }

    private void removeSupervisorNames(JsonNode node) {
        if (node instanceof ObjectNode object) object.remove("supervisorName");
        node.forEach(this::removeSupervisorNames);
    }
    private void collectSets(JsonNode node, List<ObjectNode> sets) {
        if (node instanceof ObjectNode object && object.get("items") instanceof ArrayNode) sets.add(object);
        node.forEach(child -> collectSets(child, sets));
    }
    private ProjectSummary project(ProjectRepository.ChatProject p, Map<UUID, Long> applications) {
        return new ProjectSummary(p.getId(), p.getTitle(), p.getStatus(), p.getCompanyName(), utc(p.getDeadline()),
                p.getFinished(), applications.getOrDefault(p.getId(), 0L));
    }
    private PartySummary party(PartyRepository.ChatParty p) {
        return new PartySummary(p.getId(), p.getName(), p.getStatus(), p.getMemberCount(), p.getSupervisorName(),
                p.getAssignedProjectId(), p.getViewerIsLeader());
    }
    private ProjectBrief brief(ProjectRepository.ChatBrief p, Map<UUID, Long> applications) {
        List<String> clipped = new ArrayList<>();
        var tools = projectRepository.findChatTools(p.getId(), PageRequest.of(0, 21));
        if (tools.size() > 20 || tools.stream().anyMatch(tool -> tool.length() > 100)) clipped.add("tools");
        return new ProjectBrief(p.getId(), p.getTitle(), p.getStatus(), p.getCompanyName(), utc(p.getDeadline()),
                p.getFinished(), applications.getOrDefault(p.getId(), 0L),
                clip(p.getDescription(), "description", clipped), clip(p.getScope(), "scope", clipped),
                clip(p.getDeliverables(), "deliverables", clipped), clip(p.getEvaluationCriteria(), "evaluationCriteria", clipped),
                tools.stream().limit(20).map(tool -> tool.substring(0, Math.min(100, tool.length()))).toList(),
                p.getProjectField(), List.copyOf(clipped));
    }
    private String clip(String text, String field, List<String> clipped) {
        if (text == null || text.length() <= 1500) return text;
        clipped.add(field);
        return text.substring(0, 1500);
    }
    private MyScorecard scorecard(UUID userId) {
        return scorecardRepository.findChatScorecard(userId).map(s -> new MyScorecard(s.getAverageScore(),
                s.getTotalProjects() == null ? 0 : s.getTotalProjects(),
                new SummarySet<>(scorecardRepository.findChatEntries(userId, FIRST_50).stream()
                        .map(e -> new ScorecardEntrySummary(e.getId(), e.getProjectId(), e.getProjectTitle(), e.getScore(),
                                e.getSemester(), e.getAcademicYear())).toList(), scorecardRepository.countChatEntries(userId))))
                .orElse(null);
    }
    private static Instant utc(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static <E extends Enum<E>> Map<E, Long> counts(Class<E> type, List<Object[]> rows) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E key : type.getEnumConstants()) counts.put(key, 0L);
        rows.forEach(row -> counts.put(type.cast(row[0]), ((Number) row[1]).longValue()));
        return counts;
    }
    private static long total(Map<?, Long> counts) { return counts.values().stream().mapToLong(Long::longValue).sum(); }
}
