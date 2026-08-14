package com.talentbridge.dto;

import com.talentbridge.dto.response.EvaluationReportResponse;
import com.talentbridge.dto.response.ScorecardResponse;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Scorecard;
import com.talentbridge.entity.ScorecardEntry;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationResponseMappingTest {

    @Test
    void includesReportDetailsInsideScorecardEntries() {
        User student = User.builder()
                .email("student@example.com")
                .firstName("Demo")
                .lastName("Student")
                .githubUsername("tryinnewthings505-a11y")
                .build();
        student.setId(UUID.randomUUID());

        Submission submission = Submission.builder().build();
        submission.setId(UUID.randomUUID());
        EvaluationReport report = EvaluationReport.builder()
                .submission(submission)
                .totalScore(63.0)
                .overallSummary("Strong scope alignment.")
                .studentScores(new ArrayList<>())
                .build();
        report.setId(UUID.randomUUID());
        StudentEvaluationScore studentScore = StudentEvaluationScore.builder()
                .evaluationReport(report)
                .student(student)
                .individualScore(54.0)
                .totalCommits(4)
                .contributionPercentage(25.0)
                .build();
        studentScore.setId(UUID.randomUUID());
        report.getStudentScores().add(studentScore);

        Project project = Project.builder().title("Network Health Monitor").build();
        project.setId(UUID.randomUUID());
        ScorecardEntry entry = ScorecardEntry.builder()
                .project(project)
                .evaluationReport(report)
                .score(54.0)
                .build();
        entry.setId(UUID.randomUUID());
        Scorecard scorecard = Scorecard.builder()
                .student(student)
                .entries(List.of(entry))
                .averageScore(54.0)
                .totalProjects(1)
                .build();
        scorecard.setId(UUID.randomUUID());

        ScorecardResponse response = ScorecardResponse.from(scorecard);
        EvaluationReportResponse mappedReport = response.getEntries().get(0).getEvaluationReport();

        assertEquals(54.0, response.getEntries().get(0).getScore());
        assertEquals(63.0, mappedReport.getTotalScore());
        assertEquals(54.0, mappedReport.getStudentScores().get(0).getIndividualScore());
        assertEquals("tryinnewthings505-a11y",
                mappedReport.getStudentScores().get(0).getStudent().getGithubUsername());
        assertEquals("Strong scope alignment.", mappedReport.getOverallSummary());
    }
}
