package com.emergentterrain.secrets;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Hermes-style external secret source for Spring.
 *
 * <p>At startup — after {@code application.yml} / OS env / {@code .env} have
 * loaded — pulls credentials from a vault (one bootstrap token unlocks the
 * rest) and injects them as a <b>lowest-precedence</b> property source. Two
 * properties make it safe:
 *
 * <ul>
 *   <li><b>Non-destructive</b>: a fetched key is skipped if it's already defined
 *       by any existing source (OS env, {@code .env}, yaml), so shell exports
 *       and explicit config always win. Belt-and-braces, the source is also
 *       added with {@code addLast} (lowest precedence). Literal placeholder
 *       defaults like {@code ${GROQ_API_KEY:gsk_...}} are NOT property sources,
 *       so a vault value correctly supersedes the committed fallback.</li>
 *   <li><b>Fail-open</b>: any failure (missing CLI, no token, no network) logs
 *       one line and continues with whatever config already exists — the vault
 *       never blocks startup.</li>
 * </ul>
 *
 * <p>Default OFF (see {@link SecretsProperties}); registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.
 */
public class ExternalSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "externalSecrets";

    /** Exit code when the file source has no sealed secrets and the operator must set them up. */
    static final int SECRETS_SETUP_EXIT = 78; // EX_CONFIG (sysexits)

    private final Log log;

    public ExternalSecretsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    /** Run last, so {@code application.yml} (and thus {@code app.secrets.*}) is already bound. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SecretsProperties props = Binder.get(environment)
                .bind("app.secrets", SecretsProperties.class)
                .orElseGet(SecretsProperties::new);

        if (!props.isEnabled()) {
            return;
        }
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return; // idempotent — don't double-process
        }

        // File source first-run: if the encrypted secrets file is missing, scaffold a
        // secrets.json template and print setup instructions, then terminate — rather
        // than silently running with blank credentials (every provider call would 401).
        // Other failures (bad/missing passphrase on an existing file) stay fail-open below.
        if ("file".equalsIgnoreCase(props.getSource() == null ? "" : props.getSource().trim())) {
            Path encFile = resolveEncPath(props.getFile());
            if (!Files.exists(encFile)) {
                Path jsonFile = encFile.resolveSibling("secrets.json");
                Path passFile = resolvePassphrasePath(props.getFile(), encFile);
                List<String> keys = FileSecretsBootstrap.expectedKeys(environment);
                String message = FileSecretsBootstrap.scaffold(encFile, jsonFile, passFile, keys);
                // stderr, not the deferred log: the logging system isn't initialized this
                // early, so a deferred log line would never flush before System.exit.
                System.err.println(message);
                log.warn("external-secrets: no sealed secrets at " + encFile
                        + " — wrote/needs a secrets.json template; setup instructions on stderr. Terminating (exit "
                        + SECRETS_SETUP_EXIT + ").");
                System.exit(SECRETS_SETUP_EXIT);
                return;
            }
        }

        try {
            SecretSource source = build(props, environment);
            Map<String, String> fetched = source.fetch();
            Map<String, Object> additions = nonDestructive(fetched, environment::containsProperty);
            int skipped = fetched.size() - additions.size();

            if (additions.isEmpty()) {
                log.info(String.format(
                        "external-secrets: %s returned %d secret(s); all already set — nothing added",
                        source.name(), fetched.size()));
                return;
            }
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, additions));
            log.info(String.format(
                    "external-secrets: added %d secret(s) from %s (non-destructive; kept %d already-set)",
                    additions.size(), source.name(), skipped));
        } catch (Exception ex) {
            // Fail-open: never block startup on a vault hiccup.
            log.warn("external-secrets: load failed, continuing with existing config only: " + ex.getMessage());
        }
    }

    /** Keep only fetched keys not already defined elsewhere (existing config wins). */
    static Map<String, Object> nonDestructive(Map<String, String> fetched, Predicate<String> alreadyDefined) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fetched.entrySet()) {
            if (!alreadyDefined.test(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private SecretSource build(SecretsProperties props, ConfigurableEnvironment env) {
        String s = props.getSource() == null ? "" : props.getSource().trim().toLowerCase();
        SecretSource base;
        switch (s) {
            case "command":
                base = new CommandSecretSource(props.getCommand().getExec(), props.getTimeoutSeconds());
                break;
            case "file":
                base = new EncryptedFileSecretSource(props.getFile(), env::getProperty);
                break;
            default:
                throw new IllegalStateException(
                        "unknown app.secrets.source '" + props.getSource() + "' (expected command|file)");
        }
        // In-memory TTL cache so any runtime re-fetch can't hammer the vault.
        return new CachingSecretSource(base, props.getCacheTtlSeconds());
    }

    /** Resolve the encrypted-file path the same way {@link EncryptedFileSecretSource} does. */
    private static Path resolveEncPath(SecretsProperties.FileSource cfg) {
        String p = (cfg.getPath() == null || cfg.getPath().isBlank())
                ? EncryptedFileSecretSource.DEFAULT_PATH : cfg.getPath();
        return Path.of(EncryptedSecretsFile.expandHome(p));
    }

    /** Configured passphrase-file, or {@code passphrase} alongside the encrypted file. */
    private static Path resolvePassphrasePath(SecretsProperties.FileSource cfg, Path encFile) {
        String pf = cfg.getPassphraseFile();
        if (pf == null || pf.isBlank()) return encFile.resolveSibling("passphrase");
        return Path.of(EncryptedSecretsFile.expandHome(pf));
    }
}
