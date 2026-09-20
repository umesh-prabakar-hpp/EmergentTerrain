package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spring-free, bounded reader for the fixed paper-artifact layout.
 *
 * <p>The reader only returns summary fields. It never returns a path or raw artifact content, and
 * it rejects paper identifiers and manifest entries that could escape the configured root.</p>
 */
public final class ResearchPaperArtifactProvenanceReader {
    private static final Pattern PAPER_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    private static final long MAX_HASHED_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_MANIFEST_FILES = 32;

    private final ObjectMapper mapper;

    public ResearchPaperArtifactProvenanceReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ResearchPaperProvenanceSummary read(Path outputRoot, UUID roomId, String paperId) {
        if (outputRoot == null || roomId == null || paperId == null || !PAPER_ID.matcher(paperId).matches()) {
            return ResearchPaperProvenanceSummary.missing(paperId);
        }
        Path root = realDirectory(outputRoot);
        if (root == null) return ResearchPaperProvenanceSummary.missing(paperId);
        Path artifact = locate(root, roomId.toString().substring(0, 8), paperId);
        if (artifact == null) return ResearchPaperProvenanceSummary.missing(paperId);
        return summarize(root, artifact, paperId);
    }

    private Path locate(Path root, String roomPrefix, String paperId) {
        Path direct = artifactPath(root.resolve(roomPrefix), root, paperId);
        if (direct != null) return direct;
        Path archive = root.resolve("archive");
        if (!Files.isDirectory(archive)) return null;
        try (var entries = Files.list(archive)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(roomPrefix + "-"))
                    .map(path -> artifactPath(path, root, paperId))
                    .filter(path -> path != null)
                    .findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path artifactPath(Path roomDirectory, Path root, String paperId) {
        Path candidate = roomDirectory.resolve("artifacts").resolve(paperId).normalize();
        if (!candidate.startsWith(root) || Files.isSymbolicLink(roomDirectory)
                || Files.isSymbolicLink(candidate) || Files.isSymbolicLink(candidate.resolve("manifest.json"))) {
            return null;
        }
        try {
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(root) && Files.isRegularFile(realCandidate.resolve("manifest.json"))
                    ? realCandidate : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private ResearchPaperProvenanceSummary summarize(Path root, Path artifact, String paperId) {
        try {
            JsonNode manifest = readJson(artifact.resolve("manifest.json"));
            if (manifest == null || !manifest.isObject() || !paperId.equals(text(manifest, "paper_id"))) {
                return ResearchPaperProvenanceSummary.missing(paperId);
            }
            return new ResearchPaperProvenanceSummary(paperId, integrity(root, artifact, manifest),
                    study(artifact), evidence(artifact));
        } catch (IOException ignored) {
            return ResearchPaperProvenanceSummary.missing(paperId);
        }
    }

    private ResearchArtifactIntegritySummary integrity(Path root, Path artifact, JsonNode manifest) {
        JsonNode files = manifest.path("files");
        int declared = files.isObject() ? files.size() : 0;
        Integer schemaVersion = integer(manifest, "schema_version");
        String status = schemaVersion != null && schemaVersion == 2
                ? integrityStatus(root, artifact, files) : "UNSUPPORTED_SCHEMA";
        return new ResearchArtifactIntegritySummary(true, status, schemaVersion,
                instant(manifest, "created_at"), text(manifest, "publication_label"),
                text(manifest, "paper_status"), text(manifest, "source_revision"), declared);
    }

    private String integrityStatus(Path root, Path artifact, JsonNode files) {
        if (!files.isObject() || files.size() == 0 || files.size() > MAX_MANIFEST_FILES) return "NOT_VERIFIED";
        Iterator<Map.Entry<String, JsonNode>> entries = files.fields();
        try {
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                Path target = artifact.resolve(entry.getKey()).normalize();
                if (!target.startsWith(root) || !target.startsWith(artifact) || !Files.isRegularFile(target)
                        || Files.isSymbolicLink(target) || Files.size(target) > MAX_HASHED_FILE_BYTES
                        || !entry.getValue().isTextual() || !target.toRealPath().startsWith(artifact)) {
                    return "NOT_VERIFIED";
                }
                if (!sha256(Files.readAllBytes(target)).equalsIgnoreCase(entry.getValue().asText())) return "MISMATCHED";
            }
            return "VERIFIED";
        } catch (IOException ignored) {
            return "NOT_VERIFIED";
        }
    }

    private ResearchStudyProvenanceSummary study(Path artifact) throws IOException {
        JsonNode registration = readJson(artifact.resolve("evidence/pre-registration.json"));
        if (registration == null || !registration.isObject()) {
            return new ResearchStudyProvenanceSummary(false, false, false, false, false, false);
        }
        return new ResearchStudyProvenanceSummary(true, present(registration, "hypothesis"),
                present(registration, "prediction"), present(registration, "falsificationCondition"),
                registration.hasNonNull("computationalStudyPlan"), registration.hasNonNull("protocolReuseProvenance"));
    }

    private ResearchEvidenceProvenanceSummary evidence(Path artifact) throws IOException {
        JsonNode trace = readJson(artifact.resolve("evidence/tool-trace.json"));
        int toolCalls = trace != null && trace.isArray() ? trace.size() : 0;
        JsonNode audit = readJson(artifact.resolve("evidence/literature-audit.json"));
        JsonNode gate = audit == null ? null : audit.path("gate");
        ResearchLiteratureEvidenceSummary literature = new ResearchLiteratureEvidenceSummary(
                gate != null && gate.isObject(), size(gate, "relevanceSafeIssuedKeys"),
                size(gate, "successfullyReadKeys"), size(gate, "relatedWorkCitedKeys"),
                size(gate, "citedReadKeys"), gate != null && gate.path("sourceGroundedRelatedWork").asBoolean(false));
        return new ResearchEvidenceProvenanceSummary(Files.isRegularFile(artifact.resolve("results/findings.md")),
                Files.isRegularFile(artifact.resolve("results/conclusion.md")), toolCalls, literature);
    }

    private JsonNode readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > MAX_JSON_BYTES) return null;
        return mapper.readTree(Files.readString(path));
    }

    private static Path realDirectory(Path path) {
        try {
            return Files.isDirectory(path) && !Files.isSymbolicLink(path) ? path.toRealPath() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static int size(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.path(name);
        return value != null && value.isArray() ? value.size() : 0;
    }

    private static boolean present(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node.path(field).canConvertToInt() ? node.path(field).asInt() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return node.path(field).isTextual() ? Instant.parse(node.path(field).asText()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
