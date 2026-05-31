package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 30 Wave 2 (phases-for-human-like-performance/phase-30-conjecture/
 * IMPLEMENTATION-ORDER.md) — the derivation graph: findings-as-nodes
 * ({@link DerivationNode}) with typed input/output ports and DERIVES_FROM
 * provenance edges. The pivot substrate that later waves traverse:
 * <ul>
 *   <li>Wave 3 (clade-value selection) — rank by validated-descendant productivity;</li>
 *   <li>Wave 5 (compositional derivation) — port-match an output to an input and
 *       emit a child node with provenance to both parents;</li>
 *   <li>Wave 6 (RAF closure) — extract the self-supporting, food-grounded core.</li>
 * </ul>
 *
 * <p>Pure and self-contained; deterministic; not yet wired into the live
 * pipeline (later waves consume it). Round-trips through a vanilla
 * {@code ObjectMapper}.
 */
public final class DerivationGraph {

    private final Map<String, DerivationNode> nodes = new LinkedHashMap<>();

    /** Insert/replace a node (keyed by {@link DerivationNode#id}). Returns this for chaining. */
    public DerivationGraph add(DerivationNode node) {
        if (node != null) {
            nodes.put(node.id(), node);
        }
        return this;
    }

    public Optional<DerivationNode> get(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Collection<DerivationNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public int size() {
        return nodes.size();
    }

    /** Findings whose output is {@code scalar} (port-match sources for composition). */
    public List<DerivationNode> producersOf(String scalar) {
        List<DerivationNode> out = new ArrayList<>();
        for (DerivationNode n : nodes.values()) {
            if (n.output().equals(scalar)) {
                out.add(n);
            }
        }
        return out;
    }

    /** Findings that consume {@code scalar} as an input. */
    public List<DerivationNode> consumersOf(String scalar) {
        List<DerivationNode> out = new ArrayList<>();
        for (DerivationNode n : nodes.values()) {
            if (n.inputs().contains(scalar)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Transitive DERIVES_FROM ancestry of {@code id} (excludes the node itself).
     * Cycle-safe; empty for an axiom, a leaf, or an unknown id.
     */
    public Set<String> lineage(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        DerivationNode start = nodes.get(id);
        if (start != null) {
            stack.addAll(start.derivesFrom());
        }
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            DerivationNode n = nodes.get(cur);
            if (n != null) {
                stack.addAll(n.derivesFrom());
            }
        }
        return seen;
    }

    /**
     * RAF/CAF substrate (Wave 6): the node ids forward-reachable from
     * {@code food} — a node is <i>grounded</i> if every input is in {@code food}
     * or is the output of an already-grounded node. Fixpoint; deterministic.
     */
    public Set<String> groundedNodes(Set<String> food) {
        Set<String> producible = new LinkedHashSet<>(food == null ? Set.of() : food);
        Set<String> grounded = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DerivationNode n : nodes.values()) {
                if (grounded.contains(n.id())) {
                    continue;
                }
                if (producible.containsAll(n.inputs())) {
                    grounded.add(n.id());
                    producible.add(n.output());   // its output is now available
                    changed = true;
                }
            }
        }
        return grounded;
    }

    // ---- serialise / reload ----

    public String toJson(ObjectMapper om) throws JsonProcessingException {
        return om.writeValueAsString(new ArrayList<>(nodes.values()));
    }

    public static DerivationGraph fromJson(ObjectMapper om, String json) throws JsonProcessingException {
        List<DerivationNode> list = om.readValue(json, new TypeReference<List<DerivationNode>>() {});
        DerivationGraph g = new DerivationGraph();
        for (DerivationNode n : list) {
            g.add(n);
        }
        return g;
    }

    /**
     * Build the initial graph from mined inequalities: one node per conjecture,
     * keyed by {@link ConjectureTrivialityFilter#descriptorKey}, {@code output =
     * lhs}, {@code inputs = [rhs]} (empty for a {@code k=0} constant bound). No
     * DERIVES_FROM edges yet — composition (Wave 5) adds them. Conjectures that
     * share a descriptor key collapse to one node.
     */
    public static DerivationGraph fromConjectures(List<Inequality> conjectures) {
        DerivationGraph g = new DerivationGraph();
        if (conjectures == null) {
            return g;
        }
        for (Inequality ineq : conjectures) {
            if (ineq == null) {
                continue;
            }
            String id = ConjectureTrivialityFilter.descriptorKey(ineq);
            List<String> inputs = ConjectureTrivialityFilter.isDegenerateConstant(ineq)
                    ? List.of()
                    : List.of(ineq.rhs());
            g.add(new DerivationNode(id, ineq.lhs(), inputs));
        }
        return g;
    }
}
