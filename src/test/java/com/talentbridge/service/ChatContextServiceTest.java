package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.entity.User;
import com.talentbridge.enums.*;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Pageable;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatContextServiceTest {
    ProjectRepository projects = mock(ProjectRepository.class);
    PartyRepository parties = mock(PartyRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    ApplicationRepository applications = mock(ApplicationRepository.class);
    UserRepository users = mock(UserRepository.class);
    ApplicationSettingsRepository settings = mock(ApplicationSettingsRepository.class);
    ScorecardRepository scorecards = mock(ScorecardRepository.class);
    EvaluationReportRepository evaluations = mock(EvaluationReportRepository.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Instant now = Instant.parse("2031-01-01T12:00:00Z");
    ChatContextService service = new ChatContextService(projects, parties, submissions, applications,
            users, settings, scorecards, evaluations, Clock.fixed(now, ZoneOffset.UTC), mapper);
    User user;

    @BeforeEach
    void fixture() {
        user = User.builder().firstName("Private").lastName("Viewer").email("private@example.test")
                .githubAccessToken("private-token").role(UserRole.STUDENT).status(UserStatus.APPROVED).build();
        user.setId(UUID.randomUUID());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void assignedStudentStillGetsOpenProjectsExactTotalsAndIndependentDeadlines() {
        var assigned = mock(ProjectRepository.ChatBrief.class);
        UUID projectId = UUID.randomUUID();
        when(assigned.getId()).thenReturn(projectId);
        when(assigned.getTitle()).thenReturn("Assigned");
        when(assigned.getStatus()).thenReturn(ProjectStatus.ASSIGNED);
        when(assigned.getScope()).thenReturn("x".repeat(1501));
        when(projects.findChatAssignedBrief(user.getId())).thenReturn(Optional.of(assigned));
        var open = mock(ProjectRepository.ChatProject.class);
        when(open.getId()).thenReturn(UUID.randomUUID());
        when(open.getTitle()).thenReturn("Open");
        when(open.getStatus()).thenReturn(ProjectStatus.OPEN);
        when(projects.findChatProjects(eq(user.getId()), eq(false), eq(true), any())).thenReturn(List.of(open));
        when(projects.countChatProjects(user.getId(), false, true))
                .thenReturn(List.<Object[]>of(new Object[]{ProjectStatus.OPEN, 51L}));
        when(projects.countChatApplications(anyList())).thenReturn(List.<Object[]>of(new Object[]{projectId, 7L}));
        var deadline = mock(ProjectRepository.ChatProject.class);
        when(deadline.getId()).thenReturn(UUID.randomUUID());
        when(deadline.getTitle()).thenReturn("Outside first fifty");
        when(deadline.getDeadline()).thenReturn(LocalDateTime.of(2031, 1, 2, 0, 0));
        when(projects.findChatUpcoming(eq(user.getId()), eq(false), any(), any())).thenReturn(List.of(deadline));
        when(projects.countChatUpcoming(eq(user.getId()), eq(false), any())).thenReturn(60L);

        var snapshot = service.snapshot(user.getId());

        assertEquals(now, snapshot.snapshotTimeUTC());
        assertEquals("Open", snapshot.openProjects().items().get(0).title());
        assertEquals(51, snapshot.openProjects().total());
        assertTrue(snapshot.openProjects().truncated());
        assertEquals(7, snapshot.myAssignedProject().partyApplicationCount());
        assertEquals(1500, snapshot.myAssignedProject().scope().length());
        assertEquals(List.of("scope"), snapshot.myAssignedProject().truncatedFields());
        assertEquals(60, snapshot.upcomingDeadlines().total());
        assertEquals(Instant.parse("2031-01-02T00:00:00Z"), snapshot.upcomingDeadlines().items().get(0).deadlineUTC());
        verify(projects).findChatUpcoming(eq(user.getId()), eq(false), eq(LocalDateTime.of(2031,1,1,12,0)),
                argThat(page -> page.getPageSize() == 50 && page.getOffset() == 0));
        verify(projects, never()).findAll();
        verify(projects, never()).countApplicationsByProjectId(any());
        assertNull(snapshot.counts().users());
    }

    @Test
    void coordinatorUsesAggregatesWithoutDirectoryOrEmailAndFormatterStripsIdentity() throws Exception {
        user.setRole(UserRole.COORDINATOR);
        when(users.countByRoleGrouped()).thenReturn(List.<Object[]>of(new Object[]{UserRole.STUDENT, 201L}));
        when(users.countByStatusGrouped()).thenReturn(List.<Object[]>of(new Object[]{UserStatus.APPROVED, 201L}));
        var snapshot = service.snapshot(user.getId());
        assertEquals(201, snapshot.counts().users().total());
        assertEquals(0L, snapshot.counts().users().byStatus().get(UserStatus.PENDING));
        String wire = mapper.writeValueAsString(snapshot);
        assertTrue(wire.contains("Private Viewer"));
        assertFalse(wire.contains("private@example.test"));
        assertFalse(wire.contains("private-token"));
        var model = mapper.readTree(service.formatForModel(snapshot));
        assertEquals("COORDINATOR", model.path("viewer").path("role").asText());
        assertFalse(model.path("viewer").has("id"));
        assertFalse(model.path("viewer").has("name"));
        verify(users, never()).findAll();
    }

    @Test
    void modelBudgetKeepsJsonAndCountsWithoutMutatingSnapshot() throws Exception {
        var row = mock(ProjectRepository.ChatProject.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getTitle()).thenReturn("t".repeat(100));
        when(row.getCompanyName()).thenReturn("c".repeat(255));
        when(row.getStatus()).thenReturn(ProjectStatus.OPEN);
        var fifty = Collections.nCopies(50, row);
        when(projects.findChatProjects(eq(user.getId()), eq(false), anyBoolean(), any(Pageable.class))).thenReturn(fifty);
        when(projects.countChatProjects(eq(user.getId()), eq(false), anyBoolean()))
                .thenReturn(List.<Object[]>of(new Object[]{ProjectStatus.OPEN, 50L}));
        var snapshot = service.snapshot(user.getId());
        String formatted = service.formatForModel(snapshot);
        assertTrue(formatted.length() <= 24000);
        var model = mapper.readTree(formatted);
        assertEquals(50, model.path("projects").path("total").asInt());
        assertTrue(model.path("projects").path("truncated").asBoolean());
        assertEquals(50, snapshot.projects().items().size());
        assertFalse(snapshot.projects().truncated());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"PENDING", "REJECTED", "SUSPENDED"})
    void rejectsNonApprovedUsersBeforeAnyContextQuery(UserStatus status) {
        user.setStatus(status);
        assertThrows(ForbiddenException.class, () -> service.snapshot(user.getId()));
        verifyNoInteractions(projects, parties, submissions, applications, evaluations, scorecards, settings);
    }

    @Test
    void escapedSingletonBriefStaysWithinSerializedBudgetAndMarksClipping() throws Exception {
        var assigned = mock(ProjectRepository.ChatBrief.class);
        when(assigned.getId()).thenReturn(UUID.randomUUID());
        when(assigned.getTitle()).thenReturn("Assigned");
        when(assigned.getStatus()).thenReturn(ProjectStatus.ASSIGNED);
        String escaped = "\u0001".repeat(1500);
        when(assigned.getDescription()).thenReturn(escaped);
        when(assigned.getScope()).thenReturn(escaped);
        when(assigned.getDeliverables()).thenReturn(escaped);
        when(assigned.getEvaluationCriteria()).thenReturn(escaped);
        when(projects.findChatAssignedBrief(user.getId())).thenReturn(Optional.of(assigned));
        var snapshot = service.snapshot(user.getId());

        String formatted = service.formatForModel(snapshot);

        assertTrue(formatted.length() <= 24000);
        var model = mapper.readTree(formatted);
        assertTrue(model.path("myAssignedProject").path("truncatedFields").size() > 0);
        assertEquals(assigned.getId().toString(), model.path("myAssignedProject").path("id").asText());
        assertEquals(0, model.path("projects").path("total").asInt());
        assertEquals(escaped, snapshot.myAssignedProject().description());
        assertTrue(snapshot.myAssignedProject().truncatedFields().isEmpty());
    }
}
