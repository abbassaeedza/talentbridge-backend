package com.talentbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.entity.*;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {
    private final SubmissionRepository submissionRepository;
    private final EvaluationReportRepository evaluationReportRepository;
    private final UserRepository userRepository;
    private final ScorecardService scorecardService;
    private final GitHubService gitHubService;
    private final OpenAIService openAIService;
    private final NotificationService notificationService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public EvaluationReport triggerEvaluation(UUID submissionId, UUID coordinatorId) {
        Submission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        User coordinator = userRepository.findById(coordinatorId)
            .orElseThrow(() -> new ResourceNotFoundException("User", coordinatorId.toString()));
        if (submission.getStatus() == SubmissionStatus.EVALUATED)
            throw new BadRequestException("Already evaluated");
        if (submission.getRepoUrl() == null || submission.getRepoUrl().isBlank())
            throw new BadRequestException("No GitHub repository URL");

        submission.setStatus(SubmissionStatus.UNDER_EVALUATION);
        submissionRepository.save(submission);

        Party party = submission.getParty();
        Project project = submission.getProject();
        String token = party.getLeader().getGithubAccessToken();

        String repoContent = gitHubService.fetchRepositoryContent(submission.getRepoUrl(), token);
        List<String> contributorStats = gitHubService.fetchContributorStats(submission.getRepoUrl(), token);
        Map<String, Integer> commitMap = gitHubService.fetchCommitCountPerAuthor(submission.getRepoUrl(), token);

        String aiJson = openAIService.evaluateRepository(repoContent, project.getScope(),
            project.getDeliverables(), contributorStats);

        EvaluationReport report = parseAndPersist(aiJson, submission, coordinator, party, commitMap);
        party.getMembers().forEach(m -> scorecardService.addEntry(m, project, report));

        submission.setStatus(SubmissionStatus.EVALUATED);
        submissionRepository.save(submission);
        notificationService.notifyEvaluationComplete(party, report);
        log.info("Evaluation complete. Score: {}", report.getTotalScore());
        return report;
    }

    @Transactional
    public EvaluationReport finalizeReport(UUID reportId, UUID coordinatorId) {
        EvaluationReport report = evaluationReportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException("EvaluationReport", reportId.toString()));
        if (report.isFinalized()) throw new BadRequestException("Already finalized");
        report.setFinalized(true);
        return evaluationReportRepository.save(report);
    }

    public Optional<EvaluationReport> getBySubmissionId(UUID submissionId) {
        return evaluationReportRepository.findBySubmissionId(submissionId);
    }

    private EvaluationReport parseAndPersist(String aiJson, Submission submission,
                                              User triggeredBy, Party party, Map<String, Integer> commitMap) {
        try {
            String clean = aiJson.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            JsonNode r = mapper.readTree(clean);

            EvaluationReport report = evaluationReportRepository.save(EvaluationReport.builder()
                .submission(submission).triggeredBy(triggeredBy).evaluatedAt(LocalDateTime.now())
                .aiDetectionScore(r.path("aiDetectionScore").asDouble(0))
                .aiDetectionNotes(r.path("aiDetectionNotes").asText(""))
                .codeQualityScore(r.path("codeQualityScore").asDouble(0))
                .codeQualityNotes(r.path("codeQualityNotes").asText(""))
                .functionalityScore(r.path("functionalityScore").asDouble(0))
                .functionalityNotes(r.path("functionalityNotes").asText(""))
                .scopeAlignmentScore(r.path("scopeAlignmentScore").asDouble(0))
                .scopeAlignmentNotes(r.path("scopeAlignmentNotes").asText(""))
                .teamCollaborationScore(r.path("teamCollaborationScore").asDouble(0))
                .teamCollaborationNotes(r.path("teamCollaborationNotes").asText(""))
                .totalScore(r.path("totalScore").asDouble(0))
                .overallSummary(r.path("overallSummary").asText(""))
                .finalized(false).build());

            int totalCommits = commitMap.values().stream().mapToInt(Integer::intValue).sum();
            List<StudentEvaluationScore> scores = new ArrayList<>();
            for (User member : party.getMembers()) {
                String gh = member.getGithubUsername() != null ? member.getGithubUsername()
                    : member.getEmail().split("@")[0];
                int commits = commitMap.getOrDefault(gh, 0);
                double pct = totalCommits > 0 ? (commits * 100.0 / totalCommits) : 0;
                double expectedPct = 100.0 / party.getMembers().size();
                double factor = pct / expectedPct;
                double base = report.getTotalScore() != null ? report.getTotalScore() : 0;
                double individual = Math.min(100, Math.max(0, base * (0.85 + Math.min(factor, 1.3) * 0.15)));
                scores.add(StudentEvaluationScore.builder()
                    .evaluationReport(report).student(member).totalCommits(commits)
                    .contributionPercentage(Math.round(pct * 10.0) / 10.0)
                    .individualScore(Math.round(individual * 10.0) / 10.0)
                    .performanceNotes(String.format("%d commits (%.1f%%). Score: %.1f/100.", commits, pct, individual))
                    .build());
            }
            report.setStudentScores(scores);
            return evaluationReportRepository.save(report);
        } catch (Exception e) {
            log.error("Failed to parse AI evaluation: {}", aiJson, e);
            throw new RuntimeException("Could not parse evaluation response: " + e.getMessage(), e);
        }
    }
}
