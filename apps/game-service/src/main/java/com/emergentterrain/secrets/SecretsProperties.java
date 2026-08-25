package com.emergentterrain.secrets;

import java.util.ArrayList;
import java.util.List;

/**
 * Binding target for {@code app.secrets}. Bound by
 * {@link ExternalSecretsEnvironmentPostProcessor} via {@code Binder} (it runs
 * before the application context exists, so this is a plain POJO, not a
 * {@code @ConfigurationProperties} bean). Default OFF.
 */
public class SecretsProperties {

    /** Master switch. Default OFF — no vault pull, today's behaviour. */
    private boolean enabled = false;

    /** Which backend to use: {@code file} or {@code command}. */
    private String source = "file";

    /** Subprocess timeout for the vault CLI, seconds. */
    private int timeoutSeconds = 30;

    /** In-memory cache TTL for fetched secrets, seconds. {@code <= 0} disables caching. */
    private int cacheTtlSeconds = 300;

    private Command command = new Command();
    private FileSource file = new FileSource();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

    public Command getCommand() { return command; }
    public void setCommand(Command command) { this.command = command; }

    public FileSource getFile() { return file; }
    public void setFile(FileSource file) { this.file = file; }

    /** Generic command source: a CLI that prints secrets as JSON to stdout. */
    public static class Command {
        private List<String> exec = new ArrayList<>();
        public List<String> getExec() { return exec; }
        public void setExec(List<String> exec) { this.exec = exec; }
    }

    /** Local encrypted-file source: AES-256-GCM blob unlocked by one bootstrap passphrase. */
    public static class FileSource {
        /** Path to the encrypted secrets file. Empty = ~/.emergentterrain/secrets.enc. */
        private String path = "";
        /** Env var holding the one bootstrap secret (the decryption passphrase). */
        private String passphraseEnv = "ET_SECRETS_KEY";
        /** Optional mode-0600 file holding the passphrase. Wins over passphrase-env; empty = unused. */
        private String passphraseFile = "";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getPassphraseEnv() { return passphraseEnv; }
        public void setPassphraseEnv(String passphraseEnv) { this.passphraseEnv = passphraseEnv; }
        public String getPassphraseFile() { return passphraseFile; }
        public void setPassphraseFile(String passphraseFile) { this.passphraseFile = passphraseFile; }
    }
}
