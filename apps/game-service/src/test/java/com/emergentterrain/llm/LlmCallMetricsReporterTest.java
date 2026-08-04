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
    private LlmPricing pricing;
    private LlmCallMetricsReporter reporter;
    private ListAppender<ILoggingEvent> appender;
    private Logger reporterLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new LlmCallMetrics(registry);
        pricing  = new LlmPricing(); // empty by default; tests that need prices populate it
        reporter = new LlmCallMetricsReporter(registry, pricing);

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

        // Header line with totals. All models unpriced here → cost=$0.0000.
        // tokens-in:  groq 1800 + nvidia 1000 + cerebras 300 = 3100
        // tokens-out: groq  350 + nvidia  200 + cerebras  50 =  600
        assertThat(lines.get(0)).isEqualTo(
                "llm-calls total=6 handles=3 tokens-in=3100 tokens-out=600 cost=$0.0000");

        // by-handle section (alphabetical: cerebras, groq, nvidia). Unpriced
        // models carry the [unpriced→$0] marker.
        assertThat(lines).contains(
                "llm-calls   cerebras/qwen total=1 {sync:ok=1.0} tokens-in=300 tokens-out=50 "
                + "cost=$0.0000 [unpriced→$0]");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/llama-3.3-70b total=3 ")
                && s.contains("sync:ok=1.0")
                && s.contains("sync:http_error=1.0")
                && s.contains("tools:ok=1.0")
                && s.endsWith(" tokens-in=1800 tokens-out=350 cost=$0.0000 [unpriced→$0]"));
        assertThat(lines).contains(
                "llm-calls   nvidia/nemotron total=2 {sync:ok=2.0} tokens-in=1000 tokens-out=200 "
                + "cost=$0.0000 [unpriced→$0]");

        // by-role section header + three role rows (alphabetical: narration,
        // panel-review, unknown). Role lines show cost but no unpriced marker
        // (a role can span both priced and unpriced models).
        assertThat(lines).contains("llm-calls by-role:");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=narration calls=3 ")
                && s.contains("groq/llama-3.3-70b=3.0")
                && s.endsWith(" tokens-in=1800 tokens-out=350 cost=$0.0000"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=panel-review calls=2 ")
                && s.contains("nvidia/nemotron=2.0")
                && s.endsWith(" tokens-in=1000 tokens-out=200 cost=$0.0000"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=unknown calls=1 ")
                && s.contains("cerebras/qwen=1.0")
                && s.endsWith(" tokens-in=300 tokens-out=50 cost=$0.0000"));
    }

    @Test
    void periodicReportPricesPerHandleAndSumsRoleAcrossModels() {
        // Two priced groq models: a = $1/$2 per Mtok, b = $10/$20 per Mtok.
        LlmPricing.ProviderPrices groq = new LlmPricing.ProviderPrices();
        LlmPricing.ModelPrice a = new LlmPricing.ModelPrice();
        a.setInUsdPerMtok(1.0);
        a.setOutUsdPerMtok(2.0);
        LlmPricing.ModelPrice b = new LlmPricing.ModelPrice();
        b.setInUsdPerMtok(10.0);
        b.setOutUsdPerMtok(20.0);
        groq.getModels().put("a", a);
        groq.getModels().put("b", b);
        pricing.getProviders().put("groq", groq);

        // Both models exercised under role=narration with 1M in + 1M out each:
        //   groq/a → $1 + $2  = $3.0000
        //   groq/b → $10 + $20 = $30.0000
        // role=narration cost = $33.0000 (sum of its two models at their prices).
        LlmCallContext.withRole("narration", () -> {
            metrics.record("groq", "a", "sync", "ok");
            metrics.recordTokens("groq", "a", 1_000_000, 1_000_000);
            metrics.record("groq", "b", "sync", "ok");
            metrics.recordTokens("groq", "b", 1_000_000, 1_000_000);
        });

        reporter.report();
        List<String> lines = messages();

        assertThat(lines.get(0)).isEqualTo(
                "llm-calls total=2 handles=2 tokens-in=2000000 tokens-out=2000000 cost=$33.0000");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/a ")
                && s.endsWith(" tokens-in=1000000 tokens-out=1000000 cost=$3.0000"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/b ")
                && s.endsWith(" tokens-in=1000000 tokens-out=1000000 cost=$30.0000"));
        // Role cost sums both models at their individual prices.
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=narration calls=2 ")
                && s.endsWith(" tokens-in=2000000 tokens-out=2000000 cost=$33.0000"));
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
        // total tokens-in=100, tokens-out=0; per-handle line same. Unpriced → $0.
        assertThat(lines.get(0)).isEqualTo(
                "llm-calls total=1 handles=1 tokens-in=100 tokens-out=0 cost=$0.0000");
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   groq/llama-3.3-70b ")
                && s.endsWith(" tokens-in=100 tokens-out=0 cost=$0.0000 [unpriced→$0]"));
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
                && s.endsWith(" tokens-in=200 tokens-out=100 cost=$0.0000"));
        assertThat(lines).anyMatch(s ->
                s.startsWith("llm-calls   role=professor-tools calls=1 ")
                && s.endsWith(" tokens-in=200 tokens-out=75 cost=$0.0000"));
    }

    @Test
    void finalDumpReportsZeroWhenNothingRecorded() {
        reporter.reportFinal();
        assertThat(messages()).containsExactly(
                "llm-calls FINAL (shutdown) total=0 (no LLM calls this run)");
    }

    @Test
    void finalDumpPrintsPerModelCostTokensAndCalls() {
        // Price groq/llama-3.3-70b at $0.59 in / $0.79 out per Mtok; leave
        // nvidia/nemotron unpriced so it shows the [unpriced→$0] marker.
        LlmPricing.ProviderPrices groq = new LlmPricing.ProviderPrices();
        LlmPricing.ModelPrice llama = new LlmPricing.ModelPrice();
        llama.setInUsdPerMtok(0.59);
        llama.setOutUsdPerMtok(0.79);
        groq.getModels().put("llama-3.3-70b", llama);
        pricing.getProviders().put("groq", groq);

        // Exactly 1M in + 1M out on groq → cost = 0.59 + 0.79 = $1.38.
        LlmCallContext.withRole("narration", () -> {
            metrics.record("groq", "llama-3.3-70b", "tools", "ok");
            metrics.recordTokens("groq", "llama-3.3-70b", 1_000_000, 1_000_000);
        });
        // Unpriced model → $0, but still counted.
        metrics.record("nvidia", "nemotron", "sync", "ok");
        metrics.recordTokens("nvidia", "nemotron", 500, 100);

        reporter.reportFinal();
        List<String> lines = messages();

        // Header: total cost = groq $1.3800 + nvidia $0 = $1.3800.
        assertThat(lines.get(0)).isEqualTo(
                "llm-calls FINAL (shutdown) total=2 handles=2 "
                + "tokens-in=1000500 tokens-out=1000100 cost=$1.3800");
        // Per-model rows (alphabetical: groq, nvidia).
        assertThat(lines).contains(
                "llm-calls FINAL   groq/llama-3.3-70b calls=1 "
                + "tokens-in=1000000 tokens-out=1000000 cost=$1.3800");
        assertThat(lines).contains(
                "llm-calls FINAL   nvidia/nemotron calls=1 "
                + "tokens-in=500 tokens-out=100 cost=$0.0000 [unpriced→$0]");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }
}
