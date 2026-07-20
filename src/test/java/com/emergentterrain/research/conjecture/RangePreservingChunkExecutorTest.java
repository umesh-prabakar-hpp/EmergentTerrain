package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangePreservingChunkExecutorTest {

    @TempDir
    Path journalRoot;

    @Test
    void preservesA500kLogicalRangeAndAggregatesDensityByExactChunkLength() {
        RangePreservingChunkExecutor executor = new RangePreservingChunkExecutor(
                new ObjectMapper(), journalRoot, 50_000, true);
        List<Long> starts = new ArrayList<>();

        RangePreservingChunkExecutor.DensityJobResult result = executor.executeDensity(
                "abundant_density", 1_000_000_000L, 1_000_500_000L, (start, size) -> {
                    starts.add(start);
                    // Distinct values make an unweighted or missing-chunk reduction visible.
                    return new RangePreservingChunkExecutor.DensityMeasurement(
                            0.20 + ((start - 1_000_000_000L) / 50_000) * 0.001, 7, 3);
                });

        assertTrue(result.complete());
        assertEquals(1_000_000_000L, result.job().logicalStart());
        assertEquals(1_000_500_000L, result.job().logicalEnd());
        assertEquals(10, result.job().chunks().size());
        assertEquals(10, starts.size());
        for (int i = 0; i < result.job().chunks().size(); i++) {
            RangePreservingChunkExecutor.ChunkResult chunk = result.job().chunks().get(i);
            assertEquals(1_000_000_000L + i * 50_000L, chunk.start());
            assertEquals(chunk.start() + 50_000L, chunk.end());
            assertEquals(RangePreservingChunkExecutor.ChunkStatus.COMPLETE, chunk.status());
        }
        assertEquals(0.2045, result.density(), 1.0e-12);
        assertEquals(70, result.computeMs());
        assertEquals(30, result.transportMs());
    }

    @Test
    void resumesOnlyMissingChunksAfterFailure() {
        RangePreservingChunkExecutor first = new RangePreservingChunkExecutor(
                new ObjectMapper(), journalRoot, 50_000, true);
        Map<Long, Integer> calls = new HashMap<>();

        RangePreservingChunkExecutor.DensityJobResult partial = first.executeDensity(
                "abundant_density", 1_000_000_000L, 1_000_500_000L, (start, size) -> {
                    calls.merge(start, 1, Integer::sum);
                    if (start == 1_000_100_000L) {
                        throw new IllegalStateException("injected sidecar timeout");
                    }
                    return new RangePreservingChunkExecutor.DensityMeasurement(0.25, 1, 2);
                });

        assertFalse(partial.complete());
        assertEquals(RangePreservingChunkExecutor.JobStatus.PARTIAL, partial.job().status());
        assertEquals(100_000L, partial.completedLength());
        assertNotNull(partial.job().chunks().get(2).failure());

        RangePreservingChunkExecutor resumed = new RangePreservingChunkExecutor(
                new ObjectMapper(), journalRoot, 50_000, true);
        RangePreservingChunkExecutor.DensityJobResult complete = resumed.executeDensity(
                "abundant_density", 1_000_000_000L, 1_000_500_000L, (start, size) -> {
                    calls.merge(start, 1, Integer::sum);
                    return new RangePreservingChunkExecutor.DensityMeasurement(0.25, 1, 2);
                });

        assertTrue(complete.complete());
        assertEquals(500_000L, complete.completedLength());
        assertEquals(1, calls.get(1_000_000_000L));
        assertEquals(1, calls.get(1_000_050_000L));
        assertEquals(2, calls.get(1_000_100_000L));
        assertEquals(1, calls.get(1_000_450_000L));
    }
}
