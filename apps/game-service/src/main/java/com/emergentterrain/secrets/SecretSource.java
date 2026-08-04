package com.emergentterrain.secrets;

import java.util.Map;

/**
 * A source of environment-variable-shaped credentials, pulled once at startup.
 *
 * <p>Modelled on the Hermes Agent external-secret-source design: a single
 * bootstrap token (held in the OS environment / .env) unlocks a vault, and
 * every other provider key is fetched from that vault so it never has to live
 * in plaintext config. The returned map is keyed by env-var name
 * (e.g. {@code GROQ_API_KEY}) so it slots straight into the existing
 * {@code ${GROQ_API_KEY:...}} placeholders.
 *
 * <p>Implementations should be cheap to construct; {@link #fetch()} does the
 * work and may throw — the caller treats any failure as fail-open (log and
 * continue with whatever config already exists).
 */
public interface SecretSource {

    /** Short human label for logs (no secret material). */
    String name();

    /** Fetch secrets as {@code ENV_VAR_NAME -> value}. May throw on any failure. */
    Map<String, String> fetch() throws Exception;
}
