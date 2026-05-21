package com.emergentterrain.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23.6d follow-on — pins the two-section heartbeat shape (by-handle +
 * by-role) and the input/output token columns on
 * {@link LlmCallMetricsReporter}. Uses a {@link SimpleMeterRegistry} backing
 * a real {@link LlmCallMetrics}, invokes {@code record} / {@code recordTokens}
 * under {@link LlmCallContext#withRole} scopes, then asserts the captured
 * log lines.
 */
class LlmCallMetricsReporterTest {

    private SimpleMeterRegistry registry;
    private LlmCallMetrics metrics;
    private LlmCallMetricsReporter reporter;
    private ListAppender<ILoggingEvent> appender;
    private Logger reporterLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new LlmCallMetrics(registry);
        reporter = new LlmCallMetricsReporter(registry);

        reporterLogger = (Logger) LoggerFactory.getLogger(LlmCallMetricsReporter.class);
        appender = new ListAppender<>();
        appender.start();
        reporterLogger.addAppender(appender);
        reporterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        reporterLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void emptyMetricsReportsTotalZero() {
        reporter.report();
        assertThat(messages()).containsExactly(
                "llm-calls total=0 (no LLM calls recorded yet)");
    }

    @Test
    void recordsByHandleAndByRoleWithTokenColumns() {
        // 3 calls under role=narration on groq/llama-3.3-70b — one tools+ok,
        // one sync+ok, one sync+http_error. Tokens: 1000+200, 800+150, no
        // tokens on the http_error (the recordTokens path is gated on a
        // successful 200, so a failed call wouldn't increment).
        LlmCallContext.withRole("narration", () -> {
            metrics.record("groq", "llama-3.3-70b", "tools", "ok");
            metrics.recordTokens("groq", "llama-3.3-70b", 1000, 200);
            metrics.record("groq", "llama-3.3-70b", "sync", "ok");
            metrics.recordTokens("groq", "llama-3.3-70b", 800, 150);
            metrics.record("groq", "llama-3.3-70b", "sync", "http_error");
        });

        // 2 calls under role=panel-review on nvidia/nemotron — both sync+ok,
        // 500+100 tokens each.
        LlmCallContext.withRole("panel-review", () -> {
            metrics.record("nvidia", "nemotron", "sync", "ok");
            metrics.recordTokens("nvidia", "nemotron", 500, 100);
            metrics.record("nvidia", "nemotron", "sync", "ok");
            metrics.recordTokens("nvidia", "nemotron", 500, 100);
        });

        // 1 call outside any role scope — falls under role=unknown.
        metrics.record("cerebras", "qwen", "sync", "ok");
        metrics.recordTokens("cerebras", "qwen", 300, 50);

        reporter.report();

        List<String> lines = messages();

        // Header line with totals.
        // tokens-in:  groq 1800 + nvidia 1000 + cerebras 300 = 3100
        // tokens-out: groq  350 + nvidia  200 + cerebras  50 =  600
        assertThat(lines.get(0)).isEqualTo(
                "llm-calls total=6 handles=3 tokens-in=3100 tokens-out=600");

        // by-handle section (alphabetical: cerebras, groq, nvidia).
        assertThat(lines).contains(
                "llm-calls   cerebras/qwen total=1 {sync:ok=1.0} tokens-in=300 tokens-out=50");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/llama-3.3-70b total=3 ")
                && s.contains("sync:ok=1.0")
                && s.contains("sync:http_error=1.0")
                && s.contains("tools:ok=1.0")
                && s.endsWith(" tokens-in=1800 tokens-out=350"));
        assertThat(lines).contains(
                "llm-calls   nvidia/nemotron total=2 {sync:ok=2.0} tokens-in=1000 tokens-out=200");

        // by-role section header + three role rows (alphabetical: narration,
        // panel-review, unknown).
        assertThat(lines).contains("llm-calls by-role:");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=narration calls=3 ")
                && s.contains("groq/llama-3.3-70b=3.0")
                && s.endsWith(" tokens-in=1800 tokens-out=350"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=panel-review calls=2 ")
                && s.contains("nvidia/nemotron=2.0")
                && s.endsWith(" tokens-in=1000 tokens-out=200"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=unknown calls=1 ")
                && s.contains("cerebras/qwen=1.0")
                && s.endsWith(" tokens-in=300 tokens-out=50"));
    }

    @Test
    void zeroTokensIncrementDoesNotBumpCounter() {
        // The recordTokens path is gated on positive values per side; zero
        // prompt OR zero completion should not create a counter row for
        // that side. Verifies the gate inside LlmCallMetrics.recordTokens.
        LlmCallContext.withRole("narration", () -> {
            metrics.record("groq", "llama-3.3-70b", "sync", "ok");
            metrics.recordTokens("groq", "llama-3.3-70b", 100, 0);
        });
        reporter.report();
        List<String> lines = messages();
        // total tokens-in=100, tokens-out=0; per-handle line same.
        assertThat(lines.get(0)).isEqualTo(
                "llm-calls total=1 handles=1 tokens-in=100 tokens-out=0");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/llama-3.3-70b ")
                && s.endsWith(" tokens-in=100 tokens-out=0"));
    }

    @Test
    void rolesAreReadFromContextAtRecordTimeNotInjectionTime() {
        // Even though we call .record() and .recordTokens() through the same
        // metrics instance, each invocation reads LlmCallContext.currentRole()
        // at call time. So calls inside two nested scopes get the inner role.
        LlmCallContext.withRole("narration", () -> {
            metrics.record("groq", "x", "sync", "ok");
            metrics.recordTokens("groq", "x", 100, 50);
            LlmCallContext.withRole("professor-tools", () -> {
                metrics.record("groq", "x", "tools", "ok");
                metrics.recordTokens("groq", "x", 200, 75);
            });
            metrics.record("groq", "x", "sync", "ok");
            metrics.recordTokens("groq", "x", 100, 50);
        });
        reporter.report();
        List<String> lines = messages();

        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=narration calls=2 ")
                && s.endsWith(" tokens-in=200 tokens-out=100"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=professor-tools calls=1 ")
                && s.endsWith(" tokens-in=200 tokens-out=75"));
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }
}
