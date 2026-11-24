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
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(quota).reserve(any(Runnable.class));
        service = new AssessmentAIService(mapper, transport, quota, new GitHubEvidenceService(mapper));
        reply.set(generation().toString());
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void durableAttemptCallbackRunsInsideQuotaAuthorizationBeforeTheSingleProviderRequest() {
        var recorded=new java.util.concurrent.atomic.AtomicBoolean();
        var result=service.generate(prepared(),()->{
            assertEquals(0,calls.get()); recorded.set(true);
        });
        assertTrue(recorded.get()); assertEquals(member,result.memberId()); assertEquals(1,calls.get());
        doThrow(new AssessmentQuotaService.QuotaExceeded(Instant.parse("2026-11-21T19:00:00Z"))).when(quota).reserve(any(Runnable.class));
        assertThrows(AssessmentQuotaService.QuotaExceeded.class,()->service.generate(prepared(),()->fail("No actual attempt may be recorded for quota deferral")));
        assertEquals(1,calls.get());
    }

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
        verify(quota).reserve(any(Runnable.class)); assertEquals(1, calls.get());
    }
    @Test void gradesOnlyCompleteExpectedIdsWithoutComputingTeamTotals() {
        var complete = answers(); reply.set(questionnaireEvaluation().toString());
        var result = service.evaluateQuestionnaire(service.prepareQuestionnaireEvaluation("Build a safe Engine", evidence(), complete));
        assertEquals(16, result.members().get(0).questions().get(0).mark());
        assertEquals(8192, request.get().path("maxTokens").intValue());
        assertEquals("questionnaire_evaluation", request.get().path("operation").textValue());
    }
    @Test void attachesOnlyTheFrozenSourceAssignedToEachQuestionEvenWhenTheModelReordersThem() throws Exception {
        var e=evidence();
        var mixed=new GitHubEvidence(e.resolution(),e.capturedAt(),e.paths(),List.of(
                new FileEvidence(Kind.README,"README.md","c".repeat(40),"d".repeat(64),12,1,1,1,"Instructions",false),
                e.files().get(0),
                new FileEvidence(Kind.SOURCE,"src/Store.java","e".repeat(40),"f".repeat(64),48,28,17,18,"void save() {\n}\n",true)),
                e.contributors(),e.omissions(),e.bounds());
        var prepared=service.prepareGeneration("Build a safe Engine",mixed,member,ids,List.of());
        var generated=generation();
        var questions=(com.fasterxml.jackson.databind.node.ArrayNode)generated.get("questions");
        ((ObjectNode)questions.get(1)).put("prompt","What boundary does src/Store.java enforce when it saves a revision?");
        questions.insert(0,questions.remove(1));
        reply.set(generated.toString());
        var results=assertDoesNotThrow(()->service.generate(prepared)).questions();
        assertEquals(ids.get(1),results.get(0).id());
        assertEquals(List.of(new SourceReference("src/Store.java",17,18)),results.get(0).sourceReferences());
        assertEquals(List.of(new SourceReference("src/Engine.java",1,2)),results.get(1).sourceReferences());
        var payload=mapper.readTree(request.get().path("message").textValue());
        var files=payload.path("files");
        assertFalse(files.get(0).has("sourceReferenceId"));
        assertEquals(2,files.get(1).path("sourceReferenceId").intValue());
        assertEquals(3,files.get(2).path("sourceReferenceId").intValue());
        assertEquals(2,payload.path("questionSources").path(ids.get(0).toString()).intValue());
        assertEquals(3,payload.path("questionSources").path(ids.get(1).toString()).intValue());
        ((ObjectNode)questions.get(0)).put("prompt","What validation does Engine.java apply before saving a new revision?");
        reply.set(generated.toString());
        assertAll(()->assertFalse(payload.has("questionIds")),
                ()->assertEquals("GENERATION_SOURCE_CONFLICT",assertThrows(AssessmentException.class,()->service.generate(prepared)).replyCheck().name()));
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "extra", "duplicate", "near", "referenceOverride", "id", "type", "nul", "surrogate", "trailing", "keys", "fence"})
    void rejectsMalformedGenerationInsteadOfPublishing(String mutation) {
        var body = generation(); var qs = (com.fasterxml.jackson.databind.node.ArrayNode) body.get("questions");
        var q = (ObjectNode) qs.get(0);
        switch (mutation) {
            case "missing" -> qs.remove(4);
            case "extra" -> qs.add(q.deepCopy());
            case "duplicate" -> ((ObjectNode) qs.get(1)).put("prompt", prompts.get(0));
            case "near" -> ((ObjectNode) qs.get(1)).put("prompt", prompts.get(0).toUpperCase() + "!");
            case "referenceOverride" -> q.putArray("sourceReferenceIds").add(3);
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
        assertEquals(switch (mutation) {
            case "missing","extra","referenceOverride" -> ReplyCheck.GENERATION_SHAPE;
            case "duplicate","near" -> ReplyCheck.GENERATION_DUPLICATE;
            case "id" -> ReplyCheck.GENERATION_IDENTITY;
            case "type","nul" -> ReplyCheck.GENERATION_TEXT;
            case "surrogate" -> ReplyCheck.RELAY_ENVELOPE;
            default -> ReplyCheck.MODEL_JSON;
        },error.replyCheck());
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
        doThrow(new AssessmentQuotaService.QuotaExceeded(Instant.parse("2026-11-21T19:00:00Z"))).when(quota).reserve(any(Runnable.class));
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
        assertEquals(1,service.evaluateQuestionnaire(questionnaire).members().size()); verify(quota,times(2)).reserve(any(Runnable.class));
        payload=request.get().path("message").textValue(); assertTrue(payload.contains(complete.get(0).questions().get(0).answer()));
        assertThrows(AssessmentException.class, () -> service.evaluateRepository(repository));
        reply.set(repositoryEvaluation().toString()); assertThrows(AssessmentException.class, () -> service.evaluateQuestionnaire(questionnaire));
    }

    GitHubEvidence maximumEvidence(boolean quotedPaths) {
        var files=new ArrayList<FileEvidence>();
        for(int i=0;i<8;i++) files.add(new FileEvidence(Kind.SOURCE,
                quotedPaths && i<4?"src/"+i+"\"".repeat(502)+".java":"src/Source"+i+".java",
                "c".repeat(40),"d".repeat(64),4000,1,1,1,"s".repeat(4000),false));
        files.add(new FileEvidence(Kind.README,"README.md","c".repeat(40),"d".repeat(64),4000,1,1,1,"r".repeat(4000),false));
        for(int i=0;i<8;i++) files.add(new FileEvidence(Kind.CONFIG,"module"+i+"/package.json","c".repeat(40),"d".repeat(64),1000,1,1,1,"c".repeat(1000),false));
        return new GitHubEvidence(evidence().resolution(),Instant.EPOCH,List.of(),files,
                new ContributorSnapshot(Instant.EPOCH,false,true,java.util.stream.IntStream.range(0,100)
                        .mapToObj(i->new Contributor("contributor"+i,1)).toList()),List.of(),
                new Bounds(16,32000,32000,4000,8,1000,128,8192,65536,262144,2097152,100));
    }

    List<MemberAnswers> maximumMembers(GitHubEvidence evidence,String text,int count) {
        var refs=evidence.files().stream().filter(f->f.kind()==Kind.SOURCE).limit(4)
                .map(f->new SourceReference(f.path(),1,1)).toList();
        return java.util.stream.IntStream.range(0,count).mapToObj(i->new MemberAnswers(new UUID(0,i+1),
                java.util.stream.IntStream.range(0,5).mapToObj(q->new Answer(new UUID(i+1,q+1),
                        String.valueOf((char)('A'+i*5+q)).repeat(1500),"R".repeat(2000),refs,text)).toList())).toList();
    }

    @Test void realJacksonMaximumThreeMemberPayloadOverflowsThenPartitionsLosslessly() {
        var evidence=maximumEvidence(false); var members=maximumMembers(evidence,"x".repeat(2000),3);
        for(int i=0;i<3;i++) service.prepareGeneration("Synthetic project",evidence,members.get(i).memberId(),
                members.get(i).questions().stream().map(Answer::id).toList(),
                members.subList(0,i).stream().flatMap(m->m.questions().stream()).map(Answer::prompt).toList());
        assertEquals("INPUT_TOO_LARGE",assertThrows(AssessmentException.class,
                ()->service.prepareQuestionnaireEvaluation("Synthetic project",evidence,members)).getMessage());
        var batches=service.prepareQuestionnaireBatches("Synthetic project",evidence,members);
        assertEquals(List.of(8,7),batches.stream().map(b->b.stream().mapToInt(m->m.questions().size()).sum()).toList());
        assertEquals(members.stream().flatMap(m->m.questions().stream()).toList(),
                batches.stream().flatMap(List::stream).flatMap(m->m.questions().stream()).toList());
        verifyNoInteractions(quota); assertEquals(0,calls.get());
        for(var batch:batches) {
            var body=mapper.createObjectNode(); var ms=body.putArray("members");
            for(var m:batch) {var qs=ms.addObject().put("memberId",m.memberId().toString()).putArray("questions");
                for(var q:m.questions()) qs.addObject().put("id",q.id().toString()).put("mark",20).put("feedback","Evidence-backed");}
            reply.set(body.toString());
            service.evaluateQuestionnaire(service.prepareQuestionnaireBatch("Synthetic project",evidence,batch),()->{});
            var payload=assertDoesNotThrow(()->mapper.readTree(request.get().path("message").textValue()));
            assertEquals(17,payload.path("files").size()); assertEquals(100,payload.path("contributorSnapshot").path("counts").size());
            assertEquals(mapper.valueToTree(batch),payload.path("completeMembers"));
            assertTrue(request.get().path("system").textValue().length()+request.get().path("message").textValue().length()<=96000);
        }
    }

    @Test void atomicTupleFitsPublishedBaseBudgetEvenWithWorstAllowedEscaping() {
        var input=capturePreparations();
        var evidence=maximumEvidence(true);
        String brief="b".repeat(6000);
        var single=maximumMembers(evidence,"\"".repeat(2000),1).get(0);
        var atomic=new MemberAnswers(single.memberId(),List.of(new Answer(single.questions().get(0).id(),
                "\t".repeat(1499)+"P","\\".repeat(2000),single.questions().get(0).sourceReferences(),"\"".repeat(2000))));
        service.prepareGeneration(brief,evidence,single.memberId(),single.questions().stream().map(Answer::id).toList(),List.of());
        int initial=input.get().get(0).length()+input.get().get(1).length();
        brief+="b".repeat(63999-initial);
        service.prepareGeneration(brief,evidence,single.memberId(),single.questions().stream().map(Answer::id).toList(),List.of());
        assertEquals(63999,input.get().get(0).length()+input.get().get(1).length());
        var base=(ObjectNode)assertDoesNotThrow(()->mapper.readTree(input.get().get(1)));
        base.remove(List.of("memberId","questionSources","priorPrompts"));
        base.path("files").forEach(file -> ((ObjectNode)file).remove("sourceReferenceId"));
        int baseLength=base.toString().length();assertTrue(baseLength<64000);
        String boundedBrief=brief;
        assertDoesNotThrow(()->service.prepareQuestionnaireBatch(boundedBrief,evidence,List.of(atomic)));
        int system=input.get().get(0).length();int serialized=input.get().get(1).length();
        assertTrue(system<=2048);assertTrue(assertDoesNotThrow(()->mapper.writeValueAsString(atomic.questions().get(0).sourceReferences())).length()<=4800);
        assertTrue(serialized+system<=baseLength+2048+2*5500+4800+512);
        assertTrue(serialized+system<96000);
        var privateBase=(ObjectNode)assertDoesNotThrow(()->mapper.readTree(input.get().get(1)));
        privateBase.remove(List.of("assessmentKind","completeMembers"));assertEquals(base,privateBase);
        verifyNoInteractions(quota); assertEquals(0,calls.get());
    }

    AtomicReference<List<String>> capturePreparations() {
        transport=spy(transport);var captured=new AtomicReference<List<String>>();
        doAnswer(c->{captured.set(List.of(c.getArgument(1),c.getArgument(2)));return c.callRealMethod();})
                .when(transport).prepareAssessment(anyString(),anyString(),anyString());
        service=new AssessmentAIService(mapper,transport,quota,new GitHubEvidenceService(mapper));return captured;
    }

    @ParameterizedTest @ValueSource(strings={"\"","\\","\t","\n","\r","اردو","e\u0301","🧪"})
    void allowedPlainTextAtExactlyTwoThousandUnitsSurvivesSerializationUnchanged(String unit) {
        String text=unit.repeat(2000/unit.length());if(text.isBlank()) text="X"+text.substring(1);
        var members=maximumMembers(evidence(),text,1);String expected=text;var input=capturePreparations();
        assertDoesNotThrow(()->service.prepareQuestionnaireEvaluation("Brief",evidence(),members));
        var payload=assertDoesNotThrow(()->mapper.readTree(input.get().get(1)));
        assertEquals(expected,payload.path("completeMembers").get(0).path("questions").get(0).path("answer").textValue());
        assertEquals(2000,expected.length());verifyNoInteractions(quota);assertEquals(0,calls.get());
    }

    @ParameterizedTest @ValueSource(strings={"missing","extra","duplicate","fraction","wrongMember"})
    void privateSubsetResponseMustMatchItsOwnExactIds(String mutation) {
        var member=maximumMembers(evidence(),"Saved answer",1).get(0);
        var subset=List.of(new MemberAnswers(member.memberId(),member.questions().subList(0,2)));
        var prepared=service.prepareQuestionnaireBatch("Brief",evidence(),subset);
        var body=mapper.createObjectNode();var m=body.putArray("members").addObject().put("memberId",member.memberId().toString());var qs=m.putArray("questions");
        for(var q:subset.get(0).questions()) qs.addObject().put("id",q.id().toString()).put("mark",20).put("feedback","Evidence-backed");
        switch(mutation) {
            case "missing"->qs.remove(1);
            case "extra"->qs.addObject().put("id",member.questions().get(2).id().toString()).put("mark",20).put("feedback","Evidence-backed");
            case "duplicate"->qs.set(1,qs.get(0).deepCopy());
            case "fraction"->((ObjectNode)qs.get(0)).put("mark",1.5);
            case "wrongMember"->m.put("memberId",UUID.randomUUID().toString());
        }
        reply.set(body.toString());assertEquals("INVALID_RESPONSE",assertThrows(AssessmentException.class,()->service.evaluateQuestionnaire(prepared)).getMessage());
    }

    @Test void wholeTeamFirstAndGlobalValidationCannotBeBypassedBySplitting() {
        var normal=maximumMembers(evidence(),"Urdu اردو 🧪 e\u0301\t\n\r \\u0001",2);
        assertEquals(List.of(normal),service.prepareQuestionnaireBatches("Brief",evidence(),normal));
        var bad=new ArrayList<>(normal); bad.set(1,new MemberAnswers(normal.get(1).memberId(),normal.get(0).questions()));
        assertEquals("INVALID_INPUT",assertThrows(AssessmentException.class,
                ()->service.prepareQuestionnaireBatches("Brief",evidence(),bad)).getMessage());
        ReflectionTestUtils.setField(transport,"n8nWebhookUrl","");
        assertEquals("INVALID_RELAY_CONFIGURATION",assertThrows(AssessmentException.class,
                ()->service.prepareQuestionnaireBatches("Brief",evidence(),normal)).getMessage());
        verifyNoInteractions(quota); assertEquals(0,calls.get());
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,11,12,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31})
    void unsupportedPlainTextControlsCannotReachGenerationOrPrivateTransport(int control) {
        var body=generation(); ((ObjectNode)body.path("questions").get(0)).put("rubric","Bad"+(char)control);
        reply.set(body.toString()); assertEquals("INVALID_RESPONSE",assertThrows(AssessmentException.class,
                ()->service.generate(prepared())).getMessage());
        int before=calls.get(); clearInvocations(quota);
        var members=maximumMembers(evidence(),"Bad"+(char)control,1);
        assertEquals("INVALID_INPUT",assertThrows(AssessmentException.class,
                ()->service.prepareQuestionnaireBatches("Brief",evidence(),members)).getMessage());
        verifyNoInteractions(quota); assertEquals(before,calls.get());
    }
}
