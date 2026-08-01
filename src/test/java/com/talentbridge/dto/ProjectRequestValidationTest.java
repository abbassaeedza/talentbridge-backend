package com.talentbridge.dto;

import com.talentbridge.dto.request.ProjectRequest;
import com.talentbridge.dto.request.ApplicationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsProjectTitlesLongerThanOneHundredCharacters() {
        ProjectRequest request = new ProjectRequest();
        request.setTitle("x".repeat(101));
        request.setDescription("Valid description");

        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("title")));
    }

    @Test
    void rejectsApplicationProposalsLongerThanTwoThousandCharacters() {
        ApplicationRequest request = new ApplicationRequest();
        request.setProjectId(UUID.randomUUID());
        request.setRankPosition(1);
        request.setProposalText("x".repeat(2001));

        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("proposalText")));
    }
}
