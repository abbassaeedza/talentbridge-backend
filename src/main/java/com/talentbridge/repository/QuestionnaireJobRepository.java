package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface QuestionnaireJobRepository extends JpaRepository<QuestionnaireJob, UUID> {
    Optional<QuestionnaireJob> findByJobKey(String jobKey);
}
