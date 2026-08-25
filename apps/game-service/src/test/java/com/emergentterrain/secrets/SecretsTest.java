package com.emergentterrain.secrets;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the pure pieces of the Hermes-style external secret source: JSON
 * parsing (both shapes), the non-destructive merge, and a real subprocess
 * round-trip via the generic command source.
 */
class SecretsTest {

    // ---- SecretsJson ------------------------------------------------------

    @Test
    void parse_objectForm() throws Exception {
        Map<String, String> m = SecretsJson.parse("{\"GROQ_API_KEY\":\"gsk_x\",\"OPENROUTER_API_KEY\":\"sk-or-y\"}");
        assertThat(m).containsEntry("GROQ_API_KEY", "gsk_x").containsEntry("OPENROUTER_API_KEY", "sk-or-y");
    }

    @Test
    void parse_recordArrayForm() throws Exception {
        String records = "[{\"id\":\"1\",\"key\":\"GROQ_API_KEY\",\"value\":\"gsk_x\",\"note\":\"\"},"
                + "{\"id\":\"2\",\"key\":\"NVIDIA_API_KEY\",\"value\":\"nvapi-z\"}]";
        Map<String, String> m = SecretsJson.parse(records);
        assertThat(m).containsEntry("GROQ_API_KEY", "gsk_x").containsEntry("NVIDIA_API_KEY", "nvapi-z");
    }

    @Test
    void parse_emptyAndBlank() throws Exception {
        assertThat(SecretsJson.parse("")).isEmpty();
        assertThat(SecretsJson.parse(null)).isEmpty();
        assertThat(SecretsJson.parse("[]")).isEmpty();
        assertThat(SecretsJson.parse("{}")).isEmpty();
    }

    @Test
    void parse_rejectsScalarRoot() {
        assertThatThrownBy(() -> SecretsJson.parse("\"not-a-map\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- non-destructive merge -------------------------------------------

    @Test
    void nonDestructive_skipsAlreadyDefinedKeys() {
        Map<String, String> fetched = new java.util.LinkedHashMap<>();
        fetched.put("GROQ_API_KEY", "from-vault");
        fetched.put("OPENROUTER_API_KEY", "from-vault");
        // GROQ_API_KEY is "already set" (e.g. an OS env export) → must be kept as-is
        Set<String> existing = Set.of("GROQ_API_KEY");

        Map<String, Object> additions =
                ExternalSecretsEnvironmentPostProcessor.nonDestructive(fetched, existing::contains);

        assertThat(additions).containsOnlyKeys("OPENROUTER_API_KEY");
        assertThat(additions).doesNotContainKey("GROQ_API_KEY");
    }

    // ---- CommandSecretSource (real subprocess) ---------------------------

    @Test
    void command_readsJsonFromStdout() throws Exception {
        // `cat <file>` is a portable stand-in for any vault CLI that emits JSON.
        Path f = Files.createTempFile("secrets", ".json");
        Files.writeString(f, "{\"CEREBRAS_API_KEY\":\"csk-abc\"}");
        try {
            CommandSecretSource src = new CommandSecretSource(List.of("cat", f.toString()), 10);
            assertThat(src.fetch()).containsEntry("CEREBRAS_API_KEY", "csk-abc");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void command_missingBinary_throws() {
        CommandSecretSource src = new CommandSecretSource(List.of("definitely-not-a-real-binary-xyz"), 5);
        assertThatThrownBy(src::fetch).isInstanceOf(Exception.class);
    }

    // ---- ExternalSecretsEnvironmentPostProcessor (end-to-end) ------------

    private static final DeferredLogFactory LOG_FACTORY =
            supplier -> LogFactory.getLog(ExternalSecretsEnvironmentPostProcessor.class);

    @Test
    void postProcessor_injectsVaultSecrets_nonDestructively() throws Exception {
        Path f = Files.createTempFile("secrets", ".json");
        Files.writeString(f, "{\"GROQ_API_KEY\":\"from-vault\",\"CEREBRAS_API_KEY\":\"csk-new\"}");
        try {
            StandardEnvironment env = new StandardEnvironment();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("app.secrets.enabled", true);
            cfg.put("app.secrets.source", "command");
            cfg.put("app.secrets.command.exec[0]", "cat");
            cfg.put("app.secrets.command.exec[1]", f.toString());
            cfg.put("GROQ_API_KEY", "from-env");  // pre-existing → must win
            env.getPropertySources().addFirst(new MapPropertySource("test", cfg));

            new ExternalSecretsEnvironmentPostProcessor(LOG_FACTORY).postProcessEnvironment(env, null);

            assertThat(env.getProperty("CEREBRAS_API_KEY")).isEqualTo("csk-new");   // gap filled
            assertThat(env.getProperty("GROQ_API_KEY")).isEqualTo("from-env");      // not overridden
            assertThat(env.getPropertySources().contains("externalSecrets")).isTrue();
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void postProcessor_disabled_isNoOp() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("app.secrets.enabled", false);
        cfg.put("app.secrets.source", "command");
        cfg.put("app.secrets.command.exec[0]", "cat");
        env.getPropertySources().addFirst(new MapPropertySource("test", cfg));

        new ExternalSecretsEnvironmentPostProcessor(LOG_FACTORY).postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains("externalSecrets")).isFalse();
    }

    // ---- CachingSecretSource (TTL) ---------------------------------------

    /** Delegate that counts fetches and returns a fresh map each time. */
    private static final class CountingSource implements SecretSource {
        int calls = 0;
        @Override public String name() { return "counting"; }
        @Override public Map<String, String> fetch() { calls++; return Map.of("K", "v" + calls); }
    }

    @Test
    void cache_servesFromMemoryWithinTtl_thenRefetchesAfterExpiry() throws Exception {
        CountingSource delegate = new CountingSource();
        long[] now = {1_000_000L};
        CachingSecretSource cache = new CachingSecretSource(delegate, 300, () -> now[0]);  // 300s TTL

        Map<String, String> first = cache.fetch();          // miss → delegate call #1
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(first).containsEntry("K", "v1");

        now[0] += 299_000L;                                  // still within TTL
        assertThat(cache.fetch()).containsEntry("K", "v1");  // hit → no new call
        assertThat(delegate.calls).isEqualTo(1);

        now[0] += 2_000L;                                    // now past 300s
        assertThat(cache.fetch()).containsEntry("K", "v2");  // miss → delegate call #2
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void cache_ttlZero_alwaysRefetches() throws Exception {
        CountingSource delegate = new CountingSource();
        long[] now = {0L};
        CachingSecretSource cache = new CachingSecretSource(delegate, 0, () -> now[0]);
        cache.fetch();
        cache.fetch();
        assertThat(delegate.calls).isEqualTo(2);
    }

    // ---- EncryptedSecretsFile (AES-256-GCM round-trip) -------------------

    @Test
    void encryptedFile_sealThenUnseal_roundTrips() throws Exception {
        byte[] plain = "{\"GROQ_API_KEY\":\"gsk_x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String blob = EncryptedSecretsFile.seal(plain, "correct horse".toCharArray());
        byte[] back = EncryptedSecretsFile.unseal(blob, "correct horse".toCharArray());
        assertThat(new String(back, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"GROQ_API_KEY\":\"gsk_x\"}");
    }

    @Test
    void encryptedFile_wrongPassphrase_throws() throws Exception {
        String blob = EncryptedSecretsFile.seal("{}".getBytes(), "right".toCharArray());
        assertThatThrownBy(() -> EncryptedSecretsFile.unseal(blob, "wrong".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong passphrase");
    }

    @Test
    void encryptedFile_tamperedCiphertext_throws() throws Exception {
        String blob = EncryptedSecretsFile.seal("{\"K\":\"v\"}".getBytes(), "pw".toCharArray());
        byte[] raw = java.util.Base64.getDecoder().decode(blob);
        raw[raw.length - 1] ^= 0x01;  // flip a ciphertext/tag bit → GCM auth must fail
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> EncryptedSecretsFile.unseal(tampered, "pw".toCharArray()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptedFile_badMagic_throws() {
        String notOurs = java.util.Base64.getEncoder().encodeToString(new byte[64]);  // all-zero header
        assertThatThrownBy(() -> EncryptedSecretsFile.unseal(notOurs, "pw".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readPassphraseFile_stripsOneTrailingNewline_keepsInteriorSpaces() throws Exception {
        Path p = Files.createTempFile("pp", ".txt");
        Files.writeString(p, "correct horse battery\n");  // a printf/editor trailing newline
        try {
            assertThat(new String(EncryptedSecretsFile.readPassphraseFile(p)))
                    .isEqualTo("correct horse battery");
        } finally {
            Files.deleteIfExists(p);
        }
    }

    // ---- EncryptedFileSecretSource ---------------------------------------

    @Test
    void encryptedFileSource_readsSealedFile() throws Exception {
        Path f = Files.createTempFile("secrets", ".enc");
        Files.writeString(f, EncryptedSecretsFile.seal(
                "{\"NVIDIA_API_KEY\":\"nvapi-z\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "pw".toCharArray()));
        try {
            SecretsProperties.FileSource cfg = new SecretsProperties.FileSource();
            cfg.setPath(f.toString());
            EncryptedFileSecretSource src = new EncryptedFileSecretSource(cfg, name -> "pw");
            assertThat(src.fetch()).containsEntry("NVIDIA_API_KEY", "nvapi-z");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void encryptedFileSource_passphraseFileWinsOverEnv() throws Exception {
        Path enc = Files.createTempFile("secrets", ".enc");
        Path pass = Files.createTempFile("passphrase", ".txt");
        Files.writeString(pass, "file-pw\n");  // trailing newline must be stripped to match seal
        Files.writeString(enc, EncryptedSecretsFile.seal(
                "{\"GROQ_API_KEY\":\"gsk_f\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "file-pw".toCharArray()));
        try {
            SecretsProperties.FileSource cfg = new SecretsProperties.FileSource();
            cfg.setPath(enc.toString());
            cfg.setPassphraseFile(pass.toString());
            // env resolver returns a DIFFERENT value → the file must take precedence
            EncryptedFileSecretSource src = new EncryptedFileSecretSource(cfg, name -> "wrong-env-pw");
            assertThat(src.fetch()).containsEntry("GROQ_API_KEY", "gsk_f");
        } finally {
            Files.deleteIfExists(enc);
            Files.deleteIfExists(pass);
        }
    }

    @Test
    void encryptedFileSource_passphraseFileMissing_throws() {
        SecretsProperties.FileSource cfg = new SecretsProperties.FileSource();
        cfg.setPath("/whatever.enc");
        cfg.setPassphraseFile("/definitely/not/here-pass-xyz");
        EncryptedFileSecretSource src = new EncryptedFileSecretSource(cfg, name -> "ignored");
        assertThatThrownBy(src::fetch).hasMessageContaining("passphrase file not readable");
    }

    @Test
    void encryptedFileSource_missingPassphrase_throws() {
        SecretsProperties.FileSource cfg = new SecretsProperties.FileSource();
        cfg.setPath("/whatever.enc");
        EncryptedFileSecretSource src = new EncryptedFileSecretSource(cfg, name -> null);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(src::fetch)
                .withMessageContaining("ET_SECRETS_KEY");
    }

    @Test
    void encryptedFileSource_missingFile_throws() {
        SecretsProperties.FileSource cfg = new SecretsProperties.FileSource();
        cfg.setPath("/definitely/not/here-xyz.enc");
        EncryptedFileSecretSource src = new EncryptedFileSecretSource(cfg, name -> "pw");
        assertThatThrownBy(src::fetch).hasMessageContaining("not readable");
    }

    @Test
    void postProcessor_fileSource_injectsSecrets() throws Exception {
        Path f = Files.createTempFile("secrets", ".enc");
        Files.writeString(f, EncryptedSecretsFile.seal(
                "{\"CEREBRAS_API_KEY\":\"csk-file\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "home-pw".toCharArray()));
        try {
            StandardEnvironment env = new StandardEnvironment();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("app.secrets.enabled", true);
            cfg.put("app.secrets.source", "file");
            cfg.put("app.secrets.file.path", f.toString());
            cfg.put("ET_SECRETS_KEY", "home-pw");  // bootstrap passphrase, env-resolved
            env.getPropertySources().addFirst(new MapPropertySource("test", cfg));

            new ExternalSecretsEnvironmentPostProcessor(LOG_FACTORY).postProcessEnvironment(env, null);

            assertThat(env.getProperty("CEREBRAS_API_KEY")).isEqualTo("csk-file");
            assertThat(env.getPropertySources().contains("externalSecrets")).isTrue();
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void postProcessor_failOpen_onBadSourceOrCommand() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("app.secrets.enabled", true);
        cfg.put("app.secrets.source", "command");
        cfg.put("app.secrets.command.exec[0]", "definitely-not-a-real-binary-xyz");
        env.getPropertySources().addFirst(new MapPropertySource("test", cfg));

        // must not throw — startup continues with existing config
        new ExternalSecretsEnvironmentPostProcessor(LOG_FACTORY).postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains("externalSecrets")).isFalse();
    }

    // ---- FileSecretsBootstrap (missing-secrets-file first-run) -----------

    @Test
    void bootstrap_expectedKeys_fromProviderApiKeyEnv() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("app.ai.providers.openrouter.api-key-env", "OPENROUTER_API_KEY");
        cfg.put("app.ai.providers.groq.api-key-env", "GROQ_API_KEY");
        cfg.put("app.ai.providers.legacy-ai.api-key", "local-login"); // literal → no env key
        env.getPropertySources().addFirst(new MapPropertySource("test", cfg));

        List<String> keys = FileSecretsBootstrap.expectedKeys(env);
        // sorted, de-duped, only the api-key-env values (not the literal-key provider)
        assertThat(keys).containsExactly("GROQ_API_KEY", "OPENROUTER_API_KEY");
    }

    @Test
    void bootstrap_expectedKeys_fallsBackToDefaultsWhenNoneConfigured() {
        StandardEnvironment env = new StandardEnvironment();
        assertThat(FileSecretsBootstrap.expectedKeys(env))
                .isEqualTo(FileSecretsBootstrap.DEFAULT_KEYS)
                .contains("GROQ_API_KEY", "ANTHROPIC_API_KEY");
    }

    @Test
    void bootstrap_scaffold_createsTemplateWithEmptyValues(@TempDir Path dir) throws Exception {
        Path enc = dir.resolve("secrets.enc");
        Path json = dir.resolve("secrets.json");
        Path pass = dir.resolve("passphrase");
        List<String> keys = List.of("GROQ_API_KEY", "OPENROUTER_API_KEY");

        String msg = FileSecretsBootstrap.scaffold(enc, json, pass, keys);

        assertThat(Files.exists(json)).isTrue();
        Map<String, String> parsed = SecretsJson.parse(Files.readString(json));
        assertThat(parsed).containsOnlyKeys("GROQ_API_KEY", "OPENROUTER_API_KEY");
        assertThat(parsed.values()).allMatch(String::isEmpty);   // values are empty templates
        // instructions name the real paths + both setup commands
        assertThat(msg).contains(json.toString()).contains(enc.toString()).contains(pass.toString())
                .contains("openssl rand").contains("EncryptedSecretsFile").contains("seal");
    }

    @Test
    void bootstrap_scaffold_doesNotOverwriteExistingTemplate(@TempDir Path dir) throws Exception {
        Path enc = dir.resolve("secrets.enc");
        Path json = dir.resolve("secrets.json");
        Files.writeString(json, "{\"GROQ_API_KEY\":\"gsk-already-filled\"}");

        String msg = FileSecretsBootstrap.scaffold(enc, json, dir.resolve("passphrase"),
                List.of("GROQ_API_KEY"));

        assertThat(Files.readString(json)).contains("gsk-already-filled"); // preserved
        assertThat(msg).contains("Found an existing");
    }
}
