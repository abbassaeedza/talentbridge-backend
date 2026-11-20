package com.talentbridge.service;

import java.time.Instant;
import java.util.List;

/** Untrusted repository data, never instructions or a complete repository review. */
public record GitHubEvidence(Resolution resolution, Instant capturedAt, List<PathMetadata> paths,
                             List<FileEvidence> files, ContributorSnapshot contributors,
                             List<Omission> omissions, Bounds bounds) {
    public GitHubEvidence {
        paths = List.copyOf(paths);
        files = List.copyOf(files);
        omissions = List.copyOf(omissions);
    }

    public record Repository(String owner, String name, String url) { }
    public record Resolution(Repository repository, String requestedBranch, String resolvedBranch,
                             String commitSha, String treeSha) { }
    public record PathMetadata(String path, String blobSha, long bytes) { }
    public enum Kind { README, CONFIG, SOURCE }
    public record FileEvidence(Kind kind, String path, String blobSha, String fullFileSha256,
                               int fullFileBytes, int fullFileLines, int startLine, int endLine,
                               String excerpt, boolean truncated) { }
    public record SourceReference(String path, int startLine, int endLine) { }
    public record Contributor(String login, long contributions) { }
    public record ContributorSnapshot(Instant capturedAt, boolean commitPinned, boolean truncated,
                                      List<Contributor> records) {
        public ContributorSnapshot { records = List.copyOf(records); }
    }
    public enum OmissionReason {
        UNSAFE_PATH, EXCLUDED_PATH, UNSAFE_ENTRY, UNSUPPORTED_FILE, FILE_TOO_LARGE,
        SOURCE_FILE_LIMIT, CONFIG_FILE_LIMIT, CHARACTER_LIMIT, DUPLICATE_BLOB,
        MISSING_README, EMPTY_FILE, EXCERPT_TRUNCATED, PATH_METADATA_LIMIT
    }
    public record Omission(OmissionReason reason, int count) { }
    public record Bounds(int sourceFileLimit, int sourceCharacterLimit, int sourceCharacters,
                          int readmeCharacterLimit, int configFileLimit, int configCharactersPerFile,
                          int pathLimit, int pathCharacterLimit, int fileByteLimit,
                          int responseByteLimit, int treeResponseByteLimit, int contributorLimit) { }
    public enum Failure {
        INVALID_REPOSITORY, INVALID_BRANCH, INVALID_INPUT, INVALID_REFERENCE, INVALID_RESOLUTION,
        UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, HTTP_ERROR, TRANSPORT_ERROR,
        INTERRUPTED, DEADLINE_EXCEEDED, RESPONSE_TOO_LARGE, MALFORMED_RESPONSE,
        TRUNCATED_TREE, INSUFFICIENT_CODE, CONTRIBUTORS_UNAVAILABLE
    }
    public static final class EvidenceException extends RuntimeException {
        private final Failure failure;
        private final int httpStatus;

        public EvidenceException(Failure failure, int httpStatus) {
            super("GitHub evidence: " + failure);
            this.failure = failure;
            this.httpStatus = httpStatus;
        }

        public Failure failure() { return failure; }
        public int httpStatus() { return httpStatus; }
    }
}
