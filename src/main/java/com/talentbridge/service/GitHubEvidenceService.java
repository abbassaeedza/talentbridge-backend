package com.talentbridge.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.talentbridge.service.GitHubEvidence.*;

@Service
@RequiredArgsConstructor
public class GitHubEvidenceService {
    private static final int RESPONSE_BYTES = 262144;
    private static final int TREE_RESPONSE_BYTES = 2097152;
    private static final int FILE_BYTES = 65536;
    private static final int SOURCE_FILES = 16;
    private static final int SOURCE_CHARACTERS = 32000;
    private static final int SOURCE_FILE_CHARACTERS = 4000;
    private static final int README_CHARACTERS = 4000;
    private static final int CONFIG_FILES = 8;
    private static final int CONFIG_CHARACTERS = 1000;
    private static final int PATHS = 128;
    private static final int PATH_CHARACTERS = 8192;
    private static final int CONTRIBUTORS = 100;
    private static final Set<String> CONFIG_NAMES = Set.of("package.json", "pom.xml", "build.gradle", "requirements.txt", "dockerfile");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("java", "kt", "kts", "js", "jsx", "ts", "tsx", "py", "go",
            "rs", "c", "cc", "cpp", "h", "hpp", "cs", "rb", "php", "swift", "dart", "scala", "vue", "svelte", "sql");
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of("node_modules", "vendor", "target", "dist", "build", "out",
            "coverage", "generated", "__pycache__", "venv", "env", "config", "secrets", "credentials", "test", "tests", "__tests__",
            "third_party", "third-party", "bower_components", "pods", "obj", "bin", "deps", "site-packages");
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Resolution resolveRepository(String url, String branch, String token) {
        return resolveRepository(url, branch, token, deadline());
    }

    public GitHubEvidence capture(Resolution frozen, String brief, String token) {
        return capture(frozen, brief, token, deadline());
    }

    public GitHubEvidence capture(String url, String branch, String brief, String token) {
        require(brief != null && !brief.isBlank() && brief.length() <= 96000 && validUnicode(brief), Failure.INVALID_INPUT);
        long deadline = deadline();
        return capture(resolveRepository(url, branch, token, deadline), brief, token, deadline);
    }

    private GitHubEvidence capture(Resolution resolution, String brief, String token, long deadline) {
        require(brief != null && !brief.isBlank() && brief.length() <= 96000 && validUnicode(brief), Failure.INVALID_INPUT);
        require(resolution != null && resolution.repository() != null, Failure.INVALID_RESOLUTION);
        try {
            require(repository(resolution.repository().url()).equals(resolution.repository()), Failure.INVALID_RESOLUTION);
        } catch (EvidenceException e) {
            throw error(Failure.INVALID_RESOLUTION);
        }
        require(resolution.commitSha() != null && resolution.commitSha().matches("[0-9a-f]{40}")
                && resolution.treeSha() != null && resolution.treeSha().matches("[0-9a-f]{40}")
                && resolution.resolvedBranch() != null && validBranch(resolution.resolvedBranch())
                && (resolution.requestedBranch() == null || resolution.requestedBranch().isEmpty()
                    || resolution.requestedBranch().equals(resolution.resolvedBranch())), Failure.INVALID_RESOLUTION);
        Instant capturedAt = Instant.now();
        String base = base(resolution.repository());
        JsonNode tree = get(base + "/git/trees/" + resolution.treeSha() + "?recursive=1", token, TREE_RESPONSE_BYTES, false, deadline).json();
        require(tree.isObject() && resolution.treeSha().equals(text(tree, "sha")) && tree.path("truncated").isBoolean()
                && tree.path("tree").isArray(), Failure.MALFORMED_RESPONSE);
        require(!tree.get("truncated").booleanValue(), Failure.TRUNCATED_TREE);
        require(tree.get("tree").size() <= 5000, Failure.RESPONSE_TOO_LARGE);

        Map<OmissionReason, Integer> omissions = new EnumMap<>(OmissionReason.class);
        List<PathMetadata> eligible = new ArrayList<>();
        Set<String> uniquePaths = new HashSet<>();
        for (JsonNode item : tree.get("tree")) {
            String path = text(item, "path");
            String mode = text(item, "mode");
            String type = text(item, "type");
            String sha = sha(item, "sha");
            require(uniquePaths.add(path), Failure.MALFORMED_RESPONSE);
            if (!safePath(path)) { omit(omissions, OmissionReason.UNSAFE_PATH); continue; }
            if ("tree".equals(type) && "040000".equals(mode)) continue;
            if (!"blob".equals(type) || !("100644".equals(mode) || "100755".equals(mode))) {
                omit(omissions, OmissionReason.UNSAFE_ENTRY);
                continue;
            }
            long bytes = nonnegative(item, "size");
            if (excluded(path)) { omit(omissions, OmissionReason.EXCLUDED_PATH); continue; }
            eligible.add(new PathMetadata(path, sha, bytes));
        }
        eligible.sort(Comparator.comparing(PathMetadata::path));
        List<PathMetadata> paths = new ArrayList<>();
        int pathCharacters = 0;
        for (PathMetadata path : eligible) {
            if (paths.size() >= PATHS || pathCharacters + path.path().length() > PATH_CHARACTERS) {
                omit(omissions, OmissionReason.PATH_METADATA_LIMIT);
            } else {
                paths.add(path);
                pathCharacters += path.path().length();
            }
        }

        Set<String> keywords = Arrays.stream(brief.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 3).limit(128).collect(Collectors.toSet());
        Comparator<PathMetadata> relevance = Comparator.<PathMetadata>comparingInt(p -> relevance(p.path(), keywords))
                .reversed().thenComparing(PathMetadata::path);
        List<PathMetadata> selected = eligible.stream().filter(p -> kind(p.path()) != null)
                .sorted(Comparator.<PathMetadata, Kind>comparing(p -> kind(p.path())).thenComparing(relevance)).toList();
        eligible.stream().filter(p -> kind(p.path()) == null).forEach(p -> omit(omissions, OmissionReason.UNSUPPORTED_FILE));
        if (selected.stream().noneMatch(p -> kind(p.path()) == Kind.README)) omit(omissions, OmissionReason.MISSING_README);
        List<FileEvidence> files = new ArrayList<>();
        Map<Kind, Set<String>> blobs = new EnumMap<>(Kind.class);
        int sourceFiles = 0, sourceFetches = 0, sourceCharacters = 0, configFetches = 0, readmeFetches = 0;
        for (PathMetadata path : selected) {
            Kind kind = kind(path.path());
            if (kind == Kind.README && readmeFetches > 0) { omit(omissions, OmissionReason.UNSUPPORTED_FILE); continue; }
            if (kind == Kind.SOURCE && sourceFetches >= SOURCE_FILES) { omit(omissions, OmissionReason.SOURCE_FILE_LIMIT); continue; }
            if (kind == Kind.SOURCE && sourceCharacters >= SOURCE_CHARACTERS) { omit(omissions, OmissionReason.CHARACTER_LIMIT); continue; }
            if (kind == Kind.CONFIG && configFetches >= CONFIG_FILES) { omit(omissions, OmissionReason.CONFIG_FILE_LIMIT); continue; }
            if (path.bytes() > FILE_BYTES) { omit(omissions, OmissionReason.FILE_TOO_LARGE); continue; }
            if (path.bytes() == 0) { omit(omissions, OmissionReason.EMPTY_FILE); continue; }
            if (!blobs.computeIfAbsent(kind, ignored -> new HashSet<>()).add(path.blobSha())) {
                omit(omissions, OmissionReason.DUPLICATE_BLOB);
                continue;
            }
            int limit = switch (kind) {
                case README -> README_CHARACTERS;
                case CONFIG -> CONFIG_CHARACTERS;
                case SOURCE -> Math.min(SOURCE_FILE_CHARACTERS, SOURCE_CHARACTERS - sourceCharacters);
            };
            switch (kind) {
                case SOURCE -> sourceFetches++;
                case CONFIG -> configFetches++;
                case README -> readmeFetches++;
            }
            FileEvidence file = fetchFile(base, resolution.commitSha(), path, kind, limit, token, deadline);
            if (file.excerpt().isBlank()) {
                omit(omissions, OmissionReason.CHARACTER_LIMIT);
                continue;
            }
            files.add(file);
            if (file.truncated()) omit(omissions, OmissionReason.EXCERPT_TRUNCATED);
            if (kind == Kind.SOURCE) { sourceFiles++; sourceCharacters += file.excerpt().length(); }
        }
        require(sourceFiles > 0, Failure.INSUFFICIENT_CODE);
        ContributorSnapshot contributors = contributors(base, token, deadline);
        return new GitHubEvidence(resolution, capturedAt, paths, files, contributors,
                omissions.entrySet().stream().map(e -> new Omission(e.getKey(), e.getValue())).toList(),
                new Bounds(SOURCE_FILES, SOURCE_CHARACTERS, sourceCharacters, README_CHARACTERS, CONFIG_FILES,
                        CONFIG_CHARACTERS, PATHS, PATH_CHARACTERS, FILE_BYTES, RESPONSE_BYTES, TREE_RESPONSE_BYTES, CONTRIBUTORS));
    }

    public void validateSourceReferences(GitHubEvidence evidence, List<SourceReference> references) {
        require(evidence != null && references != null && !references.isEmpty() && references.size() <= SOURCE_FILES, Failure.INVALID_REFERENCE);
        for (SourceReference ref : references) {
            require(ref != null && safePath(ref.path()) && ref.startLine() >= 1 && ref.endLine() >= ref.startLine(), Failure.INVALID_REFERENCE);
            require(evidence.files().stream().anyMatch(f -> f.kind() == Kind.SOURCE && f.path().equals(ref.path())
                    && ref.startLine() >= f.startLine() && ref.endLine() <= f.endLine()
                    && f.endLine() <= f.fullFileLines() && lines(f.excerpt()) == f.endLine() - f.startLine() + 1), Failure.INVALID_REFERENCE);
        }
    }

    private Resolution resolveRepository(String url, String requested, String token, long deadline) {
        Repository repository = repository(url);
        String branch = requested == null || requested.isEmpty() ? null : requested;
        require(branch == null || validBranch(branch), Failure.INVALID_BRANCH);
        if (branch == null) {
            JsonNode metadata = get(base(repository), token, RESPONSE_BYTES, false, deadline).json();
            branch = text(metadata, "default_branch");
            require(validBranch(branch), Failure.MALFORMED_RESPONSE);
        }
        JsonNode result = get(base(repository) + "/branches/" + encode(branch), token, RESPONSE_BYTES, false, deadline).json();
        require(branch.equals(text(result, "name")), Failure.MALFORMED_RESPONSE);
        return new Resolution(repository, requested, branch, sha(result.path("commit"), "sha"),
                sha(result.path("commit").path("commit").path("tree"), "sha"));
    }

    private Repository repository(String url) {
        require(url != null && url.length() <= 256, Failure.INVALID_REPOSITORY);
        try {
            URI uri = URI.create(url);
            require("https".equalsIgnoreCase(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getRawAuthority())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null, Failure.INVALID_REPOSITORY);
            String[] parts = uri.getRawPath().split("/", -1);
            require(parts.length == 3 && parts[0].isEmpty(), Failure.INVALID_REPOSITORY);
            String owner = parts[1], name = parts[2];
            if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
            require(owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
                    && name.matches("[A-Za-z0-9._-]{1,100}") && !Set.of(".", "..").contains(name), Failure.INVALID_REPOSITORY);
            return new Repository(owner, name, "https://github.com/" + owner + "/" + name);
        } catch (IllegalArgumentException e) {
            throw error(Failure.INVALID_REPOSITORY);
        }
    }

    private FileEvidence fetchFile(String base, String commit, PathMetadata path, Kind kind, int limit, String token, long deadline) {
        String encodedPath = Arrays.stream(path.path().split("/", -1)).map(GitHubEvidenceService::encode).collect(Collectors.joining("/"));
        JsonNode node = get(base + "/contents/" + encodedPath + "?ref=" + commit, token, RESPONSE_BYTES, false, deadline).json();
        require("file".equals(text(node, "type")) && path.path().equals(text(node, "path"))
                && path.blobSha().equals(sha(node, "sha")) && nonnegative(node, "size") == path.bytes()
                && "base64".equals(text(node, "encoding")) && !node.hasNonNull("target") && !node.hasNonNull("submodule_git_url"), Failure.MALFORMED_RESPONSE);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text(node, "content").replace("\n", "").replace("\r", ""));
        } catch (IllegalArgumentException e) {
            throw error(Failure.MALFORMED_RESPONSE);
        }
        require(bytes.length == path.bytes() && bytes.length <= FILE_BYTES, Failure.MALFORMED_RESPONSE);
        MessageDigest blob = digest("SHA-1");
        blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        require(path.blobSha().equals(HexFormat.of().formatHex(blob.digest(bytes))), Failure.MALFORMED_RESPONSE);
        String text = utf8(bytes);
        require(validUnicode(text) && text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'), Failure.MALFORMED_RESPONSE);
        String excerpt = excerpt(text, limit);
        return new FileEvidence(kind, path.path(), path.blobSha(), HexFormat.of().formatHex(digest("SHA-256").digest(bytes)),
                bytes.length, lines(text), excerpt.isEmpty() ? 0 : 1, lines(excerpt), excerpt, excerpt.length() < text.length());
    }

    private ContributorSnapshot contributors(String base, String token, long deadline) {
        Response response = get(base + "/contributors?per_page=100&anon=false&page=1", token, RESPONSE_BYTES, true, deadline);
        List<Contributor> records = new ArrayList<>();
        if (response.status() != 204) {
            require(response.json().isArray() && response.json().size() <= CONTRIBUTORS, Failure.MALFORMED_RESPONSE);
            Set<String> logins = new HashSet<>();
            for (JsonNode node : response.json()) {
                String login = text(node, "login");
                require(login.matches("[A-Za-z0-9][A-Za-z0-9\\-\\[\\]]{0,99}")
                        && logins.add(login.toLowerCase(java.util.Locale.ROOT)), Failure.MALFORMED_RESPONSE);
                records.add(new Contributor(login, nonnegative(node, "contributions")));
            }
        }
        return new ContributorSnapshot(Instant.now(), false, records.size() == CONTRIBUTORS || response.nextPage(), records);
    }

    private Response get(String url, String token, int limit, boolean contributors, long deadline) {
        long remaining = deadline - System.nanoTime();
        require(remaining > 0, Failure.DEADLINE_EXCEEDED);
        long requestBudget = Math.min(Duration.ofSeconds(15).toNanos(), remaining);
        long requestDeadline = System.nanoTime() + requestBudget;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET()
                .timeout(Duration.ofNanos(requestBudget))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TalentBridge-evidence/1.0");
        if (token != null && !token.isBlank()) {
            require(token.length() <= 4096 && token.chars().allMatch(c -> c >= 33 && c <= 126), Failure.INVALID_INPUT);
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(),
                    info -> new LimitedBody(limit, requestDeadline - System.nanoTime()));
            int status = response.statusCode();
            if (status != 200 && !(contributors && status == 204)) {
                Failure failure = switch (status) {
                    case 401 -> Failure.UNAUTHORIZED;
                    case 403 -> response.headers().firstValue("x-ratelimit-remaining").filter("0"::equals).isPresent()
                            || response.headers().firstValue("retry-after").isPresent() ? Failure.RATE_LIMITED : Failure.FORBIDDEN;
                    case 404 -> Failure.NOT_FOUND;
                    case 429 -> Failure.RATE_LIMITED;
                    case 202 -> contributors ? Failure.CONTRIBUTORS_UNAVAILABLE : Failure.HTTP_ERROR;
                    default -> Failure.HTTP_ERROR;
                };
                throw new EvidenceException(failure, status);
            }
            require(response.body().length <= limit, Failure.RESPONSE_TOO_LARGE);
            JsonNode json = status == 204 ? mapper.createArrayNode() : mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(utf8(response.body()));
            require(json != null && !json.isNull(), Failure.MALFORMED_RESPONSE);
            return new Response(status, json, response.headers().allValues("link").stream().anyMatch(v -> v.contains("rel=\"next\"")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(Failure.INTERRUPTED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw error(Failure.MALFORMED_RESPONSE);
        } catch (IOException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof EvidenceException evidence) throw evidence;
                if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException)
                    throw error(Failure.DEADLINE_EXCEEDED);
            }
            throw error(Failure.TRANSPORT_ERROR);
        }
    }

    private static String base(Repository repository) { return "https://api.github.com/repos/" + repository.owner() + "/" + repository.name(); }
    private static long deadline() { return System.nanoTime() + Duration.ofSeconds(90).toNanos(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static EvidenceException error(Failure failure) { return new EvidenceException(failure, 0); }
    private static void require(boolean condition, Failure failure) { if (!condition) throw error(failure); }
    private static void omit(Map<OmissionReason, Integer> omissions, OmissionReason reason) { omissions.merge(reason, 1, Integer::sum); }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), Failure.MALFORMED_RESPONSE);
        return node.get(field).textValue();
    }
    private static String sha(JsonNode node, String field) {
        String sha = text(node, field);
        require(sha.matches("[0-9a-f]{40}"), Failure.MALFORMED_RESPONSE);
        return sha;
    }
    private static long nonnegative(JsonNode node, String field) {
        require(node.path(field).isIntegralNumber() && node.get(field).canConvertToLong() && node.get(field).longValue() >= 0, Failure.MALFORMED_RESPONSE);
        return node.get(field).longValue();
    }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required JDK digest unavailable"); }
    }
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw error(Failure.MALFORMED_RESPONSE); }
    }
    private static boolean validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c) || c == 0) return false;
        }
        return true;
    }
    private static boolean validBranch(String branch) {
        if (branch.isEmpty() || branch.length() > 255 || !validUnicode(branch) || branch.startsWith("-")
                || branch.equals("@") || branch.contains("..") || branch.contains("@{") || branch.endsWith(".")
                || branch.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c) || "~^:?*[\\".indexOf(c) >= 0)) return false;
        return Arrays.stream(branch.split("/", -1)).noneMatch(p -> p.isEmpty() || p.startsWith(".") || p.endsWith(".lock"));
    }
    private static boolean safePath(String path) {
        return path != null && !path.isEmpty() && path.length() <= 512 && validUnicode(path) && !path.contains("\\")
                && path.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                && Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }
    private static boolean excluded(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(lower.split("/")).anyMatch(p -> p.startsWith(".") || EXCLUDED_SEGMENTS.contains(p)
                || p.contains("secret") || p.contains("credential") || p.contains("password") || p.contains("token")
                || p.replace("_", "").replace("-", "").contains("privatekey"))
                || lower.contains(".min.") || lower.contains(".generated.") || lower.contains(".test.") || lower.contains(".spec.")
                || lower.contains(".pb.") || lower.contains(".g.") || lower.contains(".designer.") || lower.contains("_generated.")
                || lower.endsWith(".lock") || lower.endsWith(".d.ts");
    }
    private static Kind kind(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (!lower.contains("/") && Set.of("readme", "readme.md", "readme.rst", "readme.txt").contains(name)) return Kind.README;
        if (CONFIG_NAMES.contains(name)) return Kind.CONFIG;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1)) ? Kind.SOURCE : null;
    }
    private static int relevance(String path, Set<String> keywords) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return (int) keywords.stream().filter(lower::contains).count();
    }
    private static String excerpt(String text, int limit) {
        int end = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            if (i + 1 > limit) break;
            if (c == '\r' || c == '\n' || i == text.length() - 1) end = i + 1;
        }
        return text.substring(0, end);
    }
    private static int lines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            if (c == '\r' || c == '\n' || i == text.length() - 1) lines++;
        }
        return lines;
    }

    private record Response(int status, JsonNode json, boolean nextPage) { }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private int received;
        private boolean stopped;

        private LimitedBody(int limit, long remainingNanos) {
            this.limit = limit;
            // JDK 17 stops the request timer at headers, so bound body consumption separately.
            delegate.getBody().toCompletableFuture().orTimeout(Math.max(1, remainingNanos), TimeUnit.NANOSECONDS)
                    .whenComplete((body, failure) -> {
                        if (failure instanceof TimeoutException) onError(failure);
                    });
        }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (stopped) subscription.cancel();
            else delegate.onSubscribe(subscription);
        }
        public synchronized void onNext(List<ByteBuffer> items) {
            if (stopped) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - received) {
                    stopped = true;
                    subscription.cancel();
                    delegate.onError(error(Failure.RESPONSE_TOO_LARGE));
                    return;
                }
                received += item.remaining();
            }
            delegate.onNext(items);
        }
        public synchronized void onError(Throwable error) {
            if (!stopped) {
                stopped = true;
                if (subscription != null) subscription.cancel();
                delegate.onError(error);
            }
        }
        public synchronized void onComplete() { if (!stopped) { stopped = true; delegate.onComplete(); } }
    }
}
