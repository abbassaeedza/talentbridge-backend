package com.talentbridge.service;

import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Scorecard;
import com.talentbridge.entity.ScorecardEntry;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.User;
import com.talentbridge.entity.Submission;
import com.talentbridge.repository.ScorecardRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScorecardServiceTest {
    @Mock private ScorecardRepository scorecardRepository;
    @Mock private JdbcTemplate jdbc;

    @Test
    void reevaluationUpdatesTheExistingProjectEntryInsteadOfDuplicatingIt() {
        User student = User.builder().email("student@example.com").build();
        student.setId(UUID.randomUUID());
        Project project = Project.builder().title("Project").build();
        project.setId(UUID.randomUUID());
        EvaluationReport oldReport = EvaluationReport.builder().totalScore(60.0).build();
        EvaluationReport updatedReport = EvaluationReport.builder()
                .totalScore(70.0)
                .studentScores(List.of(StudentEvaluationScore.builder()
                        .student(student)
                        .individualScore(75.0)
                        .build()))
                .build();
        Scorecard scorecard = Scorecard.builder()
                .student(student)
                .entries(new ArrayList<>())
                .averageScore(60.0)
                .totalProjects(1)
                .build();
        scorecard.getEntries().add(ScorecardEntry.builder()
                .scorecard(scorecard)
                .project(project)
                .evaluationReport(oldReport)
                .score(60.0)
                .build());
        when(scorecardRepository.findByStudentId(student.getId())).thenReturn(Optional.of(scorecard));

        new ScorecardService(scorecardRepository,jdbc).addEntry(student, project, updatedReport);

        assertEquals(1, scorecard.getEntries().size());
        assertEquals(75.0, scorecard.getEntries().get(0).getScore());
        assertSame(updatedReport, scorecard.getEntries().get(0).getEvaluationReport());
        assertEquals(75.0, scorecard.getAverageScore());
        verify(scorecardRepository).save(scorecard);
        var ordered=org.mockito.Mockito.inOrder(jdbc,scorecardRepository);
        ordered.verify(jdbc).queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,student.getId());
        ordered.verify(scorecardRepository).findByStudentId(student.getId());
    }

    @Test void versionTwoPublishesANewReportEntryWithoutOverwritingLegacyGradeForTheSameProject() {
        var student=User.builder().build();student.setId(UUID.randomUUID());
        var project=Project.builder().build();project.setId(UUID.randomUUID());
        var legacy=EvaluationReport.builder().totalScore(77.3).build();legacy.setId(UUID.randomUUID());
        var report=EvaluationReport.builder().submission(Submission.builder().assessmentVersion(2).build()).totalScore(81.0).build();report.setId(UUID.randomUUID());
        var card=Scorecard.builder().student(student).build();
        card.getEntries().add(ScorecardEntry.builder().scorecard(card).project(project).evaluationReport(legacy).score(77.3).build());
        when(scorecardRepository.findByStudentId(student.getId())).thenReturn(Optional.of(card));
        var service=new ScorecardService(scorecardRepository,jdbc);service.addEntry(student,project,report);service.addEntry(student,project,report);
        assertEquals(2,card.getEntries().size());assertEquals(77.3,card.getEntries().get(0).getScore());assertSame(legacy,card.getEntries().get(0).getEvaluationReport());
        assertEquals(81,card.getEntries().get(1).getScore());assertSame(report,card.getEntries().get(1).getEvaluationReport());
    }

    @Test void laterLegacyRefreshCannotOverwriteAFinalizedVersionTwoEntry() {
        var student=User.builder().build();student.setId(UUID.randomUUID());
        var project=Project.builder().build();project.setId(UUID.randomUUID());
        var finalized=EvaluationReport.builder().submission(Submission.builder().assessmentVersion(2).build()).totalScore(81.0).finalized(true).build();finalized.setId(UUID.randomUUID());
        var legacy=EvaluationReport.builder().submission(Submission.builder().assessmentVersion(1).build()).totalScore(70.0).build();legacy.setId(UUID.randomUUID());
        var card=Scorecard.builder().student(student).build();
        card.getEntries().add(ScorecardEntry.builder().scorecard(card).project(project).evaluationReport(finalized).score(81.0).build());
        when(scorecardRepository.findByStudentId(student.getId())).thenReturn(Optional.of(card));
        new ScorecardService(scorecardRepository,jdbc).addEntry(student,project,legacy);
        assertEquals(2,card.getEntries().size());assertEquals(81,card.getEntries().get(0).getScore());assertSame(finalized,card.getEntries().get(0).getEvaluationReport());
    }
}
