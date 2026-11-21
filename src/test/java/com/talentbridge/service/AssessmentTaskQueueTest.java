package com.talentbridge.service;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.tasks.v2.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssessmentTaskQueueTest {
    @Test void sendsOnlyJobReferenceWithNamedGenerationOidcAndSchedule() {
        CloudTasksClient client = mock();
        var queue = new AssessmentTaskQueue("synthetic-project", "asia-south1", "synthetic-queue",
                "https://synthetic.invalid", "https://audience.invalid", "worker@synthetic.iam.gserviceaccount.com", () -> client);
        UUID job = UUID.randomUUID(); Instant due = Instant.parse("2026-11-21T19:00:00Z");
        queue.enqueue(job, 7, due);
        verify(client).createTask(eq("projects/synthetic-project/locations/asia-south1/queues/synthetic-queue"), argThat(task ->
                task.getName().endsWith("/tasks/" + job + "-g7") && task.getScheduleTime().getSeconds() == due.getEpochSecond()
                && task.getDispatchDeadline().getSeconds()==115
                && task.getHttpRequest().getBody().toStringUtf8().equals("{\"jobId\":\"" + job + "\"}")
                && task.getHttpRequest().getHttpMethod() == HttpMethod.POST
                && task.getHttpRequest().getUrl().equals("https://synthetic.invalid/internal/assessment-jobs/" + job)
                && task.getHttpRequest().getOidcToken().getAudience().equals("https://audience.invalid")
                && task.getHttpRequest().getOidcToken().getServiceAccountEmail().equals("worker@synthetic.iam.gserviceaccount.com")));
    }

    @Test void duplicateNameIsSuccessButOtherFailuresAreStaticAndSafe() {
        CloudTasksClient client = mock();
        var queue = new AssessmentTaskQueue("project", "location", "queue", "https://synthetic.invalid",
                "audience", "worker@synthetic.iam.gserviceaccount.com", () -> client);
        when(client.createTask(anyString(), any(Task.class))).thenThrow(mock(AlreadyExistsException.class));
        assertDoesNotThrow(() -> queue.enqueue(UUID.randomUUID(), 0, Instant.now()));
        when(client.createTask(anyString(), any(Task.class))).thenThrow(new IllegalStateException("raw secret provider body"));
        var failure = assertThrows(AssessmentTaskQueue.DispatchException.class, () -> queue.enqueue(UUID.randomUUID(), 1, Instant.now()));
        assertEquals("DISPATCH_UNAVAILABLE", failure.getMessage()); assertNull(failure.getCause());
    }

    @Test void defaultConstructionDoesNotDiscoverAdcAndMissingSettingsFailBeforeClientCreation() {
        var queue = new AssessmentTaskQueue("", "", "", "", "", "");
        assertEquals("DISPATCH_CONFIGURATION", assertThrows(AssessmentTaskQueue.DispatchException.class,
                () -> queue.enqueue(UUID.randomUUID(), 0, Instant.now())).getMessage());
    }

    @Test void productionSettingsBoundOneRpcAndAmbientTransactionsFailBeforeClientCreation() throws Exception {
        var settings=AssessmentTaskQueue.settings();
        assertEquals(1,settings.createTaskSettings().getRetrySettings().getMaxAttempts());
        assertEquals(8000,settings.createTaskSettings().getRetrySettings().getTotalTimeout().toMillis());
        CloudTasksClient client=mock();
        var queue=new AssessmentTaskQueue("project","location","queue","https://synthetic.invalid","audience","worker@synthetic.iam.gserviceaccount.com",()->client);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class,()->queue.enqueue(UUID.randomUUID(),0,Instant.now())); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(client);
    }
}
