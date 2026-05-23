package com.emergentterrain.secrets;

import java.util.List;
import java.util.Map;

/**
 * Generic vault source: runs a configured command that prints secrets as JSON
 * (object or {@code [{key,value}]} array) to stdout. Lets any vault with a CLI
 * — HashiCorp Vault ({@code vault kv get -format=json ...}), 1Password
 * ({@code op ...}), AWS ({@code aws secretsmanager get-secret-value ...}), etc.
 * — be used without a provider-specific SDK dependency.
 */
class CommandSecretSource implements SecretSource {

    private final List<String> exec;
    private final int timeoutSeconds;

    CommandSecretSource(List<String> exec, int timeoutSeconds) {
        this.exec = exec;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "command(" + (exec == null || exec.isEmpty() ? "?" : exec.get(0)) + ")";
    }

    @Override
    public Map<String, String> fetch() throws Exception {
        if (exec == null || exec.isEmpty()) {
            throw new IllegalStateException("app.secrets.command.exec is empty");
        }
        return SecretsJson.parse(Subprocess.run(exec, Map.of(), timeoutSeconds));
    }
}
