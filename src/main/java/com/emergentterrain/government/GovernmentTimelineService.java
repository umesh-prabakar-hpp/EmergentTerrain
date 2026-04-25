package com.emergentterrain.government;

import com.emergentterrain.domain.GovernmentTimelineEvent;
import com.emergentterrain.domain.GovernmentTimelineSample;
import com.emergentterrain.domain.Room;
import com.emergentterrain.repo.GovernmentTimelineEventRepository;
import com.emergentterrain.repo.GovernmentTimelineSampleRepository;
import com.emergentterrain.web.GovernmentSummary;
import com.emergentterrain.web.GovernmentTrendEvent;
import com.emergentterrain.web.GovernmentTrendPoint;
import com.emergentterrain.web.GovernmentTrendResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class GovernmentTimelineService {
    private final GovernmentTimelineSampleRepository sampleRepository;
    private final GovernmentTimelineEventRepository eventRepository;
    private final GovernmentSimulationService governmentSimulationService;
    private final boolean enabled;
    private final int fullResolutionHours;
    private final int minuteBucketHours;
    private final int fiveMinuteBucketDays;
    private final int defaultWindowMinutes;
    private final int maxWindowMinutes;

    public GovernmentTimelineService(GovernmentTimelineSampleRepository sampleRepository,
                                     GovernmentTimelineEventRepository eventRepository,
                                     GovernmentSimulationService governmentSimulationService,
                                     @Value("${app.government.timeline.enabled:true}") boolean enabled,
                                     @Value("${app.government.timeline.fullResolutionHours:6}") int fullResolutionHours,
                                     @Value("${app.government.timeline.minuteBucketHours:24}") int minuteBucketHours,
                                     @Value("${app.government.timeline.fiveMinuteBucketDays:7}") int fiveMinuteBucketDays,
                                     @Value("${app.government.timeline.defaultWindowMinutes:360}") int defaultWindowMinutes,
                                     @Value("${app.government.timeline.maxWindowMinutes:21600}") int maxWindowMinutes) {
        this.sampleRepository = sampleRepository;
        this.eventRepository = eventRepository;
        this.governmentSimulationService = governmentSimulationService;
        this.enabled = enabled;
        this.fullResolutionHours = Math.max(1, fullResolutionHours);
        this.minuteBucketHours = Math.max(this.fullResolutionHours + 1, minuteBucketHours);
        this.fiveMinuteBucketDays = Math.max(1, fiveMinuteBucketDays);
        this.defaultWindowMinutes = Math.max(15, defaultWindowMinutes);
        this.maxWindowMinutes = Math.max(this.defaultWindowMinutes, maxWindowMinutes);
    }

    @Transactional
    public void capture(Room room, GovernmentState governmentState, Instant now) {
        if (!enabled || room == null || governmentState == null) {
            return;
        }
        GovernmentTimelineSample latest = sampleRepository.findTopByRoomOrderByTickDesc(room).orElse(null);
        if (latest != null && latest.getTick() >= governmentState.getTick()) {
            captureEvents(room, governmentState, now);
            return;
        }
        Instant sampledAt = governmentState.getLastTickAt() != null ? governmentState.getLastTickAt() : now;
        GovernmentSummary summary = governmentSimulationService.buildSummary(governmentState);
        GovernmentTimelineSample sample = new GovernmentTimelineSample();
        sample.setRoom(room);
        sample.setTick(governmentState.getTick());
        sample.setSampledAt(sampledAt);
        sample.setBucketStartAt(sampledAt);
        sample.setBucketMinutes(0);
        populateSample(sample, governmentState, summary);
        sampleRepository.save(sample);
        captureEvents(room, governmentState, sampledAt);
    }

    @Transactional(readOnly = true)
    public GovernmentTrendResponse getTrend(Room room, Integer requestedWindowMinutes, Instant now) {
        GovernmentTrendResponse response = new GovernmentTrendResponse();
        response.setRoomId(room.getId().toString());
        response.setGeneratedAt(now);
        int safeWindowMinutes = sanitizeWindowMinutes(requestedWindowMinutes);
        response.setWindowMinutes(safeWindowMinutes);
        if (!enabled) {
            return response;
        }
        Instant cutoff = now.minus(safeWindowMinutes, ChronoUnit.MINUTES);
        List<GovernmentTimelineSample> candidates =
            sampleRepository.findByRoomAndBucketStartAtGreaterThanEqualOrderByBucketStartAtAscBucketMinutesAsc(room, cutoff);
        List<GovernmentTimelineEvent> events =
            eventRepository.findByRoomAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(room, cutoff);
        response.setPoints(selectSamples(candidates, cutoff, now));
        response.setEvents(mapEvents(events));
        return response;
    }

    @Scheduled(fixedDelayString = "${app.government.timeline.compactionIntervalMs:300000}")
    @Transactional
    public void compactTimeline() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        compactBuckets(0, 1, truncateToBucket(now.minus(fullResolutionHours, ChronoUnit.HOURS), 1));
        compactBuckets(1, 5, truncateToBucket(now.minus(minuteBucketHours, ChronoUnit.HOURS), 5));
        compactBuckets(5, 15, truncateToBucket(now.minus(fiveMinuteBucketDays, ChronoUnit.DAYS), 15));
    }

    private void compactBuckets(int sourceBucketMinutes, int targetBucketMinutes, Instant eligibleBefore) {
        List<GovernmentTimelineSample> sourceSamples =
            sampleRepository.findByBucketMinutesAndBucketStartAtLessThanOrderByRoomIdAscBucketStartAtAsc(
                sourceBucketMinutes,
                eligibleBefore
            );
        if (sourceSamples.isEmpty()) {
            return;
        }
        Map<AggregationKey, List<GovernmentTimelineSample>> grouped = new LinkedHashMap<>();
        for (GovernmentTimelineSample sample : sourceSamples) {
            Instant targetStart = truncateToBucket(sample.getBucketStartAt(), targetBucketMinutes);
            AggregationKey key = new AggregationKey(sample.getRoom().getId(), targetStart);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample);
        }
        for (List<GovernmentTimelineSample> group : grouped.values()) {
            GovernmentTimelineSample aggregated = aggregateGroup(group, targetBucketMinutes);
            sampleRepository.save(aggregated);
        }
        int batchSize = 200;
        for (int i = 0; i < sourceSamples.size(); i += batchSize) {
            sampleRepository.deleteAllInBatch(sourceSamples.subList(i, Math.min(i + batchSize, sourceSamples.size())));
        }
    }

    private GovernmentTimelineSample aggregateGroup(List<GovernmentTimelineSample> group, int targetBucketMinutes) {
        GovernmentTimelineSample latest = group.stream()
            .max(Comparator.comparing(GovernmentTimelineSample::getSampledAt).thenComparingLong(GovernmentTimelineSample::getTick))
            .orElseThrow();
        Instant targetStart = truncateToBucket(group.get(0).getBucketStartAt(), targetBucketMinutes);
        GovernmentTimelineSample aggregate = sampleRepository
            .findByRoomAndBucketMinutesAndBucketStartAt(latest.getRoom(), targetBucketMinutes, targetStart)
            .orElseGet(GovernmentTimelineSample::new);
        aggregate.setRoom(latest.getRoom());
        aggregate.setTick(latest.getTick());
        aggregate.setSampledAt(latest.getSampledAt());
        aggregate.setBucketStartAt(targetStart);
        aggregate.setBucketMinutes(targetBucketMinutes);
        aggregate.setFederalTrust(averageDouble(group, GovernmentTimelineSample::getFederalTrust));
        aggregate.setFederalServiceAccess(averageDouble(group, GovernmentTimelineSample::getFederalServiceAccess));
        aggregate.setAvgStateTrust(averageDouble(group, GovernmentTimelineSample::getAvgStateTrust));
        aggregate.setAvgStateServiceAccess(averageDouble(group, GovernmentTimelineSample::getAvgStateServiceAccess));
        aggregate.setFederalRevenue(averageLong(group, GovernmentTimelineSample::getFederalRevenue));
        aggregate.setFederalEducation(averageLong(group, GovernmentTimelineSample::getFederalEducation));
        aggregate.setFederalHealth(averageLong(group, GovernmentTimelineSample::getFederalHealth));
        aggregate.setFederalInfrastructure(averageLong(group, GovernmentTimelineSample::getFederalInfrastructure));
        aggregate.setFederalWelfare(averageLong(group, GovernmentTimelineSample::getFederalWelfare));
        aggregate.setFederalJustice(averageLong(group, GovernmentTimelineSample::getFederalJustice));
        aggregate.setFederalPayroll(averageLong(group, GovernmentTimelineSample::getFederalPayroll));
        aggregate.setActiveBillCount(latest.getActiveBillCount());
        aggregate.setActiveExecutiveOrderCount(latest.getActiveExecutiveOrderCount());
        aggregate.setActiveCourtCaseCount(latest.getActiveCourtCaseCount());
        aggregate.setActiveAdministrativeCount(latest.getActiveAdministrativeCount());
        aggregate.setActiveConflictCount(latest.getActiveConflictCount());
        aggregate.setPressureIntensity(averageDouble(group, GovernmentTimelineSample::getPressureIntensity));
        aggregate.setImplementationScore(averageDouble(group, GovernmentTimelineSample::getImplementationScore));
        aggregate.setImplementationBackfireCount(latest.getImplementationBackfireCount());
        aggregate.setActiveBotCount(latest.getActiveBotCount());
        aggregate.setPetitioningBotCount(latest.getPetitioningBotCount());
        aggregate.setReviewBotCount(latest.getReviewBotCount());
        aggregate.setSanctionedBotCount(latest.getSanctionedBotCount());
        aggregate.setPetitioningShare(averageDouble(group, GovernmentTimelineSample::getPetitioningShare));
        aggregate.setSanctionedShare(averageDouble(group, GovernmentTimelineSample::getSanctionedShare));
        aggregate.setFederalExecutiveParty(latest.getFederalExecutiveParty());
        aggregate.setFederalLowerParty(latest.getFederalLowerParty());
        aggregate.setFederalUpperParty(latest.getFederalUpperParty());
        aggregate.setFederalChiefJusticeParty(latest.getFederalChiefJusticeParty());
        return aggregate;
    }

    private void populateSample(GovernmentTimelineSample sample,
                                GovernmentState governmentState,
                                GovernmentSummary summary) {
        GovernmentState.GovernmentNode federal = governmentState.getFederal();
        GovernmentState.BudgetState budget = federal == null ? null : federal.getBudget();
        sample.setFederalTrust(summary.getFederalTrust());
        sample.setFederalServiceAccess(federal == null ? 0.0d : federal.getServiceAccess());
        sample.setAvgStateTrust(averageStateTrust(governmentState.getStates()));
        sample.setAvgStateServiceAccess(averageStateService(governmentState.getStates()));
        sample.setFederalRevenue(budget == null ? 0L : budget.getRevenue());
        sample.setFederalEducation(budget == null ? 0L : budget.getEducation());
        sample.setFederalHealth(budget == null ? 0L : budget.getHealth());
        sample.setFederalInfrastructure(budget == null ? 0L : budget.getInfrastructure());
        sample.setFederalWelfare(budget == null ? 0L : budget.getWelfare());
        sample.setFederalJustice(budget == null ? 0L : budget.getJustice());
        sample.setFederalPayroll(budget == null ? 0L : budget.getPayroll());
        sample.setActiveBillCount(summary.getActiveBillCount());
        sample.setActiveExecutiveOrderCount(summary.getActiveExecutiveOrderCount());
        sample.setActiveCourtCaseCount(summary.getActiveCourtCaseCount());
        sample.setActiveAdministrativeCount(summary.getAdministrativeItems() == null ? 0 : summary.getAdministrativeItems().size());
        sample.setActiveConflictCount(summary.getConflictItems() == null ? 0 : summary.getConflictItems().size());
        sample.setPressureIntensity(averagePressureIntensity(governmentState.getPressureEvents()));
        sample.setImplementationScore(averageImplementationScore(governmentState.getImplementationOutcomes()));
        sample.setImplementationBackfireCount(countImplementationBackfires(governmentState.getImplementationOutcomes()));
        sample.setActiveBotCount(governmentState.getActiveBotCount());
        sample.setPetitioningBotCount(governmentState.getPetitioningBotCount());
        sample.setReviewBotCount(governmentState.getReviewBotCount());
        sample.setSanctionedBotCount(governmentState.getSanctionedBotCount());
        sample.setPetitioningShare(governmentState.getPetitioningShare());
        sample.setSanctionedShare(governmentState.getSanctionedShare());
        sample.setFederalExecutiveParty(summary.getFederalExecutiveParty());
        sample.setFederalLowerParty(summary.getFederalLowerParty());
        sample.setFederalUpperParty(summary.getFederalUpperParty());
        sample.setFederalChiefJusticeParty(summary.getFederalChiefJusticeParty());
    }

    private void captureEvents(Room room, GovernmentState governmentState, Instant defaultOccurredAt) {
        long tick = governmentState.getTick();
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            String phase = nonBlank(bill.getLawStatus()) && !"ACTIVE".equalsIgnoreCase(bill.getLawStatus())
                ? normalizeToken(bill.getStage()) + ":" + normalizeToken(bill.getLawStatus())
                : normalizeToken(bill.getStage());
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(bill.getUpdatedAt(), bill.getCreatedAt(), defaultOccurredAt),
                "bill",
                bill.getBillId(),
                phase,
                String.format(Locale.ROOT, "%s: %s", bill.getBillId(), bill.getTitle()),
                bill.getSummary(),
                humanizeToken(bill.getScope()),
                summarizeList("Changes", bill.getWorldEffects())
            );
        }
        for (GovernmentState.ExecutiveOrder order : governmentState.getExecutiveOrders()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(order.getIssuedAt(), defaultOccurredAt),
                "order",
                order.getOrderId(),
                normalizeToken(order.getStatus()),
                String.format(Locale.ROOT, "Order %s", order.getOrderId()),
                order.getSummary(),
                humanizeToken(order.getScope()),
                summarizeList("Touches", order.getAffectedSystems())
            );
        }
        for (GovernmentState.CourtCase courtCase : governmentState.getCourtCases()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(courtCase.getDecidedAt(), defaultOccurredAt),
                "court",
                courtCase.getCaseId(),
                courtCase.isBlockedAction() ? "blocked" : "upheld",
                String.format(Locale.ROOT, "Court: %s", courtCase.getCaseId()),
                courtCase.getDecision(),
                humanizeToken(courtCase.getCourtLevel()),
                summarizeList("Affected", courtCase.getAffectedLaws())
            );
        }
        for (GovernmentState.ElectionRecord election : governmentState.getElections()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(election.getHeldAt(), defaultOccurredAt),
                "election",
                election.getElectionId(),
                normalizeToken(election.getWinningPartyId()),
                String.format(Locale.ROOT, "Election %s", election.getElectionId()),
                election.getSummary(),
                humanizeToken(election.getScope()),
                null
            );
        }
        for (GovernmentState.AdministrativeAction action : governmentState.getAdministrativeActions()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(action.getUpdatedAt(), action.getCreatedAt(), defaultOccurredAt),
                "administrative",
                action.getActionId(),
                normalizeToken(action.getStage()),
                nonBlank(action.getTitle()) ? action.getTitle() : "Administrative action",
                action.getSummary(),
                humanizeToken(action.getScope()),
                action.isAppealed() ? "Administrative appeal" : null
            );
        }
        for (GovernmentState.PoliticalPressure pressure : governmentState.getPressureEvents()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(pressure.getCreatedAt(), defaultOccurredAt),
                "pressure",
                pressure.getEventId(),
                normalizeToken(pressure.getPressureType()),
                "Pressure event " + pressure.getEventId(),
                pressure.getSummary(),
                null,
                pressure.getEffect()
            );
        }
        for (GovernmentState.FederalStateConflict conflict : governmentState.getConflicts()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(conflict.getResolvedAt(), conflict.getCreatedAt(), defaultOccurredAt),
                "conflict",
                conflict.getConflictId(),
                normalizeToken(conflict.getStatus()),
                "Conflict " + conflict.getConflictId(),
                conflict.getSummary(),
                "State",
                humanizeToken(conflict.getConflictType())
            );
        }
        for (GovernmentState.ImplementationOutcome outcome : governmentState.getImplementationOutcomes()) {
            saveEventIfAbsent(
                room,
                tick,
                coalesceInstant(outcome.getMeasuredAt(), defaultOccurredAt),
                "implementation",
                outcome.getOutcomeId(),
                normalizeToken(outcome.getStatus()),
                nonBlank(outcome.getTitle()) ? outcome.getTitle() : "Implementation outcome",
                outcome.getSummary(),
                outcome.getJurisdictionId(),
                summarizeList("Adverse effects", outcome.getAdverseEffects())
            );
        }
    }

    private void saveEventIfAbsent(Room room,
                                   long tick,
                                   Instant occurredAt,
                                   String kind,
                                   String referenceId,
                                   String phase,
                                   String title,
                                   String summary,
                                   String scope,
                                   String impact) {
        String safeReferenceId = truncate(nonBlank(referenceId) ? referenceId : kind + "-" + tick, 128);
        String safePhase = truncate(nonBlank(phase) ? phase : "recorded", 128);
        if (eventRepository.existsByRoomAndKindAndReferenceIdAndPhase(room, kind, safeReferenceId, safePhase)) {
            return;
        }
        GovernmentTimelineEvent event = new GovernmentTimelineEvent();
        event.setRoom(room);
        event.setTick(tick);
        event.setOccurredAt(occurredAt);
        event.setKind(kind);
        event.setReferenceId(safeReferenceId);
        event.setPhase(safePhase);
        event.setTitle(truncate(nonBlank(title) ? title : kind, 255));
        event.setSummary(summary);
        event.setScope(truncate(scope, 64));
        event.setImpact(impact);
        eventRepository.save(event);
    }

    private List<GovernmentTrendPoint> selectSamples(List<GovernmentTimelineSample> candidates, Instant cutoff, Instant now) {
        Instant fullCutoff = now.minus(fullResolutionHours, ChronoUnit.HOURS);
        Instant minuteCutoff = now.minus(minuteBucketHours, ChronoUnit.HOURS);
        Instant fiveMinuteCutoff = now.minus(fiveMinuteBucketDays, ChronoUnit.DAYS);
        List<GovernmentTimelineSample> recent = new ArrayList<>();
        List<GovernmentTimelineSample> hourly = new ArrayList<>();
        List<GovernmentTimelineSample> daily = new ArrayList<>();
        List<GovernmentTimelineSample> archive = new ArrayList<>();
        for (GovernmentTimelineSample sample : candidates) {
            if (sample.getBucketStartAt().isBefore(cutoff)) {
                continue;
            }
            if (!sample.getBucketStartAt().isBefore(fullCutoff)) {
                recent.add(sample);
            } else if (!sample.getBucketStartAt().isBefore(minuteCutoff)) {
                hourly.add(sample);
            } else if (!sample.getBucketStartAt().isBefore(fiveMinuteCutoff)) {
                daily.add(sample);
            } else {
                archive.add(sample);
            }
        }
        List<GovernmentTimelineSample> selected = new ArrayList<>();
        selected.addAll(selectBand(recent, List.of(0, 1, 5, 15), 0));
        selected.addAll(selectBand(hourly, List.of(1, 0, 5, 15), 1));
        selected.addAll(selectBand(daily, List.of(5, 1, 0, 15), 5));
        selected.addAll(selectBand(archive, List.of(15, 5, 1, 0), 15));
        selected.sort(Comparator.comparing(GovernmentTimelineSample::getBucketStartAt).thenComparingInt(GovernmentTimelineSample::getBucketMinutes));
        List<GovernmentTrendPoint> points = new ArrayList<>(selected.size());
        for (GovernmentTimelineSample sample : selected) {
            points.add(mapPoint(sample));
        }
        return points;
    }

    private List<GovernmentTimelineSample> selectBand(List<GovernmentTimelineSample> candidates,
                                                      List<Integer> preferredResolutions,
                                                      int targetBucketMinutes) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Instant, GovernmentTimelineSample> selected = new TreeMap<>();
        for (GovernmentTimelineSample sample : candidates) {
            Instant key = targetBucketMinutes <= 0 ? sample.getBucketStartAt() : truncateToBucket(sample.getBucketStartAt(), targetBucketMinutes);
            GovernmentTimelineSample current = selected.get(key);
            if (current == null || isBetterCandidate(sample, current, preferredResolutions)) {
                selected.put(key, sample);
            }
        }
        return new ArrayList<>(selected.values());
    }

    private boolean isBetterCandidate(GovernmentTimelineSample candidate,
                                      GovernmentTimelineSample current,
                                      List<Integer> preferredResolutions) {
        int candidateScore = resolutionPreference(candidate.getBucketMinutes(), preferredResolutions);
        int currentScore = resolutionPreference(current.getBucketMinutes(), preferredResolutions);
        if (candidateScore != currentScore) {
            return candidateScore < currentScore;
        }
        if (!candidate.getSampledAt().equals(current.getSampledAt())) {
            return candidate.getSampledAt().isAfter(current.getSampledAt());
        }
        return candidate.getTick() > current.getTick();
    }

    private int resolutionPreference(int bucketMinutes, List<Integer> preferredResolutions) {
        int index = preferredResolutions.indexOf(bucketMinutes);
        return index >= 0 ? index : preferredResolutions.size();
    }

    private GovernmentTrendPoint mapPoint(GovernmentTimelineSample sample) {
        GovernmentTrendPoint point = new GovernmentTrendPoint();
        point.setTick(sample.getTick());
        point.setBucketStartAt(sample.getBucketStartAt());
        point.setSampledAt(sample.getSampledAt());
        point.setResolutionMinutes(sample.getBucketMinutes());
        point.setFederalTrust(sample.getFederalTrust());
        point.setFederalServiceAccess(sample.getFederalServiceAccess());
        point.setAvgStateTrust(sample.getAvgStateTrust());
        point.setAvgStateServiceAccess(sample.getAvgStateServiceAccess());
        point.setRevenue(sample.getFederalRevenue());
        point.setEducation(sample.getFederalEducation());
        point.setHealth(sample.getFederalHealth());
        point.setInfrastructure(sample.getFederalInfrastructure());
        point.setWelfare(sample.getFederalWelfare());
        point.setJustice(sample.getFederalJustice());
        point.setPayroll(sample.getFederalPayroll());
        point.setActiveBillCount(sample.getActiveBillCount());
        point.setActiveExecutiveOrderCount(sample.getActiveExecutiveOrderCount());
        point.setActiveCourtCaseCount(sample.getActiveCourtCaseCount());
        point.setActiveAdministrativeCount(sample.getActiveAdministrativeCount());
        point.setActiveConflictCount(sample.getActiveConflictCount());
        point.setPressureIntensity(sample.getPressureIntensity());
        point.setImplementationScore(sample.getImplementationScore());
        point.setImplementationBackfireCount(sample.getImplementationBackfireCount());
        point.setActiveBotCount(sample.getActiveBotCount());
        point.setPetitioningBotCount(sample.getPetitioningBotCount());
        point.setReviewBotCount(sample.getReviewBotCount());
        point.setSanctionedBotCount(sample.getSanctionedBotCount());
        point.setPetitioningShare(sample.getPetitioningShare());
        point.setSanctionedShare(sample.getSanctionedShare());
        point.setFederalExecutiveParty(sample.getFederalExecutiveParty());
        point.setFederalLowerParty(sample.getFederalLowerParty());
        point.setFederalUpperParty(sample.getFederalUpperParty());
        point.setFederalChiefJusticeParty(sample.getFederalChiefJusticeParty());
        return point;
    }

    private List<GovernmentTrendEvent> mapEvents(List<GovernmentTimelineEvent> events) {
        List<GovernmentTrendEvent> items = new ArrayList<>(events.size());
        for (GovernmentTimelineEvent event : events) {
            GovernmentTrendEvent item = new GovernmentTrendEvent();
            item.setTick(event.getTick());
            item.setOccurredAt(event.getOccurredAt());
            item.setKind(event.getKind());
            item.setReferenceId(event.getReferenceId());
            item.setPhase(event.getPhase());
            item.setTitle(event.getTitle());
            item.setSummary(event.getSummary());
            item.setScope(event.getScope());
            item.setImpact(event.getImpact());
            items.add(item);
        }
        return items;
    }

    private int sanitizeWindowMinutes(Integer requestedWindowMinutes) {
        if (requestedWindowMinutes == null) {
            return defaultWindowMinutes;
        }
        return Math.max(15, Math.min(maxWindowMinutes, requestedWindowMinutes));
    }

    private double averageStateTrust(List<GovernmentState.GovernmentNode> states) {
        if (states == null || states.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentState.GovernmentNode state : states) {
            total += state.getPublicTrust();
        }
        return total / (double) states.size();
    }

    private double averageStateService(List<GovernmentState.GovernmentNode> states) {
        if (states == null || states.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentState.GovernmentNode state : states) {
            total += state.getServiceAccess();
        }
        return total / (double) states.size();
    }

    private double averagePressureIntensity(List<GovernmentState.PoliticalPressure> pressureEvents) {
        if (pressureEvents == null || pressureEvents.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentState.PoliticalPressure pressure : pressureEvents) {
            total += pressure.getIntensity();
        }
        return total / (double) pressureEvents.size();
    }

    private double averageImplementationScore(List<GovernmentState.ImplementationOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentState.ImplementationOutcome outcome : outcomes) {
            total += outcome.getOutcomeScore();
        }
        return total / (double) outcomes.size();
    }

    private int countImplementationBackfires(List<GovernmentState.ImplementationOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (GovernmentState.ImplementationOutcome outcome : outcomes) {
            if ("BACKFIRE".equalsIgnoreCase(outcome.getStatus())) {
                total++;
            }
        }
        return total;
    }

    private double averageDouble(Collection<GovernmentTimelineSample> samples, DoubleAccessor accessor) {
        if (samples.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentTimelineSample sample : samples) {
            total += accessor.read(sample);
        }
        return total / (double) samples.size();
    }

    private long averageLong(Collection<GovernmentTimelineSample> samples, LongAccessor accessor) {
        if (samples.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (GovernmentTimelineSample sample : samples) {
            total += accessor.read(sample);
        }
        return Math.round((double) total / (double) samples.size());
    }

    private Instant truncateToBucket(Instant instant, int bucketMinutes) {
        if (bucketMinutes <= 0) {
            return instant;
        }
        long bucketMs = bucketMinutes * 60_000L;
        long epochMs = instant.toEpochMilli();
        return Instant.ofEpochMilli((epochMs / bucketMs) * bucketMs);
    }

    private Instant coalesceInstant(Instant primary, Instant secondary, Instant fallback) {
        if (primary != null) {
            return primary;
        }
        if (secondary != null) {
            return secondary;
        }
        return fallback;
    }

    private Instant coalesceInstant(Instant primary, Instant fallback) {
        return primary != null ? primary : fallback;
    }

    private String summarizeList(String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", items.subList(0, Math.min(3, items.size())));
        if (items.size() > 3) {
            joined += ", +" + (items.size() - 3) + " more";
        }
        return label + ": " + joined;
    }

    private String humanizeToken(String value) {
        if (!nonBlank(value)) {
            return null;
        }
        String[] parts = value.trim().replace('-', ' ').replace('_', ' ').split("\\s+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            words.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return words.isEmpty() ? null : String.join(" ", words);
    }

    private String normalizeToken(String value) {
        if (!nonBlank(value)) {
            return "recorded";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record AggregationKey(UUID roomId, Instant bucketStartAt) {
    }

    @FunctionalInterface
    private interface DoubleAccessor {
        double read(GovernmentTimelineSample sample);
    }

    @FunctionalInterface
    private interface LongAccessor {
        long read(GovernmentTimelineSample sample);
    }
}
