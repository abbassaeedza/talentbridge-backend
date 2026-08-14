package com.talentbridge.service;

import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Scorecard;
import com.talentbridge.entity.ScorecardEntry;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.User;
import com.talentbridge.repository.ScorecardRepository;
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

        new ScorecardService(scorecardRepository).addEntry(student, project, updatedReport);

        assertEquals(1, scorecard.getEntries().size());
        assertEquals(75.0, scorecard.getEntries().get(0).getScore());
        assertSame(updatedReport, scorecard.getEntries().get(0).getEvaluationReport());
        assertEquals(75.0, scorecard.getAverageScore());
        verify(scorecardRepository).save(scorecard);
    }
}
