package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.request.QuestionnaireAnswerRequest;
import com.talentbridge.entity.*;
import com.talentbridge.enums.QuestionnaireState;
import com.talentbridge.repository.QuestionnaireAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionnaireServiceTest {
    @Mock QuestionnaireAssignmentRepository assignments;
    final UUID student = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-20T12:00:00Z");
    QuestionnaireAssignment assignment;
    QuestionnaireSnapshot snapshot;
    QuestionnaireService service;

    @BeforeEach
    void fixture() {
        service = new QuestionnaireService(assignments, Clock.fixed(now, ZoneOffset.UTC));
        Submission submission = Submission.builder().build();
        submission.setId(UUID.randomUUID());
        snapshot = QuestionnaireSnapshot.builder().submission(submission).assessmentVersion(2)
                .state(QuestionnaireState.OPEN).frozenBrief("Frozen brief")
                .opensAt(now.minusSeconds(3600)).closesAt(now.plusSeconds(169200)).build();
        snapshot.setId(UUID.randomUUID());
        assignment = QuestionnaireAssignment.builder().snapshot(snapshot).studentId(student)
                .studentName("Frozen student").build();
        assignment.setId(UUID.randomUUID());
        for (int i = 1; i <= 5; i++) {
            QuestionnaireQuestion q = QuestionnaireQuestion.builder().assignment(assignment).position(i)
                    .prompt("Explain implementation " + i).hiddenRubric("PRIVATE RUBRIC")
                    .sourceReferences(new ObjectMapper().createArrayNode()).build();
            q.setId(UUID.randomUUID());
            assignment.getQuestions().add(q);
        }
    }

    @Test
    void frozenOwnerCanReadWithoutCurrentPartyMembership() {
        ownRead();
        var response = service.get(assignment.getId(), student);
        assertEquals(5, response.questions().size());
        assertEquals("DRAFT", response.answerState());
        assertEquals(2, response.assessmentVersion());
        assertEquals(now, response.serverNow());
    }

    @Test
    void ownerListDoesNotEnrollOrExposeAnotherMembersWork() {
        when(assignments.findAllByStudentIdOrderByCreatedAtDesc(student)).thenReturn(List.of(assignment));
        var response = service.list(student);
        assertEquals(1, response.size());
        assertEquals(assignment.getId(), response.get(0).id());
        assertEquals(0, response.get(0).answeredCount());
    }

    @Test
    void missingAssignmentsLeaveLegacySubmissionsUnchanged() {
        when(assignments.findAllByStudentIdOrderByCreatedAtDesc(student)).thenReturn(List.of());
        assertTrue(service.list(student).isEmpty());
        assertEquals(1, Submission.builder().build().getAssessmentVersion());
    }

    @Test
    void otherOwnerCannotReadOrSaveEvenKnowingTheId() {
        UUID other = UUID.randomUUID();
        assertStatus(404, () -> service.get(assignment.getId(), other));
        assertStatus(404, () -> service.save(assignment.getId(), other, answers("answer", 0)));
        assertTrue(assignment.getAnswers().isEmpty());
    }

    @Test
    void partiallyGeneratedQuestionsAreHiddenUntilCommonPublication() {
        ownRead();
        snapshot.setState(QuestionnaireState.GENERATING);
        snapshot.setOpensAt(null);
        snapshot.setClosesAt(null);
        var response = service.get(assignment.getId(), student);
        assertTrue(response.questions().isEmpty());
        assertEquals("PENDING", response.answerState());
        assertFalse(response.editable());
    }

    @Test
    void openWithoutPublicationStillHidesQuestions() {
        ownRead();
        snapshot.setOpensAt(null);
        snapshot.setClosesAt(null);
        assertTrue(service.get(assignment.getId(), student).questions().isEmpty());
    }

    @Test
    void futurePublicationDoesNotExposeQuestionsOrPermitSave() {
        ownRead();
        ownWrite();
        snapshot.setOpensAt(now.plusSeconds(1));
        assertTrue(service.get(assignment.getId(), student).questions().isEmpty());
        assertStatus(409, () -> service.save(assignment.getId(), student, answers("text", 0)));
    }

    @Test
    void partialSaveReplacesAllFiveValuesAtomicallyBeforeDeadline() {
        ownWrite();
        var entries = new ArrayList<>(answers("", 0).answers());
        entries.set(0, new QuestionnaireAnswerRequest.Answer(assignment.getQuestions().get(0).getId(), "my draft"));
        var response = service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(0L, entries));
        assertEquals("DRAFT", response.answerState());
        assertEquals(1, response.answeredCount());
        assertEquals("my draft", assignment.getAnswers().get(entries.get(0).questionId().toString()));
        assertEquals(5, assignment.getAnswers().size());
        assertTrue(response.editable());
    }

    @Test
    void completeSavedSetRemainsEditableUntilExpiry() {
        ownWrite();
        var response = service.save(assignment.getId(), student, answers("complete", 0));
        assertEquals("COMPLETE", response.answerState());
        assertEquals(5, response.answeredCount());
        assertTrue(response.editable());
    }

    @Test
    void saveJustBeforeExpirySucceeds() {
        ownWrite();
        snapshot.setClosesAt(now.plusNanos(1));
        assertTrue(service.save(assignment.getId(), student, answers("last", 0)).editable());
    }

    @Test
    void exactExpiryRejectsWritesAndLocksCompleteSavedAnswers() {
        ownRead();
        ownWrite();
        assignment.setAnswers(answerMap("saved"));
        snapshot.setClosesAt(now);
        assertStatus(409, () -> service.save(assignment.getId(), student, answers("late", 0)));
        var response = service.get(assignment.getId(), student);
        assertEquals("READY_FOR_EVALUATION", response.state().name());
        assertEquals("LOCKED_COMPLETE", response.answerState());
        assertFalse(response.editable());
        assertTrue(assignment.getAnswers().values().stream().allMatch("saved"::equals));
    }

    @Test
    void incompleteAtExpiryIsDistinctFromTechnicalFailurePending() {
        ownRead();
        snapshot.setClosesAt(now);
        assertEquals("INCOMPLETE", service.get(assignment.getId(), student).answerState());
        snapshot.setState(QuestionnaireState.FAILED);
        assertEquals("PENDING", service.get(assignment.getId(), student).answerState());
    }

    @Test
    void staleRevisionDoesNotOverwriteTheOtherTabsAnswers() {
        ownWrite();
        assignment.setRevision(4);
        assignment.setAnswers(answerMap("newer"));
        assertStatus(409, () -> service.save(assignment.getId(), student, answers("stale", 3)));
        assertTrue(assignment.getAnswers().values().stream().allMatch("newer"::equals));
        assertEquals(4, assignment.getRevision());
    }

    @Test
    void rejectsDuplicateQuestionIdsWithoutPartialMutation() {
        ownWrite();
        var entries = new ArrayList<>(answers("answer", 0).answers());
        entries.set(4, entries.get(0));
        assertStatus(400, () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(0L, entries)));
        assertTrue(assignment.getAnswers().isEmpty());
    }

    @Test
    void rejectsUnknownQuestionIdsWithoutPartialMutation() {
        ownWrite();
        var entries = new ArrayList<>(answers("answer", 0).answers());
        entries.set(4, new QuestionnaireAnswerRequest.Answer(UUID.randomUUID(), "foreign"));
        assertStatus(400, () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(0L, entries)));
        assertTrue(assignment.getAnswers().isEmpty());
    }

    @Test
    void requiresAllFiveEntriesEvenForAPartialSet() {
        ownWrite();
        assertStatus(400, () -> service.save(assignment.getId(), student,
                new QuestionnaireAnswerRequest(0L, answers("a", 0).answers().subList(0, 4))));
    }

    @Test
    void accepts2000CharactersAndRejects2001WithoutMutatingSavedSet() {
        ownWrite();
        assertEquals(5, service.save(assignment.getId(), student, answers("a".repeat(2000), 0)).answeredCount());
        var before = new HashMap<>(assignment.getAnswers());
        assertStatus(400, () -> service.save(assignment.getId(), student, answers("a".repeat(2001), 0)));
        assertEquals(before, assignment.getAnswers());
    }

    @Test
    void nullOrNegativeRevisionAndNullAnswersAreRejected() {
        ownWrite();
        assertStatus(400, () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(null, List.of())));
        assertStatus(400, () -> service.save(assignment.getId(), student, answers("text", -1)));
        assertStatus(400, () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(0L, null)));
        var entries = new ArrayList<>(answers("text", 0).answers());
        entries.set(4, new QuestionnaireAnswerRequest.Answer(entries.get(4).questionId(), null));
        assertStatus(400, () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(0L, entries)));
    }

    @ParameterizedTest(name = "unsupported Unicode case {index} is rejected before mutation")
    @ValueSource(strings = {"synthetic\0", "synthetic\uD800", "synthetic\uDC00", "synthetic\uDC00\uD800"})
    void rejectsUnsupportedUnicodeBeforeMutatingAnswers(String invalid) {
        ownWrite();
        assignment.setRevision(7);
        assignment.setAnswers(answerMap("Previously saved"));
        Instant savedAt = now.minusSeconds(30);
        assignment.setSavedAt(savedAt);
        var before = new HashMap<>(assignment.getAnswers());
        var entries = new ArrayList<>(answers("Replacement", 7).answers());
        entries.set(4, new QuestionnaireAnswerRequest.Answer(entries.get(4).questionId(), invalid));

        var error = assertThrows(ResponseStatusException.class,
                () -> service.save(assignment.getId(), student, new QuestionnaireAnswerRequest(7L, entries)));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("Answer text contains unsupported Unicode", error.getReason());
        assertEquals(before, assignment.getAnswers());
        assertEquals(7, assignment.getRevision());
        assertEquals(savedAt, assignment.getSavedAt());
    }

    @Test
    void preservesValidUnicodeWithoutRewriting() {
        ownWrite();
        String text = "  \uD83D\uDE80 \uD83D\uDE00 \u0627\u0631\u062F\u0648 e\u0301\n\t\u0001  ";
        var response = service.save(assignment.getId(), student, answers(text, 0));
        assertEquals(5, response.answeredCount());
        assertTrue(assignment.getAnswers().values().stream().allMatch(text::equals));
    }

    @Test
    void historicalSnapshotCanBeReadButNotSavedUnderCurrentRules() {
        ownRead();
        ownWrite();
        snapshot.setAssessmentVersion(1);
        assertEquals(1, service.get(assignment.getId(), student).assessmentVersion());
        assertStatus(409, () -> service.save(assignment.getId(), student, answers("new", 0)));
    }

    @Test
    void ownerPayloadNeverContainsRubricOrUnfinalizedScores() throws Exception {
        ownRead();
        assignment.getQuestions().get(0).setAiScore(18);
        assignment.getQuestions().get(0).setEffectiveScore(19);
        var mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(service.get(assignment.getId(), student));
        assertFalse(json.contains("PRIVATE RUBRIC"));
        assertFalse(json.contains("Rubric"));
        assertFalse(json.contains("Score"));
        assertFalse(json.contains("frozenBrief"));
    }

    private void ownRead() {
        when(assignments.findByIdAndStudentId(assignment.getId(), student)).thenReturn(Optional.of(assignment));
    }

    private void ownWrite() {
        when(assignments.findOwnedForUpdate(assignment.getId(), student)).thenReturn(Optional.of(assignment));
    }

    private QuestionnaireAnswerRequest answers(String text, long revision) {
        return new QuestionnaireAnswerRequest(revision, IntStream.range(0, 5)
                .mapToObj(i -> new QuestionnaireAnswerRequest.Answer(assignment.getQuestions().get(i).getId(), text)).toList());
    }

    private Map<String, String> answerMap(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        assignment.getQuestions().forEach(q -> result.put(q.getId().toString(), text));
        return result;
    }

    private void assertStatus(int expected, Runnable operation) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value());
    }
}
