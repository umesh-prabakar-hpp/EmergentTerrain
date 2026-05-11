package com.emergentterrain.service;

/**
 * The three outcomes a bot can produce when asked to vote on a tile-change
 * proposal:
 * <ul>
 *   <li>{@code APPROVE} — counts toward {@code RoomProposal.approvals}.</li>
 *   <li>{@code REJECT} — counts toward {@code RoomProposal.rejections}; can
 *       be flipped to {@code APPROVE} by the configurable
 *       {@code app.consensus.botApproveBias}.</li>
 *   <li>{@code ABSTAIN} — bot has no opinion; neither counter moves.</li>
 * </ul>
 *
 * Phase 2.5 carve (RoomProposalService Phase 2): promoted from a private
 * nested enum on {@link RoomService} so {@link RoomProposalService} can
 * expose {@link RoomProposalService#botVoteDecision} on its public API.
 * The {@code shouldRelocate} bot-lifecycle check in RoomService also reads
 * the returned enum to decide whether a bot should give up on its current
 * neighbourhood, so the type lives in the {@code com.emergentterrain.service}
 * package alongside the carve targets.
 */
public enum BotVote {
    APPROVE,
    REJECT,
    ABSTAIN
}
