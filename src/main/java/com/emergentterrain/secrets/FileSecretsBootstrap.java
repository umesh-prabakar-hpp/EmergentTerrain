package com.emergentterrain.secrets;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * First-run helper for the {@code file} secret source. When the encrypted
 * secrets file ({@code secrets.enc}) is missing at startup, scaffold a
 * {@code secrets.json} template — the env-var keys the configured providers
 * expect, with empty values — and build copy-paste instructions for generating
 * a random passphrase and sealing it. The caller ({@link
 * ExternalSecretsEnvironmentPostProcessor}) prints the message and terminates,
 * so the operator sets up real secrets instead of the app running with blank
 * credentials (every provider call would 401).
 *
 * <p>Pure-ish and side-effect-scoped (creates one file under the secrets dir,
 * returns text, never exits) so it's unit-testable against a temp dir.
 */
final class FileSecretsBootstrap {

    private FileSecretsBootstrap() {}

    /** Common provider key names — used only when no provider declares an api-key-env. */
    static final List<String> DEFAULT_KEYS = List.of(
            "GROQ_API_KEY", "OPENROUTER_API_KEY", "NVIDIA_API_KEY", "CEREBRAS_API_KEY",
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "MISTRAL_API_KEY",
            "DEEPSEEK_API_KEY", "XAI_API_KEY", "TOGETHER_API_KEY", "FIREWORKS_API_KEY",
            "COHERE_API_KEY", "PERPLEXITY_API_KEY");

    /**
     * Env-var names referenced by {@code app.ai.providers.*.api-key-env} (the
     * keys the app actually expects), sorted and de-duplicated. Falls back to
     * {@link #DEFAULT_KEYS} when none are configured (e.g. all providers use a
     * literal {@code api-key}).
     */
    static List<String> expectedKeys(ConfigurableEnvironment env) {
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (name.startsWith("app.ai.providers.")
                            && (name.endsWith(".api-key-env") || name.endsWith(".apiKeyEnv"))) {
                        Object v = ps.getProperty(name);
                        if (v != null && !v.toString().isBlank()) keys.add(v.toString().trim());
                    }
                }
            }
        }
        return keys.isEmpty() ? new ArrayList<>(DEFAULT_KEYS) : new ArrayList<>(keys);
    }

    /** A {@code {"KEY": "", ...}} template, pretty-printed, keys in given order. */
    static String templateJson(List<String> keys) {
        StringBuilder sb = new StringBuilder("{\n");
        for (int i = 0; i < keys.size(); i++) {
            sb.append("  \"").append(keys.get(i)).append("\": \"\"")
              .append(i < keys.size() - 1 ? "," : "").append("\n");
        }
        return sb.append("}\n").toString();
    }

    /**
     * Create {@code jsonFile} (mode 0600, parent dirs 0700) with the template if
     * it doesn't already exist — an existing one is never overwritten — and
     * return the operator instructions. Does not terminate; the caller does.
     */
    static String scaffold(Path encFile, Path jsonFile, Path passFile, List<String> keys) {
        String fileNote;
        try {
            if (jsonFile.getParent() != null) {
                Files.createDirectories(jsonFile.getParent());
                trySetPerms(jsonFile.getParent(), true);
            }
            if (Files.exists(jsonFile)) {
                fileNote = "Found an existing template at " + jsonFile
                        + " — fill in your API keys (no need to recreate it).";
            } else {
                Files.writeString(jsonFile, templateJson(keys), StandardCharsets.UTF_8);
                trySetPerms(jsonFile, false);
                fileNote = "Created a template at " + jsonFile + " with " + keys.size()
                        + " expected key(s): " + String.join(", ", keys) + " — fill in your API keys.";
            }
        } catch (IOException e) {
            fileNote = "Could not write a template at " + jsonFile + " (" + e.getMessage()
                    + "); create it manually with these keys: " + String.join(", ", keys);
        }
        return banner(encFile, jsonFile, passFile, fileNote);
    }

    private static String banner(Path encFile, Path jsonFile, Path passFile, String fileNote) {
        String nl = System.lineSeparator();
        return nl
            + "================================================================================" + nl
            + " EmergentTerrain: encrypted secrets file not found" + nl
            + "   expected: " + encFile + nl
            + "   (app.secrets.enabled=true, source=file — sealed secrets are required)" + nl
            + nl
            + " " + fileNote + nl
            + nl
            + " To finish setup, then restart the app:" + nl
            + nl
            + "   1) Fill in your API keys:" + nl
            + "        $EDITOR " + jsonFile + nl
            + nl
            + "   2) Generate a random bootstrap passphrase (written mode 0600):" + nl
            + "        ( umask 077; openssl rand -base64 32 | tr -d '\\n' > " + passFile + " )" + nl
            + nl
            + "   3) Seal secrets.json into the encrypted file (run from the project root," + nl
            + "      where target/classes exists):" + nl
            + "        java -cp target/classes com.emergentterrain.secrets.EncryptedSecretsFile \\" + nl
            + "          seal " + encFile + " --passphrase-file " + passFile + " < " + jsonFile + nl
            + nl
            + "   4) Restart:  ./scripts/start.sh" + nl
            + nl
            + " Alternatively set app.secrets.enabled=false to run without the vault." + nl
            + "================================================================================" + nl;
    }

    private static void trySetPerms(Path p, boolean dir) {
        try {
            Set<PosixFilePermission> perms = dir
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception ignore) {
            // non-POSIX filesystem — best effort
        }
    }
}
