package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResearchPaper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/** Writes a self-contained audit bundle beside each computational paper. */
public final class ArtifactPackageWriter {
    private ArtifactPackageWriter() {}

    public static Path write(Path researchOutputDir, String roomPrefix, ResearchPaper paper,
                             PreRegistration preRegistration, List<ToolInvocation> trace,
                             PublicationAssessment assessment, String markdown,
                             ObjectMapper objectMapper) throws IOException {
        Path root = researchOutputDir.resolve(roomPrefix).resolve("artifacts").resolve(paper.paperId());
        Path evidence = root.resolve("evidence");
        Path results = root.resolve("results");
        Files.createDirectories(evidence);
        Files.createDirectories(results);

        Files.writeString(root.resolve("paper.md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("pre-registration.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(preRegistration),
                StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("tool-trace.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace == null ? List.of() : trace),
                StandardCharsets.UTF_8);
        Files.writeString(results.resolve("findings.md"), nullToEmpty(paper.findings()), StandardCharsets.UTF_8);
        Files.writeString(results.resolve("conclusion.md"), nullToEmpty(paper.conclusion()), StandardCharsets.UTF_8);

        String reproduce = reproductionScript(preRegistration);
        Path reproduceFile = root.resolve("reproduce.sh");
        Files.writeString(reproduceFile, reproduce, StandardCharsets.UTF_8);
        reproduceFile.toFile().setExecutable(true, false);
        Files.writeString(root.resolve("README.md"), readme(paper, assessment, preRegistration),
                StandardCharsets.UTF_8);

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schema_version", 1);
        manifest.put("created_at", Instant.now().toString());
        manifest.put("paper_id", paper.paperId());
        manifest.put("publication_label", paper.publicationLabel().name());
        manifest.put("paper_status", paper.status());
        manifest.put("source_revision", sourceRevision());
        manifest.set("missing_requirements", objectMapper.valueToTree(assessment.missingRequirements()));
        manifest.set("files", checksums(root, objectMapper));
        Files.writeString(root.resolve("manifest.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        return root;
    }

    private static String reproductionScript(PreRegistration preRegistration) {
        ComputationalStudyPlan plan = preRegistration == null ? null : preRegistration.computationalStudyPlan();
        String command = plan == null ? "" : nullToEmpty(plan.reproductionCommand()).trim();
        if (command.isBlank()) {
            return "#!/usr/bin/env bash\nset -euo pipefail\n"
                    + "echo 'No approved reproduction command was pre-registered for this artifact.' >&2\n"
                    + "exit 2\n";
        }
        // The command is recorded, not auto-executed by the server.  Artifact consumers
        // choose whether to run it in their own reviewed environment.
        return "#!/usr/bin/env bash\nset -euo pipefail\n"
                + "echo 'Declared reproduction command (review before executing):'\n"
                + "printf '%s\\n' " + shellQuote(command) + "\n";
    }

    private static String readme(ResearchPaper paper, PublicationAssessment assessment,
                                 PreRegistration preRegistration) {
        StringBuilder out = new StringBuilder("# Computational artifact package\n\n")
                .append("This package is an immutable audit bundle for **")
                .append(paper.paperId()).append("**. Its label is **")
                .append(paper.publicationLabel().displayName()).append("**.\n\n")
                .append("It does not imply external replication or human peer-review acceptance.\n\n")
                .append("## Contents\n\n")
                .append("- `paper.md`: rendered paper\n")
                .append("- `evidence/pre-registration.json`: locked research contract\n")
                .append("- `evidence/tool-trace.json`: executed computational evidence\n")
                .append("- `results/`: rendered findings and conclusion\n")
                .append("- `manifest.json`: file hashes and quality-gate result\n")
                .append("- `reproduce.sh`: declared reproduction command (printed, never server-executed)\n");
        if (!assessment.missingRequirements().isEmpty()) {
            out.append("\n## Before this can be treated as a computational-paper candidate\n\n");
            for (String missing : assessment.missingRequirements()) {
                out.append("- Add ").append(missing).append(".\n");
            }
        }
        if (preRegistration != null && preRegistration.computationalStudyPlan() != null) {
            out.append("\nThe discovery/confirmation plan is stored in the pre-registration file.\n");
        }
        return out.toString();
    }

    private static ObjectNode checksums(Path root, ObjectMapper mapper) throws IOException {
        ObjectNode out = mapper.createObjectNode();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("manifest.json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            out.put(root.relativize(path).toString(), sha256(Files.readAllBytes(path)));
                        } catch (IOException e) {
                            throw new ArtifactWriteFailure(e);
                        }
                    });
        } catch (ArtifactWriteFailure e) {
            throw e.getCause();
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sourceRevision() {
        String revision = System.getenv("GIT_COMMIT");
        return revision == null || revision.isBlank() ? "unrecorded" : revision;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class ArtifactWriteFailure extends RuntimeException {
        ArtifactWriteFailure(IOException cause) { super(cause); }
        @Override public IOException getCause() { return (IOException) super.getCause(); }
    }
}
