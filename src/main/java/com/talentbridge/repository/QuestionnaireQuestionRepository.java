package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface QuestionnaireQuestionRepository extends JpaRepository<QuestionnaireQuestion, UUID> {
}
