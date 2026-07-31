package com.talentbridge.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTransactionBoundaryTest {

    @Test
    void keepsProjectAndPartyResponseMappingInsideReadTransactions() {
        assertReadOnly(ProjectService.class);
        assertReadOnly(PartyService.class);
    }

    private void assertReadOnly(Class<?> serviceType) {
        Transactional transaction = AnnotatedElementUtils.findMergedAnnotation(
                serviceType, Transactional.class);
        assertNotNull(transaction, serviceType.getSimpleName() + " must be transactional");
        assertTrue(transaction.readOnly(),
                serviceType.getSimpleName() + " reads must keep lazy response mapping inside a session");
    }
}
