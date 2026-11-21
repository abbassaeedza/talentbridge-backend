package com.talentbridge.service;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.tasks.v2.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class AssessmentTaskQueue {
    private final String project, location, queue, target, audience, caller;
    private final Supplier<CloudTasksClient> factory;
    private CloudTasksClient client;

    @Autowired
    public AssessmentTaskQueue(@Value("${assessment.tasks.project:}") String project,
            @Value("${assessment.tasks.location:}") String location, @Value("${assessment.tasks.queue:}") String queue,
            @Value("${assessment.tasks.target:}") String target, @Value("${assessment.tasks.audience:}") String audience,
            @Value("${assessment.tasks.caller-email:}") String caller) {
        this(project, location, queue, target, audience, caller, () -> {
            try {
                return CloudTasksClient.create(settings());
            } catch (Exception failure) { throw new DispatchException("DISPATCH_UNAVAILABLE"); }
        });
    }

    static CloudTasksSettings settings() throws java.io.IOException {
        var settings = CloudTasksSettings.newBuilder();
        settings.createTaskSettings().setRetrySettings(settings.createTaskSettings().getRetrySettings().toBuilder()
                .setMaxAttempts(1).setTotalTimeout(org.threeten.bp.Duration.ofSeconds(8)).build());
        return settings.build();
    }

    AssessmentTaskQueue(String project, String location, String queue, String target, String audience,
            String caller, Supplier<CloudTasksClient> factory) {
        this.project=project; this.location=location; this.queue=queue; this.target=target;
        this.audience=audience; this.caller=caller; this.factory=factory;
    }

    public void enqueue(UUID id, int generation, Instant due) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dispatch requires a nontransactional caller");
        if (project.isBlank() || location.isBlank() || queue.isBlank() || !target.matches("https://[^/?#]+")
                || audience.isBlank() || !caller.endsWith(".iam.gserviceaccount.com")) throw new DispatchException("DISPATCH_CONFIGURATION");
        try {
            String parent = QueueName.of(project, location, queue).toString();
            // https://docs.cloud.google.com/tasks/docs/creating-http-target-tasks
            Task task = Task.newBuilder().setName(parent + "/tasks/" + id + "-g" + generation)
                    .setScheduleTime(Timestamp.newBuilder().setSeconds(due.getEpochSecond()).setNanos(due.getNano()))
                    .setDispatchDeadline(com.google.protobuf.Duration.newBuilder().setSeconds(115))
                    .setHttpRequest(HttpRequest.newBuilder().setHttpMethod(HttpMethod.POST)
                            .setUrl(target + "/internal/assessment-jobs/" + id).putHeaders("Content-Type", "application/json")
                            .setBody(ByteString.copyFromUtf8("{\"jobId\":\"" + id + "\"}"))
                            .setOidcToken(OidcToken.newBuilder().setAudience(audience).setServiceAccountEmail(caller))).build();
            client().createTask(parent, task);
        } catch (AlreadyExistsException duplicate) {
            // Named-task retention deduplicates enqueue, not model execution.
        } catch (Exception failure) { throw new DispatchException("DISPATCH_UNAVAILABLE"); }
    }

    private synchronized CloudTasksClient client() {
        if (client == null) client = factory.get();
        return client;
    }
    @PreDestroy public synchronized void close() { if (client != null) client.close(); }
    public static final class DispatchException extends RuntimeException {
        public DispatchException(String code) { super(code); }
    }
}
