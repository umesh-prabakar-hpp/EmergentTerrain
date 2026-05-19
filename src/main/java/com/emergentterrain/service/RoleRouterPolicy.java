package com.emergentterrain.service;

import com.emergentterrain.llm.LlmCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 20.5 — operator-tunable per-role filtering / reordering of the
 * {@link PrimaryModelRegistry} handle list. Reads {@code app.ai.role-overrides:}
 * from {@code application.yml} at startup; pairs with {@link LlmCallContext#currentRole}
 * which call sites set to identify which workload is dispatching.
 *
 * <h2>Why this is needed</h2>
 * {@link LlmRouter#orderedAvailable} sorts handles by one global list
 * ({@code app.ai.primaryProviderOrder}). That's the right answer on average
 * but wrong per-workload — when one model misbehaves on one role (e.g.,
 * Groq llama-3.3 hitting daily-token cap on professor-tools while staying
 * fine for fast-audit) the only lever today is global demotion, which
 * affects every other role too. {@code RoleRouterPolicy} lets the operator
 * say "avoid {@code groq/llama-3.3-70b-versatile} on {@code professor-tools},
 * keep it for {@code fast-audit}" via a YAML edit + restart, no code change.
 *
 * <h2>Configuration shape</h2>
 * <pre>
 * app.ai.role-overrides:
 *   fast-audit:
 *     preferred-providers: [cerebras, groq, ollama]
 *   professor-tools:
 *     preferred-providers: [groq, cerebras, nvidia]
 *     avoid-models: [groq/llama-3.3-70b-versatile]
 * </pre>
 *
 * Per-role keys:
 * <ul>
 *   <li>{@code preferred-providers} — provider keys in priority order. Handles
 *       whose providerKey appears here sort to the front in this order;
 *       handles whose provider is absent retain the global ordering and sort
 *       after. Empty list = no reordering for this role.</li>
 *   <li>{@code avoid-models} — full reviewerIds (provider/model) excluded
 *       entirely for this role. Empty list = no exclusions.</li>
 * </ul>
 *
 * <h2>Activation</h2>
 * Without {@code LlmCallContext.withRole(...)} set, the policy is a no-op
 * and the global ordering wins. Call sites opt in by wrapping the LLM
 * call in {@code withRole("fast-audit", ...)} — see {@link ResearchNarrationService}
 * for the existing pattern (Phase 7 E1.6 introduced role-based dispatch
 * via {@link LlmRouter#callForRole} but didn't extend it to the
 * registry-walking primary path; 20.5 closes that gap).
 *
 * <h2>Empty / missing config</h2>
 * Empty {@code role-overrides:} section reproduces today's behavior exactly.
 * A role appears in YAML with all-empty sub-keys = no-op for that role.
 * Pair with the {@link #activeForCurrentRole} sanity-check at startup to
 * catch misconfiguration that would leave a role with zero handles.
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class RoleRouterPolicy {

    private static final Logger log = LoggerFactory.getLogger(RoleRouterPolicy.class);

    /**
     * Spring-bound: keys are role names ({@code fast-audit}, {@code professor-tools},
     * {@code panel-review}, etc.); values carry the per-role overrides.
     * Defaults to empty map — no role configured, infrastructure is dormant.
     */
    private Map<String, RoleOverride> roleOverrides = new HashMap<>();

    public Map<String, RoleOverride> getRoleOverrides() { return roleOverrides; }
    public void setRoleOverrides(Map<String, RoleOverride> v) {
        this.roleOverrides = v == null ? new HashMap<>() : v;
    }

    /**
     * Apply the override block matching {@link LlmCallContext#currentRole()}
     * (if any) to {@code handles}: drop avoid-models, then sort preferred-providers
     * to the front. Returns a new list; the input list is unmodified.
     *
     * <p>No-ops when:
     * <ul>
     *   <li>No role is set on {@code LlmCallContext} — global ordering wins.</li>
     *   <li>The role has no entry in {@code role-overrides:} — global ordering wins.</li>
     *   <li>Both {@code preferred-providers} and {@code avoid-models} are empty —
     *       returns the input list unchanged (defensive: empty config = no-op).</li>
     * </ul>
     */
    public List<ModelHandle> applyForCurrentRole(List<ModelHandle> handles) {
        String role = LlmCallContext.currentRole();
        if (role == null || role.isEmpty()) return handles;
        RoleOverride override = roleOverrides.get(role);
        if (override == null) return handles;
        if (override.getAvoidModels().isEmpty() && override.getPreferredProviders().isEmpty()) return handles;

        List<ModelHandle> filtered = new ArrayList<>(handles.size());
        for (ModelHandle h : handles) {
            if (!override.getAvoidModels().contains(h.reviewerId())) filtered.add(h);
        }
        if (filtered.isEmpty()) {
            log.warn("RoleRouterPolicy: role={} avoid-models removed every handle from the dispatch list. "
                    + "Falling back to the unfiltered list to keep the role alive.", role);
            return handles;
        }
        if (!override.getPreferredProviders().isEmpty()) {
            List<String> order = override.getPreferredProviders();
            filtered.sort(Comparator.comparingInt(h -> {
                int idx = order.indexOf(h.providerKey());
                return idx == -1 ? Integer.MAX_VALUE : idx;
            }));
        }
        return filtered;
    }

    /**
     * Diagnostics — true when any of the configured roles is non-empty.
     * Logged once at startup by {@link LlmRouter} so the operator can see
     * the policy is loaded even when no LLM call has hit it yet.
     */
    public boolean isAnyRoleConfigured() {
        return roleOverrides.values().stream()
                .anyMatch(r -> !r.getPreferredProviders().isEmpty() || !r.getAvoidModels().isEmpty());
    }

    public static class RoleOverride {
        private List<String> preferredProviders = List.of();
        private List<String> avoidModels = List.of();

        public List<String> getPreferredProviders() { return preferredProviders; }
        public void setPreferredProviders(List<String> v) {
            this.preferredProviders = v == null ? List.of() : v;
        }
        public List<String> getAvoidModels() { return avoidModels; }
        public void setAvoidModels(List<String> v) {
            this.avoidModels = v == null ? List.of() : v;
        }
    }
}
