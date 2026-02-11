package com.emergentterrain.service;

import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomParticipantRepository;
import com.emergentterrain.repo.RoomProposalRepository;
import com.emergentterrain.repo.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class ActivityReporter {
    private static final Logger log = LoggerFactory.getLogger(ActivityReporter.class);

    private final ActivityMetrics metrics;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository participantRepository;
    private final RoomBotRepository botRepository;
    private final RoomProposalRepository proposalRepository;
    private final long intervalMs;

    public ActivityReporter(ActivityMetrics metrics,
                            RoomRepository roomRepository,
                            RoomParticipantRepository participantRepository,
                            RoomBotRepository botRepository,
                            RoomProposalRepository proposalRepository,
                            @Value("${app.activityLog.intervalMs:30000}") long intervalMs) {
        this.metrics = metrics;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.botRepository = botRepository;
        this.proposalRepository = proposalRepository;
        this.intervalMs = intervalMs;
    }

    @Scheduled(fixedDelayString = "${app.activityLog.intervalMs:30000}")
    public void logActivity() {
        Map<String, Long> deltas = metrics.snapshotDeltas();
        Map<String, Long> totals = metrics.snapshotTotals();
        long rooms = roomRepository.count();
        long participantsActive = participantRepository.countByActiveTrue();
        long bots = botRepository.count();
        long proposalsPending = proposalRepository.countByStatus("PENDING");

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        int threads = Thread.activeCount();

        log.info("activity ts={} intervalMs={} deltas={} totals={} rooms={} participantsActive={} bots={} proposalsPending={} memMb={}/{} threads={}",
            Instant.now(),
            intervalMs,
            deltas,
            totals,
            rooms,
            participantsActive,
            bots,
            proposalsPending,
            usedMb,
            maxMb,
            threads
        );
    }
}
