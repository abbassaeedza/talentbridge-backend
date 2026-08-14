package com.talentbridge.service;

import com.talentbridge.dto.response.ScorecardResponse;
import com.talentbridge.entity.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScorecardService {
    private final ScorecardRepository scorecardRepository;

    @Transactional(readOnly = true)
    public ScorecardResponse getByStudentId(UUID studentId) {
        return scorecardRepository.findByStudentId(studentId)
                .map(ScorecardResponse::from)
                .orElse(null);
    }

    @Transactional
    public void addEntry(User student, Project project, EvaluationReport report) {
        Scorecard sc = scorecardRepository.findByStudentId(student.getId())
            .orElseGet(() -> scorecardRepository.save(
                Scorecard.builder().student(student).averageScore(0.0).totalProjects(0).build()));

        double score = report.getStudentScores().stream()
            .filter(s -> s.getStudent().getId().equals(student.getId()))
            .mapToDouble(StudentEvaluationScore::getIndividualScore).findFirst()
            .orElse(report.getTotalScore() != null ? report.getTotalScore() : 0.0);

        ScorecardEntry entry = sc.getEntries().stream()
                .filter(existing -> existing.getProject().getId().equals(project.getId()))
                .findFirst()
                .orElseGet(() -> {
                    ScorecardEntry created = ScorecardEntry.builder()
                            .scorecard(sc)
                            .project(project)
                            .build();
                    sc.getEntries().add(created);
                    return created;
                });
        entry.setEvaluationReport(report);
        entry.setScore(score);
        sc.setTotalProjects(sc.getEntries().size());
        sc.setAverageScore(Math.round(sc.getEntries().stream()
            .mapToDouble(ScorecardEntry::getScore).average().orElse(0.0) * 10.0) / 10.0);
        scorecardRepository.save(sc);
        log.info("Scorecard updated for {} - avg: {}", student.getEmail(), sc.getAverageScore());
    }
}
