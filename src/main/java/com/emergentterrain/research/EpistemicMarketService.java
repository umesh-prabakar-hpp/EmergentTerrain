package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.BotCalibrationRepository;
import com.emergentterrain.repo.EpistemicMarketRepository;
import com.emergentterrain.repo.MarketStakeRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 Iter 7 — reputation-staked prediction markets on PreRegistration outcomes.
 *
 * When a paper publishes carrying a falsifiable PreRegistration prediction, an
 * EpistemicMarket opens. Other bots can place bounded reputation stakes on
 * CONFIRMS or REFUTES. When a later paper in the same (room, domain, topic)
 * resolves the market, winners gain reputation proportional to their stake and
 * losers lose theirs, and per-bot {@link BotCalibration} stats accumulate so
 * Phase 3 Iter 8's FundingAgency can prefer well-calibrated bots in grant
 * allocation.
 *
 * Currency: reputation. The phase doc's "wager compute budget / grant funding"
 * is deferred until economic currency exists.
 *
 * Gated by {@code app.research.markets.enabled} (default false). All public
 * entry points become no-ops when the flag is off.
 *
 * Bounded staking: stakes are capped at {@link #MAX_STAKE_FRACTION} of the
 * staker's current reputation so a single bad bet can't destroy a researcher.
 */
@Service
public class EpistemicMarketService {

    private static final Logger log = LoggerFactory.getLogger(EpistemicMarketService.class);

    /** Max fraction of current reputation a single stake can use. */
    private static final double MAX_STAKE_FRACTION = 0.20;

    /** How long a market stays OPEN before stakes are locked. */
    private static final Duration MARKET_WINDOW = Duration.ofMinutes(5);

    /**
     * Surprise-score threshold for resolution. A resolving paper with surprise
     * &lt; this value confirms the prediction (CONFIRMS wins); &gt;= this value
     * refutes it (REFUTES wins). Matches the ExpectationModelService threshold.
     */
    private static final double SURPRISE_CONFIRMS_BELOW = 2.0;

    /**
     * Multiplier on stake amount when the bot wins/loses. A win returns +stake*PAYOUT_MULTIPLIER
     * to reputation; a loss subtracts stake. Small bias toward staking activity.
     */
    private static final double PAYOUT_MULTIPLIER = 1.0;

    private final boolean enabled;
    private final EpistemicMarketRepository markets;
    private final MarketStakeRepository     stakes;
    private final BotCalibrationRepository  calibrations;
    private final RoomBotRepository         botRepository;
    private final ObjectMapper              objectMapper;

    public EpistemicMarketService(
            @Value("${app.research.markets.enabled:false}") boolean enabled,
            EpistemicMarketRepository markets,
            MarketStakeRepository stakes,
            BotCalibrationRepository calibrations,
            RoomBotRepository botRepository,
            ObjectMapper objectMapper) {
        this.enabled       = enabled;
        this.markets       = markets;
        this.stakes        = stakes;
        this.calibrations  = calibrations;
        this.botRepository = botRepository;
        this.objectMapper  = objectMapper;
        if (enabled) {
            log.info("EpistemicMarketService: ENABLED — prediction markets open on "
                    + "papers with PreRegistration; stakes use reputation as currency.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Open a market for a paper that carries a falsifiable prediction. Returns
     * the persisted market, or empty when:
     *   - the markets flag is off,
     *   - the paper has no PreRegistration or the prediction is blank,
     *   - a market for this hypothesis paper already exists (idempotent).
     */
    public Optional<EpistemicMarket> openMarketFor(ResearchPaper paper, PreRegistration preReg) {
        if (!enabled || paper == null) return Optional.empty();
        if (preReg == null || preReg.prediction() == null || preReg.prediction().isBlank()) {
            return Optional.empty();
        }
        Optional<EpistemicMarket> existing = markets.findFirstByHypothesisPaperId(paper.paperId());
        if (existing.isPresent()) return existing;

        Instant now = Instant.now();
        EpistemicMarket m = new EpistemicMarket(
                paper.paperId(), paper.roomId(), paper.domain(), paper.topic(),
                now, now.plus(MARKET_WINDOW));
        try {
            return Optional.of(markets.save(m));
        } catch (Exception e) {
            log.warn("market open failed paperId={}: {}", paper.paperId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Place a reputation stake on an open market. The amount is computed as a
     * fraction of the bot's current reputation, capped at {@link #MAX_STAKE_FRACTION}.
     * No-op when the market is closed / resolved or the bot has zero reputation.
     *
     * Returns the persisted stake, or empty when the stake was rejected.
     */
    public Optional<MarketStake> placeStake(UUID marketId, UUID botId,
                                            EpistemicMarket.StakeSide side,
                                            double reputationFraction) {
        if (!enabled) return Optional.empty();
        EpistemicMarket market = markets.findById(marketId).orElse(null);
        if (market == null || market.getStatus() != EpistemicMarket.Status.OPEN) return Optional.empty();
        if (Instant.now().isAfter(market.getClosesAt())) return Optional.empty();

        RoomBot bot = botRepository.findById(botId).orElse(null);
        if (bot == null) return Optional.empty();
        BotLifecycleState ls = readLifecycleState(bot);
        if (ls == null) return Optional.empty();
        double currentRep = ls.getReputation();
        if (currentRep <= 0.0) return Optional.empty();

        double clampedFraction = Math.max(0.0, Math.min(MAX_STAKE_FRACTION, reputationFraction));
        double amount = currentRep * clampedFraction;
        if (amount <= 0.0) return Optional.empty();

        MarketStake stake = new MarketStake(marketId, botId, side, amount, Instant.now());
        try {
            return Optional.of(stakes.save(stake));
        } catch (Exception e) {
            log.warn("market stake placement failed market={} bot={}: {}", marketId, botId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolve any markets settled by the resolving paper's outcome. A market
     * resolves when a later paper publishes in the same (room, domain, topic)
     * AFTER the market's closesAt. The surpriseScore on the resolving paper
     * determines whether CONFIRMS or REFUTES wins.
     *
     * Called from UniversityService.publishPaper. Returns the number of markets
     * settled by this resolving paper (0 when the flag is off, when no markets
     * are pending in this slot, or when the resolving paper itself is the one
     * that opened the market — author-self-resolution is filtered).
     */
    public int maybeResolveMarketsFor(ResearchPaper resolvingPaper, double surpriseScore) {
        if (!enabled || resolvingPaper == null) return 0;
        Instant now = Instant.now();

        List<EpistemicMarket> open = markets.findByRoomIdAndDomainAndStatus(
                resolvingPaper.roomId(), resolvingPaper.domain(), EpistemicMarket.Status.OPEN);
        int resolved = 0;
        for (EpistemicMarket m : open) {
            // Don't resolve a market with its own opening paper.
            if (resolvingPaper.paperId().equals(m.getHypothesisPaperId())) continue;
            // Must match topic and be past the close window.
            if (!resolvingPaper.topic().equalsIgnoreCase(m.getTopic())) continue;
            if (now.isBefore(m.getClosesAt())) continue;

            EpistemicMarket.StakeSide winner = surpriseScore < SURPRISE_CONFIRMS_BELOW
                    ? EpistemicMarket.StakeSide.CONFIRMS
                    : EpistemicMarket.StakeSide.REFUTES;
            settleMarket(m, winner, resolvingPaper.paperId(), now);
            resolved++;
        }
        return resolved;
    }

    /** Calibration score for a bot in [0,1]. Returns 0.5 (neutral) for unknown bots. */
    public double calibrationScore(UUID botId) {
        if (botId == null) return 0.5;
        return calibrations.findByBotId(botId)
                .map(BotCalibration::calibrationScore).orElse(0.5);
    }

    // ---- internal ----

    private void settleMarket(EpistemicMarket market, EpistemicMarket.StakeSide winner,
                               String resolvingPaperId, Instant at) {
        List<MarketStake> all = stakes.findByMarketId(market.getId());
        for (MarketStake s : all) {
            boolean won = s.getSide() == winner;
            s.setWonOnResolution(won);
            applyReputationOutcome(s.getBotId(), s.getReputationStaked(), won, at);
            try { stakes.save(s); }
            catch (Exception e) {
                log.warn("market stake settle save failed id={}: {}", s.getId(), e.getMessage());
            }
        }

        market.setStatus(EpistemicMarket.Status.RESOLVED);
        market.setResolvedAt(at);
        market.setResolvedSide(winner);
        market.setResolvingPaperId(resolvingPaperId);
        try { markets.save(market); }
        catch (Exception e) {
            log.warn("market resolve save failed id={}: {}", market.getId(), e.getMessage());
        }
        log.info("market RESOLVED id={} winner={} resolvedBy={} stakes={}",
                market.getId(), winner, resolvingPaperId, all.size());
    }

    private void applyReputationOutcome(UUID botId, double stake, boolean won, Instant at) {
        botRepository.findById(botId).ifPresent(bot -> {
            BotLifecycleState ls = readLifecycleState(bot);
            if (ls == null) return;
            double delta = won ? +stake * PAYOUT_MULTIPLIER : -stake;
            ls.setReputation(Math.max(0.0, Math.min(1.0, ls.getReputation() + delta)));
            try {
                bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                botRepository.save(bot);
            } catch (Exception e) {
                log.warn("market reputation update failed bot={}: {}", botId, e.getMessage());
            }
        });

        BotCalibration cal = calibrations.findByBotId(botId)
                .orElseGet(() -> new BotCalibration(botId, at));
        if (won) cal.recordWin(at); else cal.recordLoss(at);
        try { calibrations.save(cal); }
        catch (Exception e) {
            log.warn("calibration save failed bot={}: {}", botId, e.getMessage());
        }
    }

    private BotLifecycleState readLifecycleState(RoomBot bot) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, BotLifecycleState.class);
        } catch (Exception e) {
            log.warn("lifecycle parse failed bot={}: {}", bot.getId(), e.getMessage());
            return null;
        }
    }
}
