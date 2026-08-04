package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 Iter 3 — read-side queries over the {@link PaperCitation} edge table.
 *
 * The contradiction-cluster detection is intentionally simple: any pair of
 * papers connected by a {@link RelationshipType#CONTRADICTS} edge in either
 * direction is a contradiction cluster of two. Phase 4 Iter 9 will refine this
 * by reading {@code FormalFinding} records and detecting semantic contradictions
 * across more than two papers.
 *
 * Stateless service. Safe to inject as a singleton.
 */
@Service
public class PaperGraphService {

    private final PaperCitationRepository citations;

    public PaperGraphService(PaperCitationRepository citations) {
        this.citations = citations;
    }

    public record ContradictionCluster(UUID roomId, Set<String> paperIds, int edgeCount) {}

    /** All paper IDs that {@code paperId} cites (outgoing edges). */
    public List<String> citationsOf(String paperId) {
        return citations.findByCitingPaperId(paperId).stream()
                .map(PaperCitation::getCitedPaperId)
                .toList();
    }

    /** All paper IDs that cite {@code paperId} (incoming edges). */
    public List<String> citedBy(String paperId) {
        return citations.findByCitedPaperId(paperId).stream()
                .map(PaperCitation::getCitingPaperId)
                .toList();
    }

    /** Citing papers that explicitly contradict {@code paperId}. */
    public List<String> contradictedBy(String paperId) {
        return citations.findByCitedPaperId(paperId).stream()
                .filter(c -> c.getRelationshipType() == RelationshipType.CONTRADICTS)
                .map(PaperCitation::getCitingPaperId)
                .toList();
    }

    /** Total incoming edges, across all relationship types. */
    public long citationCount(String paperId) {
        return citations.countByCitedPaperId(paperId);
    }

    /** Top-cited papers in the room, by total incoming-edge count, most-cited first. */
    public List<String> topCited(UUID roomId, int limit) {
        Map<String, Long> counts = new HashMap<>();
        for (PaperCitation c : citations.findByRoomId(roomId)) {
            counts.merge(c.getCitedPaperId(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Group all CONTRADICTS edges in the room into clusters. Two papers are in
     * the same cluster if they are joined by a CONTRADICTS edge in either
     * direction. Uses a simple union-find pass; large rooms with thousands of
     * contradictions may want a graph library, but for current per-room volumes
     * the in-memory pass is fine.
     */
    public List<ContradictionCluster> findContradictionClusters(UUID roomId) {
        List<PaperCitation> contradictions = citations
                .findByRoomIdAndRelationshipType(roomId, RelationshipType.CONTRADICTS);
        if (contradictions.isEmpty()) return List.of();

        // Pass 1: union all edges so every node ends up under its final cluster root.
        Map<String, String> parent = new HashMap<>();
        for (PaperCitation c : contradictions) {
            String a = c.getCitingPaperId();
            String b = c.getCitedPaperId();
            parent.putIfAbsent(a, a);
            parent.putIfAbsent(b, b);
            String ra = find(parent, a);
            String rb = find(parent, b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }

        // Pass 2: count edges by their final cluster root, and collect cluster members.
        Map<String, Integer> edgeCounts = new HashMap<>();
        for (PaperCitation c : contradictions) {
            edgeCounts.merge(find(parent, c.getCitingPaperId()), 1, Integer::sum);
        }
        Map<String, Set<String>> groups = new HashMap<>();
        for (String node : parent.keySet()) {
            groups.computeIfAbsent(find(parent, node), k -> new HashSet<>()).add(node);
        }
        return groups.entrySet().stream()
                .map(e -> new ContradictionCluster(roomId, Set.copyOf(e.getValue()),
                        edgeCounts.getOrDefault(e.getKey(), 0)))
                .sorted(Comparator.comparingInt(ContradictionCluster::edgeCount).reversed())
                .toList();
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!root.equals(parent.get(root))) root = parent.get(root);
        // Path compression
        String cur = x;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

}
