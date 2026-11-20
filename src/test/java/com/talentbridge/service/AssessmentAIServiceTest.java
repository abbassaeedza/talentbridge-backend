package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.talentbridge.service.AssessmentAIService.*;
import static com.talentbridge.service.GitHubEvidence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentAIServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final UUID member = UUID.fromString("00000000-0000-4000-8000-000000000001");
    final List<UUID> ids = java.util.stream.IntStream.range(2, 7).mapToObj(i ->
            UUID.fromString("00000000-0000-4000-8000-00000000000" + i)).toList();
    final List<String> prompts = List.of("Explain how src/Engine.java validates a submitted revision before writing data.",
            "Why does src/Engine.java acquire a row lock before reading the server clock?",
            "Describe the rollback behavior when the Engine insert fails after updating its counter.",
            "How would the Engine handle two simultaneous saves for the same assignment?",
            "Which boundary values would you test for the Engine publication deadline and why?");
    HttpServer server;
    OpenAIService transport;
    AssessmentQuotaService quota;
    AssessmentAIService service;
    AtomicReference<String> reply = new AtomicReference<>();
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> request = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/relay", e -> {
            calls.incrementAndGet();
            assertEquals("synthetic-relay-secret", e.getRequestHeaders().getFirst("X-TB-Secret"));
            request.set(mapper.readTree(e.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(Map.of("message", reply.get()));
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        transport = new OpenAIService(mapper);
        ReflectionTestUtils.setField(transport, "n8nWebhookUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/relay");
        ReflectionTestUtils.setField(transport, "n8nWebhookSecret", "synthetic-relay-secret");
        ReflectionTestUtils.setField(transport, "evalModel", "gpt-4o");
        quota = mock(AssessmentQuotaService.class);
        service = new AssessmentAIService(mapper, transport, quota, new GitHubEvidenceService(mapper));
        reply.set(generation().toString());
    }
    @AfterEach void stop() { server.stop(0); }

    GitHubEvidence evidence() {
        return new GitHubEvidence(new Resolution(new Repository("synthetic", "repo", "https://github.com/synthetic/repo"),
                null, "main", "a".repeat(40), "b".repeat(40)), Instant.EPOCH, List.of(),
                List.of(new FileEvidence(Kind.SOURCE, "src/Engine.java", "c".repeat(40), "d".repeat(64),
                        22, 2, 1, 2, "class Engine {\n}\n", false)),
                new ContributorSnapshot(Instant.EPOCH, false, false, List.of(new Contributor("private-login", 4))),
                List.of(), new Bounds(16, 32000, 17, 4000, 8, 1000, 128, 8192, 65536, 262144, 2097152, 100));
    }
    ObjectNode generation() {
        var root = mapper.createObjectNode(); root.put("memberId", member.toString());
        var questions = root.putArray("questions");
        for (int i = 0; i < 5; i++) {
            var q = questions.addObject(); q.put("id", ids.get(i).toString()); q.put("prompt", prompts.get(i));
            q.put("rubric", "Identify the actual Engine behavior and justify its boundary conditions.");
            q.putArray("sourceReferences").addObject().put("path", "src/Engine.java").put("startLine", 1).put("endLine", 2);
        }
        return root;
    }
    PreparedGeneration prepared() { return service.prepareGeneration("Build a safe Engine", evidence(), member, ids, List.of()); }
    List<MemberAnswers> answers() {
        var generated = service.generate(prepared());
        return List.of(new MemberAnswers(member, generated.questions().stream().map(q ->
                new Answer(q.id(), q.prompt(), q.rubric(), q.sourceReferences(), "Engine validates the saved revision.")).toList()));
    }
    ObjectNode repositoryEvaluation() {
        var root = mapper.createObjectNode(); var repository = root.putObject("repository");
        for (String key : List.of("authenticity", "quality", "functionality", "scope", "collaboration"))
            repository.putObject(key).put("score", 84.5).put("notes", "Limited captured evidence; uncertain provenance, not proof of authorship.");
        return root;
    }
    ObjectNode questionnaireEvaluation() {
        var root = mapper.createObjectNode();
        var m = root.putArray("members").addObject(); m.put("memberId", member.toString()); var qs = m.putArray("questions");
        for (UUID id : ids) qs.addObject().put("id", id.toString()).put("mark", 16).put("feedback", "Explains the actual revision boundary.");
        return root;
    }

    @Test void generatesTypedFiveQuestionsUsingOnlyOpaqueIdsAndSelectedEvidence() {
        var result = service.generate(prepared());
        assertEquals(member, result.memberId()); assertEquals(5, result.questions().size());
        assertEquals(new SourceReference("src/Engine.java", 1, 2), result.questions().get(0).sourceReferences().get(0));
        assertEquals(4096, request.get().path("maxTokens").intValue());
        assertEquals("questionnaire_generation", request.get().path("operation").textValue());
        assertEquals("gpt-4o", request.get().path("model").textValue());
        assertFalse(request.get().toString().contains("private-login"));
        assertTrue(request.get().path("message").textValue().contains("a".repeat(40)));
        verify(quota).reserve(); assertEquals(1, calls.get());
    }
    @Test void gradesOnlyCompleteExpectedIdsWithoutComputingTeamTotals() {
        var complete = answers(); reply.set(questionnaireEvaluation().toString());
        var result = service.evaluateQuestionnaire(service.prepareQuestionnaireEvaluation("Build a safe Engine", evidence(), complete));
        assertEquals(16, result.members().get(0).questions().get(0).mark());
        assertEquals(8192, request.get().path("maxTokens").intValue());
        assertEquals("questionnaire_evaluation", request.get().path("operation").textValue());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "extra", "duplicate", "near", "reference", "hidden", "id", "type", "nul", "surrogate", "trailing", "keys", "fence"})
    void rejectsMalformedGenerationInsteadOfPublishing(String mutation) {
        var body = generation(); var qs = (com.fasterxml.jackson.databind.node.ArrayNode) body.get("questions");
        var q = (ObjectNode) qs.get(0);
        switch (mutation) {
            case "missing" -> qs.remove(4);
            case "extra" -> qs.add(q.deepCopy());
            case "duplicate" -> ((ObjectNode) qs.get(1)).put("prompt", prompts.get(0));
            case "near" -> ((ObjectNode) qs.get(1)).put("prompt", prompts.get(0).toUpperCase() + "!");
            case "reference" -> ((ObjectNode) q.path("sourceReferences").get(0)).put("endLine", 3);
            case "hidden" -> ((ObjectNode) q.path("sourceReferences").get(0)).put("rubric", "secret");
            case "id" -> q.put("id", UUID.randomUUID().toString());
            case "type" -> q.put("prompt", 17);
            case "nul" -> q.put("rubric", "bad\0value");
            case "surrogate" -> q.put("rubric", "bad\ud800value");
            default -> { }
        }
        String json = body.toString();
        if (mutation.equals("trailing")) json += " {}";
        if (mutation.equals("keys")) json = json.replace("\"questions\":", "\"memberId\":\"" + member + "\",\"questions\":");
        if (mutation.equals("fence")) json = "```json\n" + json + "\n```";
        reply.set(json); var prepared = prepared();
        var error = assertThrows(AssessmentException.class, () -> service.generate(prepared));
        assertEquals("INVALID_RESPONSE", error.getMessage()); assertNull(error.getCause());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "unknownMember", "unknownQuestion", "fraction", "negative", "over", "string", "feedback"})
    void rejectsMalformedGradesAsTechnicalFailure(String mutation) {
        var complete = answers(); var body = questionnaireEvaluation();
        var m = (ObjectNode) body.path("members").get(0); var qs = (com.fasterxml.jackson.databind.node.ArrayNode) m.get("questions");
        var q = (ObjectNode) qs.get(0);
        switch (mutation) {
            case "missing" -> qs.remove(4);
            case "duplicate" -> qs.set(4, q.deepCopy());
            case "unknownMember" -> m.put("memberId", UUID.randomUUID().toString());
            case "unknownQuestion" -> q.put("id", UUID.randomUUID().toString());
            case "fraction" -> q.put("mark", 1.5);
            case "negative" -> q.put("mark", -1);
            case "over" -> q.put("mark", 21);
            case "string" -> q.put("mark", "10");
            case "feedback" -> q.put("feedback", "x".repeat(2001));
        }
        reply.set(body.toString()); var prepared = service.prepareQuestionnaireEvaluation("Build a safe Engine", evidence(), complete);
        assertThrows(AssessmentException.class, () -> service.evaluateQuestionnaire(prepared));
    }
    @ParameterizedTest @ValueSource(strings={"missingCategory","nan","infinite","over","negative","string","notes","privateResult","total"})
    void rejectsMalformedRepositoryScoresAndAnyPrivateResults(String mutation) {
        var body=repositoryEvaluation(); var repository=(ObjectNode)body.get("repository"); var quality=(ObjectNode)repository.get("quality");
        switch(mutation) {
            case "missingCategory" -> repository.remove("scope");
            case "nan" -> quality.put("score",Double.NaN);
            case "infinite" -> quality.put("score",Double.POSITIVE_INFINITY);
            case "over" -> quality.put("score",100.1);
            case "negative" -> quality.put("score",-0.1);
            case "string" -> quality.put("score","84");
            case "notes" -> quality.put("notes","bad\0notes");
            case "privateResult" -> body.set("members",questionnaireEvaluation().get("members"));
            case "total" -> body.put("total",100);
        }
        reply.set(body.toString()); var prepared=service.prepareRepositoryEvaluation("Build Engine",evidence());
        assertThrows(AssessmentException.class, () -> service.evaluateRepository(prepared));
    }
    @Test void invalidOversizeOrInsufficientEvidenceNeverReservesOrCalls() {
        assertThrows(AssessmentException.class, () -> service.prepareGeneration("x".repeat(64000), evidence(), member, ids, List.of()));
        assertThrows(AssessmentException.class, () -> service.prepareGeneration("bad\0brief", evidence(), member, ids, List.of()));
        var e = evidence(); var empty = new GitHubEvidence(e.resolution(), e.capturedAt(), e.paths(), List.of(), e.contributors(), e.omissions(), e.bounds());
        assertThrows(AssessmentException.class, () -> service.prepareGeneration("Engine", empty, member, ids, List.of()));
        ReflectionTestUtils.setField(transport, "n8nWebhookUrl", "");
        assertThrows(AssessmentException.class, this::prepared);
        verifyNoInteractions(quota); assertEquals(0, calls.get());
    }
    @Test void rejectsDuplicatesAgainstPreviouslyGeneratedTeammatePrompts() {
        var prepared = service.prepareGeneration("Build Engine", evidence(), member, ids, List.of(prompts.get(0)));
        assertThrows(AssessmentException.class, () -> service.generate(prepared));
    }
    @Test void quotaDenialMakesNoRelayCallAndFailureIsNotRefunded() {
        doThrow(new AssessmentQuotaService.QuotaExceeded(Instant.parse("2026-11-21T19:00:00Z"))).when(quota).reserve();
        var prepared = prepared();
        var error = assertThrows(AssessmentQuotaService.QuotaExceeded.class, () -> service.generate(prepared));
        assertEquals(Instant.parse("2026-11-21T19:00:00Z"), error.resetAt()); assertEquals(0, calls.get());
    }
    @Test void validatesAllMembersAndQuestionIdsAcrossACompleteTeam() {
        var first=answers().get(0); UUID secondId=UUID.randomUUID();
        var second=new MemberAnswers(secondId,first.questions().stream().map(q ->
                new Answer(UUID.randomUUID(),q.prompt(),q.rubric(),q.sourceReferences(),q.answer())).toList());
        var prepared=service.prepareQuestionnaireEvaluation("Build a safe Engine",evidence(),List.of(first,second));
        var body=questionnaireEvaluation(); var memberJson=((com.fasterxml.jackson.databind.node.ArrayNode)body.get("members")).addObject();
        memberJson.put("memberId",secondId.toString()); var qs=memberJson.putArray("questions");
        for (Answer q:second.questions()) qs.addObject().put("id",q.id().toString()).put("mark",20).put("feedback","Strong evidence-backed explanation.");
        reply.set(body.toString()); assertEquals(2,service.evaluateQuestionnaire(prepared).members().size());
        memberJson.put("memberId",member.toString()); reply.set(body.toString());
        assertThrows(AssessmentException.class, () -> service.evaluateQuestionnaire(prepared));
        memberJson.put("memberId",secondId.toString()); ((ObjectNode)qs.get(0)).put("id",ids.get(0).toString()); reply.set(body.toString());
        assertThrows(AssessmentException.class, () -> service.evaluateQuestionnaire(prepared));
    }
    @Test void incompleteInputsAndOversizedCompleteTeamsNeverConsumeAllowance() {
        var first=answers().get(0); clearInvocations(quota); int priorCalls=calls.get();
        assertThrows(AssessmentException.class, () -> service.prepareQuestionnaireEvaluation("Engine",evidence(),List.of(new MemberAnswers(member,first.questions().subList(0,4)))));
        List<MemberAnswers> many=new ArrayList<>();
        for (int i=0; i<10; i++) many.add(new MemberAnswers(UUID.randomUUID(),first.questions().stream().map(q ->
                new Answer(UUID.randomUUID(),q.prompt(),q.rubric(),q.sourceReferences(),"x".repeat(2000))).toList()));
        var error=assertThrows(AssessmentException.class, () -> service.prepareQuestionnaireEvaluation("Engine",evidence(),many));
        assertEquals("INPUT_TOO_LARGE",error.getMessage()); verifyNoInteractions(quota); assertEquals(priorCalls,calls.get());
    }
    @Test void preservesValidUnicodeAndAcceptsRepositoryOnlyEvaluationForNoCompleteMembers() {
        var body=generation(); ((ObjectNode)body.path("questions").get(0)).put("rubric","درست جواب 🧪 e\u0301");
        reply.set(body.toString()); assertEquals("درست جواب 🧪 e\u0301",service.generate(prepared()).questions().get(0).rubric());
        reply.set(repositoryEvaluation().toString());
        assertEquals(84.5,service.evaluateRepository(service.prepareRepositoryEvaluation("Engine",evidence())).repository().get("quality").score());
        assertThrows(AssessmentException.class, () -> service.prepareQuestionnaireEvaluation("Engine",evidence(),List.of()));
    }
    @Test void repositoryOnlyPayloadCannotReceivePrivateQuestionsRubricsAnswersOrMemberIds() {
        var complete=answers(); clearInvocations(quota);
        var repository=service.prepareRepositoryEvaluation("Build Engine",evidence());
        var questionnaire=service.prepareQuestionnaireEvaluation("Build Engine",evidence(),complete);
        reply.set(repositoryEvaluation().toString());
        var shared=service.evaluateRepository(repository); assertEquals(5,shared.repository().size());
        String payload=request.get().path("message").textValue();
        assertFalse(payload.contains(member.toString())); assertFalse(payload.contains("private-login"));
        for (Answer q:complete.get(0).questions()) {
            assertFalse(payload.contains(q.id().toString())); assertFalse(payload.contains(q.prompt()));
            assertFalse(payload.contains(q.rubric())); assertFalse(payload.contains(q.answer()));
        }
        reply.set(questionnaireEvaluation().toString());
        assertEquals(1,service.evaluateQuestionnaire(questionnaire).members().size()); verify(quota,times(2)).reserve();
        payload=request.get().path("message").textValue(); assertTrue(payload.contains(complete.get(0).questions().get(0).answer()));
        assertThrows(AssessmentException.class, () -> service.evaluateRepository(repository));
        reply.set(repositoryEvaluation().toString()); assertThrows(AssessmentException.class, () -> service.evaluateQuestionnaire(questionnaire));
    }
}
