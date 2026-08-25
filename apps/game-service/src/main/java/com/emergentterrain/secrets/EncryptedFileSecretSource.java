package com.emergentterrain.secrets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Local encrypted-file secret source — the zero-dependency, no-network backend.
 *
 * <p>Reads an AES-256-GCM blob (see {@link EncryptedSecretsFile}) from disk and
 * decrypts it with a single bootstrap passphrase — the same
 * one-secret-unlocks-the-rest pattern, but with no external
 * CLI, server, or network, so its runtime footprint is essentially nil. The
 * decrypted plaintext is the same JSON the other sources speak (object or
 * {@code [{key,value}]} array), so it slots into {@link SecretsJson} unchanged.
 *
 * <p>The passphrase resolves from {@code app.secrets.file.passphrase-file} (a
 * mode-0600 file — preferred for daemons / Docker secrets, and never exposed via
 * {@code /proc/<pid>/environ}) if set, otherwise from the env var
 * {@code app.secrets.file.passphrase-env} ({@code ET_SECRETS_KEY} by default).
 *
 * <p>Create / rotate the file with the {@code seal} CLI on
 * {@link EncryptedSecretsFile}. A missing file or unset/incorrect passphrase
 * throws, which the post-processor treats as fail-open.
 */
class EncryptedFileSecretSource implements SecretSource {

    static final String DEFAULT_PATH = "~/.emergentterrain/secrets.enc";

    private final Path file;
    private final Path passphraseFile;   // optional; takes precedence over the env var
    private final String passphraseEnv;
    private final String passphraseFromEnv;

    EncryptedFileSecretSource(SecretsProperties.FileSource cfg, Function<String, String> envLookup) {
        String p = cfg.getPath() == null || cfg.getPath().isBlank() ? DEFAULT_PATH : cfg.getPath();
        this.file = Path.of(EncryptedSecretsFile.expandHome(p));
        String pf = cfg.getPassphraseFile();
        this.passphraseFile = (pf == null || pf.isBlank()) ? null : Path.of(EncryptedSecretsFile.expandHome(pf));
        this.passphraseEnv = cfg.getPassphraseEnv();
        this.passphraseFromEnv = envLookup.apply(this.passphraseEnv);
    }

    @Override
    public String name() {
        return "encrypted-file(" + file + ")";
    }

    @Override
    public Map<String, String> fetch() throws Exception {
        char[] passphrase = resolvePassphrase();
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("secrets file not readable at '" + file
                    + "' — create it with the EncryptedSecretsFile seal CLI, or set app.secrets.file.path");
        }
        byte[] plaintext = EncryptedSecretsFile.unseal(Files.readString(file), passphrase);
        return SecretsJson.parse(new String(plaintext, StandardCharsets.UTF_8));
    }

    /** Passphrase from the configured file if set (mode-0600 friendly), else the env var. */
    private char[] resolvePassphrase() throws Exception {
        if (passphraseFile != null) {
            if (!Files.isReadable(passphraseFile)) {
                throw new IllegalStateException("passphrase file not readable at '" + passphraseFile
                        + "' (app.secrets.file.passphrase-file)");
            }
            char[] pp = EncryptedSecretsFile.readPassphraseFile(passphraseFile);
            if (pp.length == 0) {
                throw new IllegalStateException("passphrase file is empty: " + passphraseFile);
            }
            return pp;
        }
        if (passphraseFromEnv != null && !passphraseFromEnv.isBlank()) {
            return passphraseFromEnv.toCharArray();
        }
        throw new IllegalStateException("no bootstrap passphrase — set app.secrets.file.passphrase-file "
                + "or the env var '" + passphraseEnv + "'");
    }
}
