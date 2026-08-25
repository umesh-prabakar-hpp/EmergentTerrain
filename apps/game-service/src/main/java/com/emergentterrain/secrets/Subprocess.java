package com.emergentterrain.secrets;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a vault CLI as a subprocess and returns its stdout.
 *
 * <p>Secrets are passed via the child's
 * <b>environment</b>, never as argv, so they don't leak through
 * {@code ps}/process listings. stderr is drained on a separate thread to avoid
 * a pipe-buffer deadlock; stdout is returned, stderr surfaces only in the error
 * message on failure.
 */
final class Subprocess {

    private Subprocess() {}

    static String run(List<String> command, Map<String, String> extraEnv, int timeoutSeconds) throws Exception {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            throw new RuntimeException("could not start '" + command.get(0)
                    + "' (is it installed / on PATH?): " + e.getMessage(), e);
        }

        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try {
                err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // best-effort diagnostics only
            }
        }, "secrets-subprocess-stderr");
        errReader.setDaemon(true);
        errReader.start();

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("'" + command.get(0) + "' timed out after " + timeoutSeconds + "s");
        }
        errReader.join(1000);
        int code = p.exitValue();
        if (code != 0) {
            throw new RuntimeException("'" + command.get(0) + "' exited " + code + ": " + err.toString().strip());
        }
        return stdout;
    }
}
