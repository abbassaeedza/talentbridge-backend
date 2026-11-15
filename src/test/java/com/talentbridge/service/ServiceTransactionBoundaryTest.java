package com.talentbridge.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTransactionBoundaryTest {

    @Test
    void keepsProjectAndPartyResponseMappingInsideReadTransactions() {
        assertReadOnly(ProjectService.class);
        assertReadOnly(PartyService.class);
    }

    @Test
    void snapshotIsRepeatableReadButControllerAndProviderHaveNoTransaction() throws Exception {
        var transaction = ChatContextService.class.getMethod("snapshot", UUID.class).getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertTrue(transaction.readOnly());
        org.junit.jupiter.api.Assertions.assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
        org.junit.jupiter.api.Assertions.assertNull(AnnotatedElementUtils.findMergedAnnotation(OpenAIService.class, Transactional.class));
        org.junit.jupiter.api.Assertions.assertNull(AnnotatedElementUtils.findMergedAnnotation(
                com.talentbridge.controller.ChatController.class, Transactional.class));
    }

    private void assertReadOnly(Class<?> serviceType) {
        Transactional transaction = AnnotatedElementUtils.findMergedAnnotation(
                serviceType, Transactional.class);
        assertNotNull(transaction, serviceType.getSimpleName() + " must be transactional");
        assertTrue(transaction.readOnly(),
                serviceType.getSimpleName() + " reads must keep lazy response mapping inside a session");
    }
}
