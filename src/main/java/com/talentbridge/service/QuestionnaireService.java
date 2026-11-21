package com.talentbridge.service;

import com.talentbridge.dto.request.QuestionnaireAnswerRequest;
import com.talentbridge.dto.response.*;
import com.talentbridge.entity.QuestionnaireAssignment;
import com.talentbridge.entity.QuestionnaireSnapshot;
import com.talentbridge.enums.QuestionnaireState;
import com.talentbridge.repository.QuestionnaireAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class QuestionnaireService {
    private final QuestionnaireAssignmentRepository assignments;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<QuestionnaireSummaryResponse> list(UUID studentId) {
        Instant now = clock.instant();
        return assignments.findAllByStudentIdOrderByCreatedAtDesc(studentId).stream().map(a -> {
            QuestionnaireResponse r = response(a, now);
            return new QuestionnaireSummaryResponse(r.id(), r.submissionId(), r.assessmentVersion(), r.projectTitle(), r.state(),
                    r.answerState(), r.revision(), r.answeredCount(), r.editable(), r.opensAt(), r.closesAt(), now);
        }).toList();
    }

    @Transactional(readOnly = true)
    public QuestionnaireResponse get(UUID id, UUID studentId) {
        return response(assignments.findByIdAndStudentId(id, studentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Questionnaire not found")), clock.instant());
    }

    @Transactional
    public QuestionnaireResponse save(UUID id, UUID studentId, QuestionnaireAnswerRequest request) {
        QuestionnaireAssignment a = assignments.findOwnedForUpdate(id, studentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Questionnaire not found"));
        // Check time after acquiring the row lock, not when the request started waiting.
        Instant now = clock.instant();
        if (!editable(a.getSnapshot(), now))
            throw error(HttpStatus.CONFLICT, "Questionnaire is not open for answers");
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0
                || request.answers() == null || request.answers().size() != 5)
            throw error(HttpStatus.BAD_REQUEST, "Expected a revision and all five answer entries");
        if (request.expectedRevision() != a.getRevision())
            throw error(HttpStatus.CONFLICT, "Answers changed in another session; reload before saving");
        Set<UUID> questionIds = new HashSet<>();
        a.getQuestions().forEach(q -> questionIds.add(q.getId()));
        if (questionIds.size() != 5)
            throw error(HttpStatus.CONFLICT, "Question set is not ready");
        Map<String, String> answers = new LinkedHashMap<>();
        for (QuestionnaireAnswerRequest.Answer answer : request.answers()) {
            if (answer == null || answer.questionId() == null || !questionIds.contains(answer.questionId())
                    || answer.text() == null || answer.text().length() > 2000
                    || answers.putIfAbsent(answer.questionId().toString(), answer.text()) != null)
                throw error(HttpStatus.BAD_REQUEST, "Answers require five distinct assigned question IDs and text of at most 2000 characters");
            if (answer.text().indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(answer.text()))
                throw error(HttpStatus.BAD_REQUEST, "Answer text contains unsupported Unicode");
        }
        a.setAnswers(answers);
        a.setSavedAt(now);
        assignments.saveAndFlush(a);
        return response(a, now);
    }

    private QuestionnaireResponse response(QuestionnaireAssignment a, Instant now) {
        QuestionnaireSnapshot s = a.getSnapshot();
        boolean published = published(s, now);
        List<QuestionnaireResponse.Question> questions = published ? a.getQuestions().stream()
                .sorted(Comparator.comparingInt(com.talentbridge.entity.QuestionnaireQuestion::getPosition))
                .map(q -> new QuestionnaireResponse.Question(q.getId(), q.getPosition(), q.getPrompt(),
                        q.getSourceReferences(), a.getAnswers().getOrDefault(q.getId().toString(), ""))).toList() : List.of();
        int answered = (int) questions.stream().filter(q -> !q.answer().isBlank()).count();
        boolean complete = questions.size() == 5 && answered == 5;
        boolean expired = published && !now.isBefore(s.getClosesAt());
        String answerState = !published || s.getState() == QuestionnaireState.FAILED ? "PENDING"
                : expired ? (complete ? "LOCKED_COMPLETE" : "INCOMPLETE") : complete ? "COMPLETE" : "DRAFT";
        QuestionnaireState state = s.getState() == QuestionnaireState.OPEN && expired
                ? QuestionnaireState.READY_FOR_EVALUATION : s.getState();
        return new QuestionnaireResponse(a.getId(), s.getSubmission().getId(), s.getAssessmentVersion(), s.getFrozenLabel(), state,
                answerState, a.getRevision(), answered, editable(s, now), s.getOpensAt(), s.getClosesAt(), now,
                a.getSavedAt(), questions);
    }

    private boolean published(QuestionnaireSnapshot s, Instant now) {
        return s.getState() != QuestionnaireState.GENERATING && s.getOpensAt() != null && s.getClosesAt() != null
                && !now.isBefore(s.getOpensAt());
    }

    private boolean editable(QuestionnaireSnapshot s, Instant now) {
        return s.getAssessmentVersion() == 2 && s.getState() == QuestionnaireState.OPEN && published(s, now)
                && now.isBefore(s.getClosesAt());
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
