package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 7 F1 — registry behaviour + INTERDISCIPLINARY invariant. */
class TopicValidatorRegistryTest {

    private static TopicValidatorRegistry.ValidatorSpec emptySpec() {
        return new TopicValidatorRegistry.ValidatorSpec(
                "", null, 0.0, "", 0.0, "", false, "");
    }

    private static TopicValidatorRegistry.ValidatorSpec mechanismOnlySpec() {
        return new TopicValidatorRegistry.ValidatorSpec(
                "", null, 0.0, "", 0.0, "", true, "");
    }

    private static TopicValidatorRegistry.ValidatorSpec boundedSpec(double upper) {
        return new TopicValidatorRegistry.ValidatorSpec(
                "", Pattern.compile("([0-9.]+)"), upper, "MPa", 0.0, "", false, "test");
    }

    @Test
    void nonInterdisciplinaryTopicWithoutBoundSucceeds() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        r.register(new TopicValidatorRegistry.TopicKey("MATHEMATICS", "Some Topic"), emptySpec());
        assertThat(r.findFor("MATHEMATICS", "Some Topic")).isPresent();
    }

    @Test
    void interdisciplinaryTopicWithoutBoundOrMechanismThrows() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        assertThatThrownBy(() -> r.register(
                new TopicValidatorRegistry.TopicKey("INTERDISCIPLINARY", "New Cross Topic"),
                emptySpec()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERDISCIPLINARY")
                .hasMessageContaining("new cross topic");  // key.topic() is normalised lowercase
    }

    @Test
    void interdisciplinaryWithRequiresPhysicalMechanismSucceeds() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        r.register(new TopicValidatorRegistry.TopicKey("INTERDISCIPLINARY", "Mechanism Topic"),
                mechanismOnlySpec());
        assertThat(r.findFor("INTERDISCIPLINARY", "Mechanism Topic")).isPresent();
    }

    @Test
    void interdisciplinaryWithMagnitudeBoundSucceeds() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        r.register(new TopicValidatorRegistry.TopicKey("INTERDISCIPLINARY", "Bounded Topic"),
                boundedSpec(100.0));
        assertThat(r.findFor("INTERDISCIPLINARY", "Bounded Topic")).isPresent();
    }

    @Test
    void topicKeyEqualityIsCaseInsensitive() {
        TopicValidatorRegistry.TopicKey upper = new TopicValidatorRegistry.TopicKey("PHYSICS", "RLC");
        TopicValidatorRegistry.TopicKey lower = new TopicValidatorRegistry.TopicKey("physics", "rlc");
        TopicValidatorRegistry.TopicKey mixed = new TopicValidatorRegistry.TopicKey("Physics", "Rlc");
        assertThat(upper).isEqualTo(lower);
        assertThat(upper).isEqualTo(mixed);
        assertThat(upper.hashCode()).isEqualTo(lower.hashCode());
        assertThat(upper.hashCode()).isEqualTo(mixed.hashCode());
    }

    @Test
    void findForReturnsRegisteredSpecCaseInsensitively() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        TopicValidatorRegistry.ValidatorSpec spec = boundedSpec(500.0);
        r.register(new TopicValidatorRegistry.TopicKey("MATERIALS", "Foo Bar"), spec);
        assertThat(r.findFor("materials", "foo bar")).contains(spec);
        assertThat(r.findFor("MATERIALS", "FOO BAR")).contains(spec);
    }

    @Test
    void findForReturnsEmptyForUnregisteredTopic() {
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        Optional<TopicValidatorRegistry.ValidatorSpec> result = r.findFor("DYNAMIC", "Not Registered");
        assertThat(result).isEmpty();
    }

    @Test
    void reRegisteringSameKeyIsIdempotent() {
        // Documented choice: re-registration overwrites silently. This lets tests
        // swap a known-bound spec for a stub without having to first deregister.
        TopicValidatorRegistry r = new TopicValidatorRegistry();
        TopicValidatorRegistry.TopicKey key = new TopicValidatorRegistry.TopicKey("MATERIALS", "X");
        r.register(key, boundedSpec(100.0));
        r.register(key, boundedSpec(200.0));
        assertThat(r.findFor("MATERIALS", "X")).isPresent();
        assertThat(r.findFor("MATERIALS", "X").get().upperLimit()).isEqualTo(200.0);
    }

    @Test
    void defaultRegistryHasFourBoundedTopics() {
        // The 4 historical MagnitudeRangeValidator.BOUNDS entries.
        TopicValidatorRegistry r = TopicValidatorRegistry.defaultRegistry();
        assertThat(r.findFor("MATERIALS", "Composite Failure Mode"))
                .hasValueSatisfying(s -> assertThat(s.hasMagnitudeBound()).isTrue());
        assertThat(r.findFor("MATERIALS", "Arrhenius Rule Validity"))
                .hasValueSatisfying(s -> assertThat(s.hasMagnitudeBound()).isTrue());
        assertThat(r.findFor("INTERDISCIPLINARY", "Van-der-Waals Composite Crossover"))
                .hasValueSatisfying(s -> assertThat(s.hasMagnitudeBound()).isTrue());
        assertThat(r.findFor("INTERDISCIPLINARY", "Prime-Gap Defect Thermal Loading"))
                .hasValueSatisfying(s -> assertThat(s.hasMagnitudeBound()).isTrue());
    }
}
