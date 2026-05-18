package com.emergentterrain.llm;

import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 17 — tests for the thread-local sampling-temperature override.
 * The contract: pools call {@link LlmCallContext#currentTemperature()}
 * just before serialising the request body and apply
 * {@code .orElse(<their-default>)}. Callers wrap with
 * {@link LlmCallContext#withTemperature(double, Runnable)}.
 */
class LlmCallContextTest {

    @AfterEach
    void clearContext() {
        // Belt-and-suspenders — even if a test leaks, the next test starts fresh.
        LlmCallContext.clear();
    }

    @Test
    void unsetReturnsEmpty() {
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void withTemperatureMakesValueVisibleInsideBody() {
        AtomicReference<OptionalDouble> seen = new AtomicReference<>(OptionalDouble.empty());
        LlmCallContext.withTemperature(0.0, () -> seen.set(LlmCallContext.currentTemperature()));
        assertThat(seen.get()).isPresent();
        assertThat(seen.get().getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void overrideClearsAfterBody() {
        LlmCallContext.withTemperature(0.0, () -> {});
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void overrideClearsEvenWhenBodyThrows() {
        assertThatThrownBy(() ->
                LlmCallContext.withTemperature(0.0, () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);
        // Critical: the finally-block must clear even on exception so no
        // leaked override poisons subsequent unrelated calls.
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void supplierOverloadReturnsValue() {
        String result = LlmCallContext.withTemperature(0.0, () -> {
            assertThat(LlmCallContext.currentTemperature().getAsDouble()).isEqualTo(0.0);
            return "value";
        });
        assertThat(result).isEqualTo("value");
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void supplierOverloadClearsOnException() {
        assertThatThrownBy(() ->
                LlmCallContext.<String>withTemperature(0.0, () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void nestedOverridesRestorePriorValue() {
        LlmCallContext.withTemperature(0.5, () -> {
            assertThat(LlmCallContext.currentTemperature().getAsDouble()).isEqualTo(0.5);
            LlmCallContext.withTemperature(0.0, () -> {
                assertThat(LlmCallContext.currentTemperature().getAsDouble()).isEqualTo(0.0);
            });
            // The inner finally restores the outer 0.5 — NOT empty.
            assertThat(LlmCallContext.currentTemperature().getAsDouble()).isEqualTo(0.5);
        });
        assertThat(LlmCallContext.currentTemperature()).isEmpty();
    }

    @Test
    void perThreadIsolation() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<OptionalDouble> otherThreadValue = new AtomicReference<>(OptionalDouble.of(99.0));
        Thread other = new Thread(() -> {
            try {
                start.await();
                // Another thread should see EMPTY even though main set 0.0 — the
                // ThreadLocal is per-thread by definition.
                otherThreadValue.set(LlmCallContext.currentTemperature());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        other.start();
        LlmCallContext.withTemperature(0.0, () -> {
            start.countDown();
            try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(otherThreadValue.get()).isEmpty();
    }

    @Test
    void clearResetsOverride() {
        LlmCallContext.withTemperature(0.5, () -> {
            assertThat(LlmCallContext.currentTemperature()).isPresent();
            LlmCallContext.clear();
            assertThat(LlmCallContext.currentTemperature()).isEmpty();
        });
    }
}
