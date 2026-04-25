const token = window.APP_TOKEN;
const canvas = document.getElementById('world');
const ctx = canvas.getContext('2d');
const worldFxCanvas = document.getElementById('worldFx');
const rendererStatusEl = document.getElementById('rendererStatus');
const rendererStatusTitleEl = document.getElementById('rendererStatusTitle');
const rendererStatusDetailEl = document.getElementById('rendererStatusDetail');
const rendererStatusMetaEl = document.getElementById('rendererStatusMeta');
const miniMap = document.getElementById('miniMap');
const miniMapContainer = document.getElementById('miniMapContainer');
const miniCtx = null;
const miniMapToggle = document.getElementById('toggleMiniMap');
const pollOverlay = document.getElementById('pollOverlay');
const infoPanel = document.getElementById('infoPanel');
const infoToggle = document.getElementById('toggleInfoPanel');
const explanationEl = document.getElementById('explanation');
const tradeoffPanel = document.getElementById('tradeoffPanel');
const radialMenu = document.getElementById('radialMenu');
let tileHighlightEl = null;
const toggleTokensBtn = document.getElementById('toggleTokens');
const toggleQuestsBtn = document.getElementById('toggleQuests');
const toggleMilestonesBtn = document.getElementById('toggleMilestones');
const toggleSnapshotsBtn = document.getElementById('toggleSnapshots');
const toggleMultiplayerBtn = document.getElementById('toggleMultiplayer');
const toggleBotsBtn = document.getElementById('toggleBots');
const tokensPanel = document.getElementById('tokensPanel');
const questsPanel = document.getElementById('questsPanel');
const milestonesPanel = document.getElementById('milestonesPanel');
const snapshotsPanel = document.getElementById('snapshotsPanel');
const multiplayerPanel = document.getElementById('multiplayerPanel');
const botsPanel = document.getElementById('botsPanel');
const botPromptEl = document.getElementById('botPrompt');
const roomNameInput = document.getElementById('roomName');
const roomIdInput = document.getElementById('roomIdInput');
const createRoomBtn = document.getElementById('createRoomBtn');
const joinRoomBtn = document.getElementById('joinRoomBtn');
const roomStatus = document.getElementById('roomStatus');
const participantsList = document.getElementById('participantsList');
const proposalsList = document.getElementById('proposalsList');
const localProposalsList = document.getElementById('localProposalsList');
const pollDetails = document.getElementById('pollDetails');
const lockStatusEl = document.getElementById('lockStatus');
const missedVotesEl = document.getElementById('missedVotes');
const governmentSummaryEl = document.getElementById('governmentSummary');
const governmentBillsEl = document.getElementById('governmentBills');
const governmentPartiesEl = document.getElementById('governmentParties');
const pollResultsList = document.getElementById('pollResultsList');
const togglePollResultsBtn = document.getElementById('togglePollResults');
const routeChoicesEl = document.getElementById('routeChoices');
const neighborStats = document.getElementById('neighborStats');
const proposeRoomBtn = document.getElementById('proposeRoomBtn');
const approveProposalBtn = document.getElementById('approveProposalBtn');
const rejectProposalBtn = document.getElementById('rejectProposalBtn');
const applyProposalBtn = document.getElementById('applyProposalBtn');
const sharedTokensEl = document.getElementById('sharedTokens');
const sharedQuestsList = document.getElementById('sharedQuestsList');
const memoryLabelInput = document.getElementById('memoryLabel');
const dropMemoryBtn = document.getElementById('dropMemoryBtn');
const memoryList = document.getElementById('memoryList');
const botProfilesList = document.getElementById('botProfilesList');

let tiles = [];
let highways = [];
let width = 0;
let height = 0;
let tileSize = 16;
let viewWidth = 50;
let viewHeight = 38;
let viewX = 0;
let viewY = 0;
let miniTarget = null;
let autoNavTarget = null;
let autoNavTimer = null;
let autoNavInFlight = false;
let autoNavInFlightCount = 0;
let autoNavPath = null;
let autoNavIndex = 0;
let autoNavSentIndex = 0;
let autoNavPredictedIndex = 0;
let autoNavLastPredictAt = 0;
let pendingRoutes = null;
let roomId = '';
let participantId = '';
let roomState = null;
let participants = [];
let bots = [];
let pendingChange = null;
let pendingAnchor = null;
let radialMenuIgnoreUntil = 0;
let player = { x: 0, y: 0 };
let playerTarget = { x: 0, y: 0 };
let playerRender = { x: 0, y: 0 };
let sceneAnimFrame = null;
let lastSceneAnimTs = 0;
let botRenderState = new Map();
let tileTransitions = new Map();
let worldFxRenderer = null;
let proposalVisuals = new Map();
let resolvedProposalBursts = [];
let playerLockedUntil = 0;
let selectedProposalId = '';
const dismissedPolls = new Set();
let rendererStatus = {
  code: 'booting',
  level: 'warn',
  message: 'Starting WebGPU renderer...',
  detail: 'Waiting for adapter and world data.',
  worldWidth: 0,
  worldHeight: 0,
  tileCount: 0,
  proposalCount: 0,
  actorCount: 0,
  frameCount: 0,
  lastFrameAt: 0
};
let ws = null;
let wsRoomId = null;
let wsReconnectTimer = null;
let refreshInFlight = false;
let refreshQueued = false;
const debugMinimap = new URLSearchParams(window.location.search).has('debugMinimap');

let tool = 'forest';
const AUTO_NAV_MAX_IN_FLIGHT = 2;
const AUTO_NAV_MAX_STEPS = 4;
const AUTO_NAV_PREDICT_INTERVAL_MS = 160;
const PLAYER_INTERPOLATION_MS = 120;
const BOT_INTERPOLATION_MS = 150;
const TILE_TRANSITION_MS = 180;
const PROPOSAL_BURST_RETENTION_MS = 1400;

function formatRendererMeta(status) {
  const parts = [];
  if (status.worldWidth && status.worldHeight) {
    parts.push(`${status.worldWidth}x${status.worldHeight}`);
  }
  if (status.tileCount) {
    parts.push(`${status.tileCount} cells`);
  }
  if (status.actorCount || status.actorCount === 0) {
    parts.push(`${status.actorCount} actors`);
  }
  if (status.proposalCount || status.proposalCount === 0) {
    parts.push(`${status.proposalCount} proposals`);
  }
  if (status.frameCount) {
    parts.push(`${status.frameCount} frames`);
  }
  if (status.lastFrameAt) {
    parts.push(`last ${Math.max(0, Math.round((Date.now() - status.lastFrameAt) / 1000))}s ago`);
  }
  return parts.join(' · ');
}

function renderRendererStatus() {
  if (!rendererStatusEl) return;
  const level = rendererStatus.level || 'warn';
  rendererStatusEl.classList.remove('is-ready', 'is-warn', 'is-error');
  rendererStatusEl.classList.add(level === 'error' ? 'is-error' : level === 'ready' ? 'is-ready' : 'is-warn');
  if (rendererStatusTitleEl) {
    rendererStatusTitleEl.textContent = rendererStatus.message || 'WebGPU renderer status unknown.';
  }
  if (rendererStatusDetailEl) {
    rendererStatusDetailEl.textContent = rendererStatus.detail || 'No renderer details available.';
  }
  if (rendererStatusMetaEl) {
    rendererStatusMetaEl.textContent = formatRendererMeta(rendererStatus);
  }
}

function updateRendererStatus(next) {
  if (!next) return;
  rendererStatus = {
    ...rendererStatus,
    ...next
  };
  renderRendererStatus();
}

const panelMap = {
  tokens: tokensPanel,
  quests: questsPanel,
  milestones: milestonesPanel,
  snapshots: snapshotsPanel,
  multiplayer: multiplayerPanel,
  bots: botsPanel
};

function parseTimeMs(value) {
  if (!value) return 0;
  const parsed = new Date(value).getTime();
  return Number.isFinite(parsed) ? parsed : 0;
}

function phaseForProposal(proposal) {
  const status = proposal && proposal.status ? proposal.status : 'PENDING';
  if (status === 'APPLIED') return { stage: 4, label: 'committed', statusCode: 1 };
  if (status === 'REJECTED') return { stage: 1, label: 'collapsed', statusCode: 2 };
  const approvals = proposal && Number.isFinite(proposal.approvals) ? proposal.approvals : 0;
  const rejections = proposal && Number.isFinite(proposal.rejections) ? proposal.rejections : 0;
  const requiredVotes = Math.max(1, proposal && Number.isFinite(proposal.requiredVotes) ? proposal.requiredVotes : approvals + rejections || 1);
  const supportRatio = approvals / requiredVotes;
  const balance = approvals - rejections;
  if (balance <= 0) return { stage: 1, label: 'contested', statusCode: 0 };
  if (supportRatio < 0.55) return { stage: 2, label: 'leaning', statusCode: 0 };
  if (supportRatio < 0.9) return { stage: 3, label: 'nearly accepted', statusCode: 0 };
  return { stage: 4, label: 'committed', statusCode: 0 };
}

function proposalSupport(proposal) {
  const approvals = proposal && Number.isFinite(proposal.approvals) ? proposal.approvals : 0;
  const rejections = proposal && Number.isFinite(proposal.rejections) ? proposal.rejections : 0;
  const botYes = proposal && Number.isFinite(proposal.botYes) ? proposal.botYes : 0;
  const botNo = proposal && Number.isFinite(proposal.botNo) ? proposal.botNo : 0;
  const yes = approvals + botYes * 0.25;
  const no = rejections + botNo * 0.25;
  const total = Math.max(1, yes + no);
  return Math.max(0.05, Math.min(1, yes / total));
}

function buildProposalVisual(proposal, resolvedAtMs = 0) {
  const phase = phaseForProposal(proposal || {});
  const createdAtMs = parseTimeMs(proposal && proposal.createdAt);
  const expiresAtMs = parseTimeMs(proposal && proposal.expiresAt);
  const neighborCount = proposal && Number.isFinite(proposal.neighborCount) ? proposal.neighborCount : 0;
  return {
    id: proposal && proposal.id ? proposal.id : `burst-${Math.random().toString(36).slice(2)}`,
    anchorX: proposal && Number.isFinite(proposal.anchorX) ? proposal.anchorX : 0,
    anchorY: proposal && Number.isFinite(proposal.anchorY) ? proposal.anchorY : 0,
    tileType: proposal && proposal.tileType !== undefined ? proposal.tileType : 0,
    stage: phase.stage,
    stageLabel: phase.label,
    support: proposalSupport(proposal || {}),
    createdAtMs,
    expiresAtMs,
    resolvedAtMs,
    status: proposal && proposal.status ? proposal.status : 'PENDING',
    statusCode: phase.statusCode,
    radius: Math.max(5, 4 + neighborCount * 0.35 + phase.stage * 1.8),
    waveStrength: phase.statusCode === 0 ? 0.85 : 1.15
  };
}

function proposalStageLabel(proposal) {
  const visual = proposal && proposal.id ? proposalVisuals.get(proposal.id) : null;
  if (visual && visual.stageLabel) {
    return visual.stageLabel;
  }
  return phaseForProposal(proposal || {}).label;
}

function upsertRoomProposal(proposal) {
  if (!roomState) return;
  const proposals = Array.isArray(roomState.proposals) ? roomState.proposals.slice() : [];
  const index = proposals.findIndex((item) => item.id === proposal.id);
  if (index >= 0) {
    proposals[index] = { ...proposals[index], ...proposal };
  } else {
    proposals.unshift(proposal);
  }
  proposals.sort((left, right) => parseTimeMs(right.createdAt) - parseTimeMs(left.createdAt));
  roomState.proposals = proposals.slice(0, 10);
}

function syncProposalVisuals(nextProposals) {
  const nextMap = new Map();
  (nextProposals || []).forEach((proposal) => {
    const existing = proposalVisuals.get(proposal.id);
    const visual = buildProposalVisual(proposal, existing ? existing.resolvedAtMs : 0);
    nextMap.set(proposal.id, visual);
    if (existing && existing.status === 'PENDING' && visual.status !== 'PENDING') {
      resolvedProposalBursts.push(visual);
    }
  });
  proposalVisuals = nextMap;
  if (proposalVisuals.size || resolvedProposalBursts.length) {
    ensureSceneAnimation();
  }
}

function handleProposalSocketEvent(type, proposal) {
  if (!proposal || !proposal.id) return;
  upsertRoomProposal(proposal);
  const resolvedAtMs = type === 'proposal_resolved' ? Date.now() : 0;
  proposalVisuals.set(proposal.id, buildProposalVisual(proposal, resolvedAtMs));
  if (type === 'proposal_resolved') {
    resolvedProposalBursts.push(buildProposalVisual(proposal, resolvedAtMs));
  }
  renderRoomState();
  ensureSceneAnimation();
}

function applyTileTransitionEvent(transition) {
  if (!transition) return;
  const key = tileTransitionKey(transition.x, transition.y);
  tileTransitions.set(key, {
    prevTile: transition.fromTile,
    nextTile: transition.toTile,
    prevHighway: !!transition.fromHighway,
    nextHighway: !!transition.toHighway,
    startTs: performance.now(),
    durationMs: transition.durationMs || 900,
    phase: transition.phase || 'committed'
  });
}

function applyTileTransitionProgressEvent(transition) {
  if (!transition) return;
  const key = tileTransitionKey(transition.x, transition.y);
  const durationMs = transition.durationMs || 900;
  tileTransitions.set(key, {
    prevTile: transition.fromTile,
    nextTile: transition.toTile,
    prevHighway: !!transition.fromHighway,
    nextHighway: !!transition.toHighway,
    startTs: performance.now() - (transition.progress || 0) * durationMs,
    durationMs,
    phase: transition.phase || 'committed'
  });
}

function handleTileTransitionSocketEvent(transitions, type) {
  const apply = type === 'tile_transition_progress' ? applyTileTransitionProgressEvent : applyTileTransitionEvent;
  (transitions || []).forEach(apply);
  if ((transitions || []).length) {
    ensureSceneAnimation();
  }
}

function pruneResolvedProposalBursts(nowMs) {
  const before = resolvedProposalBursts.length;
  resolvedProposalBursts = resolvedProposalBursts.filter((proposal) => nowMs - proposal.resolvedAtMs < PROPOSAL_BURST_RETENTION_MS);
  return before !== resolvedProposalBursts.length;
}

function hasLiveProposalVisuals() {
  if (resolvedProposalBursts.length) return true;
  for (const proposal of proposalVisuals.values()) {
    if (proposal.status === 'PENDING') {
      return true;
    }
  }
  return false;
}

function buildRendererProposals() {
  const proposals = [];
  proposalVisuals.forEach((proposal) => {
    if (proposal.status === 'PENDING') {
      proposals.push(proposal);
    }
  });
  resolvedProposalBursts.forEach((proposal) => {
    proposals.push(proposal);
  });
  return proposals;
}

function buildRendererActors() {
  const actors = [];
  participants.forEach((participant) => {
    if (!participant || !Number.isFinite(participant.x) || !Number.isFinite(participant.y) || participant.x < 0 || participant.y < 0) {
      return;
    }
    const isSelf = participant.id === participantId;
    const px = isSelf && Number.isFinite(playerRender.x) ? playerRender.x : participant.x;
    const py = isSelf && Number.isFinite(playerRender.y) ? playerRender.y : participant.y;
    actors.push({
      x: px,
      y: py,
      radius: isSelf ? 0.5 : 0.46,
      color: isSelf ? [1.0, 0.7, 0.12, 0.96] : [1.0, 0.82, 0.5, 0.82],
      glow: isSelf ? 1.0 : 0.42
    });
  });
  if (!participants.length && Number.isFinite(player.x) && Number.isFinite(player.y) && player.x >= 0 && player.y >= 0) {
    actors.push({
      x: Number.isFinite(playerRender.x) ? playerRender.x : player.x,
      y: Number.isFinite(playerRender.y) ? playerRender.y : player.y,
      radius: 0.5,
      color: [1.0, 0.7, 0.12, 0.96],
      glow: 1.0
    });
  }
  bots.forEach((bot, index) => {
    if (!bot || !Number.isFinite(bot.x) || !Number.isFinite(bot.y) || bot.x < 0 || bot.y < 0) {
      return;
    }
    const render = botRenderState.get(botKey(bot, index));
    actors.push({
      x: render && Number.isFinite(render.x) ? render.x : bot.x,
      y: render && Number.isFinite(render.y) ? render.y : bot.y,
      radius: 0.4,
      color: [1.0, 0.42, 0.34, 0.82],
      glow: 0.28
    });
  });
  return actors;
}

async function initWorldFxRenderer() {
  if (!worldFxCanvas || !window.WorldFxRenderer) return;
  updateRendererStatus({
    code: 'initializing',
    level: 'warn',
    message: 'Starting WebGPU renderer...',
    detail: 'Requesting adapter and configuring GPU canvases.'
  });
  try {
    worldFxRenderer = await window.WorldFxRenderer.create(worldFxCanvas, miniMap, {
      onStatus: updateRendererStatus
    });
  } catch (err) {
    worldFxRenderer = null;
    const detail = err && err.message ? err.message : String(err);
    console.error('WebGPU renderer failed to start:', err);
    updateRendererStatus({
      code: 'init_failed',
      level: 'error',
      message: 'WebGPU renderer failed to start.',
      detail
    });
    if (roomStatus) {
      roomStatus.textContent = `Renderer error: ${detail}`;
    }
  }
}

function renderWorldFx(ts = performance.now()) {
  if (!worldFxRenderer) {
    updateRendererStatus({
      code: 'renderer_missing',
      level: 'error',
      message: 'WebGPU renderer unavailable.',
      detail: 'The renderer was not created, so no world frame can be drawn.'
    });
    return;
  }
  if (!worldFxRenderer.ready) {
    updateRendererStatus({
      code: 'renderer_not_ready',
      level: rendererStatus.level === 'error' ? 'error' : 'warn',
      message: 'WebGPU renderer is not ready yet.',
      detail: rendererStatus.detail || 'Waiting for WebGPU initialization to complete.'
    });
    return;
  }
  worldFxRenderer.render({
    nowMs: ts,
    tiles,
    highways,
    tileSize,
    viewX,
    viewY,
    viewWidth,
    viewHeight,
    worldWidth: width,
    worldHeight: height,
    transitions: tileTransitions,
    proposals: buildRendererProposals(),
    actors: buildRendererActors(),
    player: Number.isFinite(playerRender.x) && Number.isFinite(playerRender.y) ? playerRender : player,
    miniTarget,
    pendingChange,
    pendingRoutes
  });
  if (typeof worldFxRenderer.getDiagnostics === 'function') {
    updateRendererStatus(worldFxRenderer.getDiagnostics());
  }
}

function copyTiles(src) {
  return src.map(row => row.slice());
}

function toolToTile(toolName) {
  if (toolName === 'forest') return 1;
  if (toolName === 'water') return 2;
  if (toolName === 'industry') return 3;
  if (toolName === 'highway') return 4;
  return 0;
}

function applyChangeToTiles(src, change) {
  const out = copyTiles(src);
  if (!change) return out;
  const x0 = Math.max(0, Math.min(width - 1, change.x));
  const y0 = Math.max(0, Math.min(height - 1, change.y));
  const type = toolToTile(change.type);
  out[y0][x0] = type;
  return out;
}

function ensurePlayerRender() {
  if (!Number.isFinite(playerRender.x) || !Number.isFinite(playerRender.y)) {
    playerRender = { x: player.x || 0, y: player.y || 0 };
  }
  if (!Number.isFinite(playerTarget.x) || !Number.isFinite(playerTarget.y)) {
    playerTarget = { x: playerRender.x, y: playerRender.y };
  }
}

function setPlayerTarget(x, y) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return;
  ensurePlayerRender();
  playerTarget = { x, y };
  ensureSceneAnimation();
}

function stepTowards(current, target, dt, smoothingMs) {
  const alpha = 1 - Math.exp(-dt / smoothingMs);
  return current + (target - current) * alpha;
}

function tileTransitionKey(x, y) {
  return `${x}:${y}`;
}

function botKey(bot, index) {
  if (bot && bot.id) return String(bot.id);
  if (bot && bot.botId) return String(bot.botId);
  if (bot && bot.displayName) return `name:${bot.displayName}:${index}`;
  return `index:${index}`;
}

function ensureSceneAnimation() {
  if (sceneAnimFrame !== null) return;
  lastSceneAnimTs = 0;
  sceneAnimFrame = requestAnimationFrame(stepSceneAnimation);
}

function animatePlayer(dt) {
  ensurePlayerRender();
  playerRender.x = stepTowards(playerRender.x, playerTarget.x, dt, PLAYER_INTERPOLATION_MS);
  playerRender.y = stepTowards(playerRender.y, playerTarget.y, dt, PLAYER_INTERPOLATION_MS);
  const dx = playerTarget.x - playerRender.x;
  const dy = playerTarget.y - playerRender.y;
  if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) {
    playerRender.x = playerTarget.x;
    playerRender.y = playerTarget.y;
    return false;
  }
  return true;
}

function animateBots(dt) {
  let active = false;
  botRenderState.forEach((state) => {
    state.x = stepTowards(state.x, state.targetX, dt, BOT_INTERPOLATION_MS);
    state.y = stepTowards(state.y, state.targetY, dt, BOT_INTERPOLATION_MS);
    const dx = state.targetX - state.x;
    const dy = state.targetY - state.y;
    if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) {
      state.x = state.targetX;
      state.y = state.targetY;
      return;
    }
    active = true;
  });
  return active;
}

function pruneTileTransitions(ts) {
  let active = false;
  tileTransitions.forEach((transition, key) => {
    const durationMs = transition.durationMs || TILE_TRANSITION_MS;
    if (ts - transition.startTs >= durationMs) {
      tileTransitions.delete(key);
      return;
    }
    active = true;
  });
  return active;
}

function stepSceneAnimation(ts) {
  if (!sceneAnimFrame) return;
  if (!lastSceneAnimTs) {
    lastSceneAnimTs = ts;
  }
  const dt = Math.max(1, ts - lastSceneAnimTs);
  lastSceneAnimTs = ts;
  const playerActive = animatePlayer(dt);
  const botActive = animateBots(dt);
  const tileActive = pruneTileTransitions(ts);
  const burstPruned = pruneResolvedProposalBursts(ts);
  const proposalActive = hasLiveProposalVisuals();
  draw(ts);
  if (playerActive || botActive || tileActive || proposalActive || burstPruned) {
    sceneAnimFrame = requestAnimationFrame(stepSceneAnimation);
    return;
  }
  sceneAnimFrame = null;
  lastSceneAnimTs = 0;
}

function terrainColor(tileValue) {
  if (tileValue === 2) return '#4aa3df';
  if (tileValue === 1) return '#2a7f62';
  if (tileValue === 3) return '#6b6f7a';
  if (tileValue === 4) return '#c39b6b';
  return '#e2c28b';
}

function drawTerrainCell(cellX, cellY, tileValue, highwayValue, alpha = 1) {
  if (alpha <= 0) return;
  const px = (cellX - viewX) * tileSize;
  const py = (cellY - viewY) * tileSize;
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.fillStyle = terrainColor(tileValue);
  ctx.fillRect(px, py, tileSize, tileSize);
  if (highwayValue || tileValue === 4) {
    ctx.fillStyle = 'rgba(131, 95, 60, 0.85)';
    ctx.fillRect(px, py, tileSize, tileSize);
    ctx.strokeStyle = 'rgba(245, 235, 220, 0.65)';
    ctx.lineWidth = Math.max(1, Math.floor(tileSize * 0.06));
    const cx = px + tileSize / 2;
    const cy = py + tileSize / 2;
    ctx.beginPath();
    ctx.moveTo(cx - tileSize * 0.18, cy);
    ctx.lineTo(cx + tileSize * 0.18, cy);
    ctx.stroke();
  }
  ctx.restore();
}

function captureTileTransitions(nextTiles, nextHighways) {
  if (!tiles.length || !nextTiles || !nextTiles.length) {
    tileTransitions.clear();
    return;
  }
  const nextWidth = nextTiles[0] ? nextTiles[0].length : 0;
  const nextHeight = nextTiles.length || 0;
  if (nextWidth !== width || nextHeight !== height) {
    tileTransitions.clear();
    return;
  }
  const startedAt = performance.now();
  const nextTransitions = new Map();
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const prevTile = tiles[y] ? tiles[y][x] : 0;
      const nextTile = nextTiles[y] ? nextTiles[y][x] : 0;
      const prevHighway = !!(highways[y] && highways[y][x]);
      const nextHighway = !!(nextHighways && nextHighways[y] && nextHighways[y][x]);
      if (prevTile === nextTile && prevHighway === nextHighway) {
        continue;
      }
      nextTransitions.set(tileTransitionKey(x, y), {
        prevTile,
        nextTile,
        prevHighway,
        nextHighway,
        startTs: startedAt
      });
    }
  }
  tileTransitions = nextTransitions;
  if (tileTransitions.size) {
    ensureSceneAnimation();
  }
}

function syncBotRenderState(nextBots) {
  const nextState = new Map();
  let shouldAnimate = false;
  (nextBots || []).forEach((bot, index) => {
    const key = botKey(bot, index);
    const targetX = Number.isFinite(bot.x) ? bot.x : -1;
    const targetY = Number.isFinite(bot.y) ? bot.y : -1;
    const existing = botRenderState.get(key);
    const state = existing
      ? { ...existing, targetX, targetY }
      : { x: targetX, y: targetY, targetX, targetY };
    if (existing && (Math.abs(existing.targetX - targetX) > 0.01 || Math.abs(existing.targetY - targetY) > 0.01)) {
      shouldAnimate = true;
    }
    nextState.set(key, state);
  });
  botRenderState = nextState;
  if (shouldAnimate) {
    ensureSceneAnimation();
  }
}

function draw(ts = performance.now()) {
  if (!tiles.length) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  renderPollOverlay();
  renderWorldFx(ts);
}

function gridFromEvent(evt) {
  const rect = canvas.getBoundingClientRect();
  const x = Math.floor((evt.clientX - rect.left) / tileSize) + viewX;
  const y = Math.floor((evt.clientY - rect.top) / tileSize) + viewY;
  return { x, y };
}

function setPendingChange(x, y, selectedTool) {
  if (isLocked()) return;
  if (x < 0 || x >= width || y < 0 || y >= height) return;
  const x0 = Math.max(0, Math.min(width - 1, x));
  const y0 = Math.max(0, Math.min(height - 1, y));
  const nextTool = selectedTool || tool;
  pendingChange = { x: x0, y: y0, type: nextTool };
  explanationEl.textContent = `Propose 1x1 ${nextTool} tile at (${x0}, ${y0}).`;
  if (pollDetails) {
    pollDetails.textContent = `Poll ready: 1x1 ${nextTool} tile at (${x0}, ${y0}).`;
  }
  updateLockUI();
  draw();
}

function updateViewCenter() {
  if (!width || !height) return;
  const cx = player && player.x !== undefined ? player.x : Math.floor(width / 2);
  const cy = player && player.y !== undefined ? player.y : Math.floor(height / 2);
  viewX = Math.max(0, Math.min(width - viewWidth, cx - Math.floor(viewWidth / 2)));
  viewY = Math.max(0, Math.min(height - viewHeight, cy - Math.floor(viewHeight / 2)));
}

function resizeViewport() {
  if (!canvas) return;
  const parent = canvas.parentElement;
  if (!parent) return;
  const w = Math.max(320, parent.clientWidth || 0);
  const h = Math.max(240, parent.clientHeight || 0);
  const desired = 14;
  viewWidth = Math.max(12, Math.min(width || 48, Math.floor(w / desired)));
  viewHeight = Math.max(10, Math.min(height || 36, Math.floor(h / desired)));
  tileSize = Math.max(4, Math.floor(Math.min(w / viewWidth, h / viewHeight)));
  const logicalWidth = viewWidth * tileSize;
  const logicalHeight = viewHeight * tileSize;
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  canvas.style.width = `${logicalWidth}px`;
  canvas.style.height = `${logicalHeight}px`;
  canvas.width = Math.max(1, Math.round(logicalWidth * dpr));
  canvas.height = Math.max(1, Math.round(logicalHeight * dpr));
  if (worldFxCanvas) {
    worldFxCanvas.style.width = `${logicalWidth}px`;
    worldFxCanvas.style.height = `${logicalHeight}px`;
    worldFxCanvas.width = canvas.width;
    worldFxCanvas.height = canvas.height;
  }
  if (worldFxRenderer && typeof worldFxRenderer.resize === 'function') {
    worldFxRenderer.resize();
  }
  updateViewCenter();
}
function drawMiniMap() {
  if (!miniCtx || !miniMap || !tiles.length) return;
  const mw = miniMap.width;
  const mh = miniMap.height;
  const sx = mw / width;
  const sy = mh / height;
  miniCtx.clearRect(0, 0, mw, mh);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = tiles[y][x];
      if (v === 2) {
        miniCtx.fillStyle = '#4aa3df';
      } else if (v === 1) {
        miniCtx.fillStyle = '#2a7f62';
      } else if (v === 3) {
        miniCtx.fillStyle = '#6b6f7a';
      } else if (v === 4) {
        miniCtx.fillStyle = '#c39b6b';
      } else {
        miniCtx.fillStyle = '#e2c28b';
      }
      miniCtx.fillRect(Math.floor(x * sx), Math.floor(y * sy), Math.ceil(sx), Math.ceil(sy));
    }
  }

  miniCtx.strokeStyle = 'rgba(255, 255, 255, 0.9)';
  miniCtx.lineWidth = 2;
  miniCtx.strokeRect(viewX * sx, viewY * sy, viewWidth * sx, viewHeight * sy);

  if (player && player.x >= 0 && player.y >= 0) {
    const px = Number.isFinite(playerRender.x) ? playerRender.x : player.x;
    const py = Number.isFinite(playerRender.y) ? playerRender.y : player.y;
    miniCtx.fillStyle = '#ffb300';
    miniCtx.beginPath();
    miniCtx.arc(px * sx, py * sy, 3, 0, Math.PI * 2);
    miniCtx.fill();
  }

  if (miniTarget) {
    miniCtx.strokeStyle = 'rgba(255, 255, 255, 0.9)';
    miniCtx.lineWidth = 1;
    const tx = miniTarget.x * sx;
    const ty = miniTarget.y * sy;
    miniCtx.beginPath();
    miniCtx.moveTo(tx - 5, ty);
    miniCtx.lineTo(tx + 5, ty);
    miniCtx.moveTo(tx, ty - 5);
    miniCtx.lineTo(tx, ty + 5);
    miniCtx.stroke();
  }

  if (pendingRoutes) {
    const drawPath = (path) => {
      if (!path || path.length < 2) return;
      const baseWidth = Math.max(2, Math.floor(Math.min(mw, mh) * 0.02));
      miniCtx.lineCap = 'round';
      miniCtx.lineJoin = 'round';
      miniCtx.strokeStyle = 'rgba(15, 64, 34, 0.95)';
      miniCtx.lineWidth = baseWidth + 2;
      miniCtx.beginPath();
      miniCtx.moveTo(path[0].x * sx, path[0].y * sy);
      for (let i = 1; i < path.length; i++) {
        miniCtx.lineTo(path[i].x * sx, path[i].y * sy);
      }
      miniCtx.stroke();
      miniCtx.strokeStyle = 'rgba(120, 200, 96, 0.98)';
      miniCtx.lineWidth = baseWidth;
      miniCtx.beginPath();
      miniCtx.moveTo(path[0].x * sx, path[0].y * sy);
      for (let i = 1; i < path.length; i++) {
        miniCtx.lineTo(path[i].x * sx, path[i].y * sy);
      }
      miniCtx.stroke();
    };
    drawPath(pendingRoutes.road);
  }
}

function drawAxisLabels() {
  if (!tiles.length) return;
  const step = Math.max(1, Math.ceil(32 / tileSize));
  const fontSize = Math.max(10, Math.floor(tileSize * 0.65));
  const xEnd = viewX + viewWidth;
  const yEnd = viewY + viewHeight;
  ctx.save();
  ctx.font = `${fontSize}px Roboto, "Segoe UI", sans-serif`;
  ctx.fillStyle = 'rgba(20, 20, 20, 0.85)';
  ctx.shadowColor = 'rgba(255, 255, 255, 0.75)';
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = 0;
  const yText = Math.max(12, Math.floor(tileSize * 0.8));
  for (let x = viewX; x < xEnd; x += step) {
    const screenX = (x - viewX) * tileSize + 2;
    ctx.fillText(String(x), screenX, yText);
  }
  const xText = 2;
  for (let y = viewY; y < yEnd; y += step) {
    const screenY = (y - viewY) * tileSize + yText;
    ctx.fillText(String(y), xText, screenY);
  }
  ctx.restore();
}

function ensureTileHighlight() {
  if (tileHighlightEl) return;
  const wrap = canvas ? (canvas.parentElement || canvas) : null;
  if (!wrap) return;
  tileHighlightEl = document.createElement('div');
  tileHighlightEl.id = 'tileHighlight';
  tileHighlightEl.className = 'tile-highlight';
  tileHighlightEl.addEventListener('animationend', () => {
    tileHighlightEl.classList.remove('blink');
    tileHighlightEl.style.opacity = '0';
  });
  wrap.appendChild(tileHighlightEl);
}

function blinkTileAt(x, y) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return;
  if (x < viewX || x >= viewX + viewWidth || y < viewY || y >= viewY + viewHeight) return;
  ensureTileHighlight();
  if (!tileHighlightEl) return;
  const size = Math.max(tileSize * 2.6, 36);
  tileHighlightEl.style.width = `${size}px`;
  tileHighlightEl.style.height = `${size}px`;
  tileHighlightEl.style.left = `${(x - viewX + 0.5) * tileSize}px`;
  tileHighlightEl.style.top = `${(y - viewY + 0.5) * tileSize}px`;
  tileHighlightEl.classList.remove('blink');
  // force reflow so the animation restarts
  void tileHighlightEl.offsetWidth;
  tileHighlightEl.style.opacity = '1';
  tileHighlightEl.classList.add('blink');
}

if (miniMap) {
  miniMap.addEventListener('click', (evt) => {
    const rect = miniMap.getBoundingClientRect();
    const x = Math.floor((evt.clientX - rect.left) * (width / rect.width));
    const y = Math.floor((evt.clientY - rect.top) * (height / rect.height));
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    miniTarget = {
      x: Math.max(0, Math.min(width - 1, x)),
      y: Math.max(0, Math.min(height - 1, y))
    };
    const sx = player.x;
    const sy = player.y;
    const tx = miniTarget.x;
    const ty = miniTarget.y;
    const roadResult = buildRoadBiasedPath(sx, sy, tx, ty);
    pendingRoutes = {
      road: roadResult.path
    };
    if (debugMinimap) {
      const len = pendingRoutes.road ? pendingRoutes.road.length : 0;
      window.__minimapRouteLength = len;
      const px = player && Number.isFinite(player.x) ? player.x : 'n/a';
      const py = player && Number.isFinite(player.y) ? player.y : 'n/a';
      const first = len ? `${pendingRoutes.road[0].x},${pendingRoutes.road[0].y}` : 'n/a';
      const last = len ? `${pendingRoutes.road[len - 1].x},${pendingRoutes.road[len - 1].y}` : 'n/a';
      const cost = roadResult && Number.isFinite(roadResult.cost) ? roadResult.cost.toFixed(2) : 'n/a';
      const plain = buildRoadPath(sx, sy, tx, ty);
      const plainLen = plain && plain.path ? plain.path.length : 0;
      const plainFirst = plainLen ? `${plain.path[0].x},${plain.path[0].y}` : 'n/a';
      const plainLast = plainLen ? `${plain.path[plainLen - 1].x},${plain.path[plainLen - 1].y}` : 'n/a';
      const plainCost = plain && Number.isFinite(plain.cost) ? plain.cost.toFixed(2) : 'n/a';
      const startTile = tiles[sy] ? tiles[sy][sx] : 'n/a';
      const targetTile = tiles[ty] ? tiles[ty][tx] : 'n/a';
      const tileW = tiles[0] ? tiles[0].length : 0;
      const tileH = tiles.length;
      console.log(`[minimap] route length=${len} target=(${tx},${ty}) player=(${px},${py}) size=(${width},${height}) tiles=(${tileW},${tileH}) startTile=${startTile} targetTile=${targetTile} first=(${first}) last=(${last}) cost=${cost} plainLen=${plainLen} plainFirst=(${plainFirst}) plainLast=(${plainLast}) plainCost=${plainCost}`);
    }
    renderRouteChoices(null, roadResult.path, roadResult.cost);
    draw();
  });
}

if (miniMapToggle && miniMap) {
  miniMapToggle.addEventListener('click', () => {
    if (miniMapContainer) {
      miniMapContainer.classList.toggle('collapsed');
    } else {
      miniMap.classList.toggle('collapsed');
    }
  });
}

if (infoToggle && infoPanel) {
  infoToggle.addEventListener('click', () => {
    infoPanel.classList.toggle('open');
    document.body.classList.toggle('drawer-open', infoPanel.classList.contains('open'));
    resizeViewport();
    draw();
  });
}

  if (canvas) {
    canvas.addEventListener('mousedown', (evt) => {
      if (evt.button !== 0) return;
      if (isLocked()) {
        tradeoffPanel.textContent = 'You are locked from play for a short time.';
        return;
      }
      const { x, y } = gridFromEvent(evt);
      if (x < 0 || x >= width || y < 0 || y >= height) return;
      pendingAnchor = { x, y };
      openRadialMenu(evt);
      evt.stopPropagation();
    });
    canvas.addEventListener('click', (evt) => {
      evt.stopPropagation();
    });
  }

for (const btn of document.querySelectorAll('[data-tool]')) {
  btn.addEventListener('click', () => {
    tool = btn.dataset.tool;
  });
}

function togglePanel(name) {
  const panel = panelMap[name];
  if (!panel) return;
  const isActive = panel.classList.contains('active');
  Object.values(panelMap).forEach(p => p && p.classList.remove('active'));
  if (!isActive) {
    panel.classList.add('active');
  }
}

if (toggleTokensBtn) toggleTokensBtn.addEventListener('click', () => togglePanel('tokens'));
if (toggleQuestsBtn) toggleQuestsBtn.addEventListener('click', () => togglePanel('quests'));
if (toggleMilestonesBtn) toggleMilestonesBtn.addEventListener('click', () => togglePanel('milestones'));
if (toggleSnapshotsBtn) toggleSnapshotsBtn.addEventListener('click', () => togglePanel('snapshots'));
if (toggleMultiplayerBtn) toggleMultiplayerBtn.addEventListener('click', () => togglePanel('multiplayer'));
if (toggleBotsBtn) toggleBotsBtn.addEventListener('click', () => togglePanel('bots'));

for (const btn of document.querySelectorAll('[data-close]')) {
  btn.addEventListener('click', () => {
    const target = btn.getAttribute('data-close');
    const panel = document.getElementById(target);
    if (panel) panel.classList.remove('active');
  });
}

function isRoomLocked() {
  if (!roomState || !roomState.lockUntil) return false;
  return Date.now() < new Date(roomState.lockUntil).getTime();
}

function isLocked() {
  return isRoomLocked() || (playerLockedUntil && Date.now() < playerLockedUntil);
}

function updateLockUI() {
  const locked = isLocked();
  if (proposeRoomBtn) proposeRoomBtn.disabled = locked || !pendingChange;
  if (approveProposalBtn) approveProposalBtn.disabled = locked || !selectedProposalId;
  if (rejectProposalBtn) rejectProposalBtn.disabled = locked || !selectedProposalId;
  if (applyProposalBtn) applyProposalBtn.disabled = true;
  if (locked) {
    tradeoffPanel.textContent = lockStatusEl && lockStatusEl.textContent
      ? `You are locked from play for a short time. ${lockStatusEl.textContent}`
      : 'You are locked from play for a short time.';
  } else if (tradeoffPanel.textContent && tradeoffPanel.textContent.startsWith('You are locked from play')) {
    tradeoffPanel.textContent = '';
  }
}

function renderLockStatus() {
  if (!lockStatusEl || !missedVotesEl) return;
  const now = Date.now();
  let lockLines = [];
  if (roomState && roomState.lockUntil) {
    const until = new Date(roomState.lockUntil).getTime();
    if (until > now) {
      const remaining = Math.ceil((until - now) / 1000);
      lockLines.push(`Room lock: consensus applying (${remaining}s)`);
    }
  }
  if (playerLockedUntil && playerLockedUntil > now) {
    const remaining = Math.ceil((playerLockedUntil - now) / 1000);
    lockLines.push(`Player lock: missed votes (${remaining}s)`);
  }
  lockStatusEl.textContent = lockLines.length ? lockLines.join(' · ') : 'No lock active.';
  const me = participants.find(p => p.id === participantId);
  missedVotesEl.textContent = `Missed votes: ${me ? me.missedVotes || 0 : 0}`;
}

function setRoomStateFromResponse(data) {
  roomState = data;
  const nextTiles = data.tiles || null;
  const nextHighways = data.highways || highways || [];
  if (data.roomId) {
    roomId = data.roomId;
    if (roomIdInput) roomIdInput.value = roomId;
  }
  ensureWebSocket();
  if (nextTiles) {
    captureTileTransitions(nextTiles, nextHighways);
    tiles = nextTiles;
    const nextWidth = tiles[0] ? tiles[0].length : 0;
    const nextHeight = tiles.length || 0;
    const sizeChanged = nextWidth !== width || nextHeight !== height;
    width = nextWidth;
    height = nextHeight;
    if (sizeChanged || !viewWidth || !viewHeight) {
      resizeViewport();
    }
    pendingChange = null;
    if (pollDetails) {
      pollDetails.textContent = 'Select a 1x1 area to start a poll.';
    }
  }
  highways = nextHighways;
  participants = data.participants || [];
  syncBotRenderState(data.bots || []);
  bots = data.bots || [];
  syncProposalVisuals(data.proposals || []);
  dismissedPolls.clear();
  (data.dismissedPollIds || []).forEach(id => dismissedPolls.add(id));
  const me = participants.find(p => p.id === participantId);
  if (me) {
    player = { x: me.x, y: me.y };
    playerLockedUntil = me.lockedUntil ? new Date(me.lockedUntil).getTime() : 0;
  } else if (width && height) {
    player = { x: Math.floor(width / 2), y: Math.floor(height / 2) };
  }
  setPlayerTarget(player.x, player.y);
  reconcileAutoNavFromServer();
  updateViewCenter();
  renderRoomState();
  if (roomState) {
    roomStatus.textContent = `${roomState.roomName} - Negotiation active`;
  }
  updateLockUI();
  renderLockStatus();
  draw();
}

function ensureWebSocket() {
  if (!roomId) return;
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    if (ws.readyState === WebSocket.OPEN && wsRoomId !== roomId) {
      ws.send(JSON.stringify({ type: 'subscribe', roomId }));
      wsRoomId = roomId;
    }
    return;
  }
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${protocol}://${window.location.host}/ws/room`);
  wsRoomId = roomId;
  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'subscribe', roomId }));
  };
  ws.onmessage = (evt) => {
    try {
      const msg = JSON.parse(evt.data);
      if (msg.roomId !== roomId) {
        return;
      }
      if (msg.type === 'proposal_created' || msg.type === 'proposal_vote_delta' || msg.type === 'proposal_resolved') {
        handleProposalSocketEvent(msg.type, msg.proposal);
        return;
      }
      if (msg.type === 'tile_transition_started' || msg.type === 'tile_transition_progress') {
        handleTileTransitionSocketEvent(msg.transitions || [], msg.type);
        return;
      }
      if (msg.type === 'refresh') {
        queueRoomRefresh();
      }
    } catch (e) {
      // ignore bad messages
    }
  };
  ws.onclose = () => {
    if (wsReconnectTimer) return;
    wsReconnectTimer = setTimeout(() => {
      wsReconnectTimer = null;
      ensureWebSocket();
    }, 2000);
  };
}

function queueRoomRefresh() {
  if (refreshInFlight) {
    refreshQueued = true;
    return;
  }
  refreshInFlight = true;
  refreshRoomState().finally(() => {
    refreshInFlight = false;
    if (refreshQueued) {
      refreshQueued = false;
      queueRoomRefresh();
    }
  });
}

function renderRoomState() {
  renderNeighborStats();
  renderParticipants();
  renderBotProfiles();
  renderGovernmentSummary();
  renderLocalProposals();
  renderLockStatus();
  renderPollResults();
  renderRouteChoices();
  renderPollOverlay();
  renderProposals();
  renderSharedTokens();
  renderSharedQuests();
  renderMemoryMarkers();
}

function renderRouteChoices(plainPath, roadPath, roadCost) {
  if (!routeChoicesEl) return;
  routeChoicesEl.innerHTML = '';
  const road = roadPath || (pendingRoutes ? pendingRoutes.road : null);
  if (!road || !road.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'Tap the minimap to choose a route.';
    routeChoicesEl.appendChild(empty);
    return;
  }
  const roadSteps = Math.max(0, road.length - 1);
  const roadTime = roadCost !== undefined && Number.isFinite(roadCost) ? roadCost : roadSteps;
  const row = document.createElement('div');
  row.className = 'list-item';

  const roadBtn = document.createElement('button');
  roadBtn.className = 'fallback-button';
  roadBtn.textContent = `Road-biased (${roadSteps} steps, ${roadTime.toFixed(1)} time)`;
  roadBtn.addEventListener('click', () => beginAutoNav(road));

  row.appendChild(roadBtn);
  routeChoicesEl.appendChild(row);
}

function renderNeighborStats() {
  if (!neighborStats || !roomState) return;
  neighborStats.innerHTML = '';
  const items = [
    `Neighbor radius: ${roomState.neighborRadius || 0} tiles`,
    `Minimum neighbors: ${roomState.minNeighbors || 0}`,
    `Density area: ${roomState.densityAreaSize || 0}x${roomState.densityAreaSize || 0}`,
    `Max occupants in area: ${roomState.maxOccupants || 0}`
  ];
  items.forEach(text => {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.textContent = text;
    neighborStats.appendChild(row);
  });
}

function renderParticipants() {
  if (!participantsList || !roomState) return;
  participantsList.innerHTML = '';
  (roomState.participants || []).forEach(p => {
    const row = document.createElement('div');
    row.className = 'list-item';
    const label = document.createElement('div');
    const lock = p.lockedUntil ? 'locked' : 'active';
    label.textContent = `${p.displayName} - ${p.role} - ${p.lens} - ${lock}`;
    row.appendChild(label);
    participantsList.appendChild(row);
  });
}

function renderGovernmentSummary() {
  if (!roomState || !governmentSummaryEl || !governmentBillsEl || !governmentPartiesEl) return;
  governmentSummaryEl.innerHTML = '';
  governmentBillsEl.innerHTML = '';
  governmentPartiesEl.innerHTML = '';
  const summary = roomState.governmentSummary;
  if (!summary || !summary.constitutionVersion) {
    const empty = document.createElement('div');
    empty.className = 'list-item';
    empty.textContent = 'Government model not initialized yet.';
    governmentSummaryEl.appendChild(empty);
    return;
  }

  const summaryItems = [
    { text: `Constitution: ${summary.constitutionVersion}` },
    { text: `Tick: ${summary.tick || 0}` },
    { text: `Federal executive: ${summary.federalExecutiveParty || 'None'}` },
    { text: `Lower / upper: ${summary.federalLowerParty || 'None'} / ${summary.federalUpperParty || 'None'}` },
    { text: `Chief justice bloc: ${summary.federalChiefJusticeParty || 'None'}` },
    { text: `Trust: ${formatScore(summary.federalTrust)}` },
    {
      text: `Revenue / infrastructure: ${summary.federalRevenue || 0} / ${summary.federalInfrastructureBudget || 0}`,
      detail: renderBudgetBreakdownCard(summary)
    },
    {
      text: `States: ${summary.stateCount || 0}`,
      detail: renderStateSummaryGroup(summary)
    },
    { text: `Orders / cases: ${summary.activeExecutiveOrderCount || 0} / ${summary.activeCourtCaseCount || 0}` },
    {
      text: `Next election: ${summary.nextElectionAt ? new Date(summary.nextElectionAt).toLocaleTimeString() : 'pending'}`,
      detail: renderElectionSummaryCard(summary)
    }
  ];
  summaryItems.forEach((item) => {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.textContent = item.text;
    governmentSummaryEl.appendChild(row);
    if (item.detail) {
      governmentSummaryEl.appendChild(item.detail);
    }
  });

  const actionItems = Array.isArray(summary.actionItems) ? summary.actionItems : [];
  if (!actionItems.length) {
    const combinedGovernmentActions = [
      ...(summary.activeBills || []),
      ...((summary.executiveOrders || []).map((item) => `Order: ${item}`)),
      ...((summary.courtCases || []).map((item) => `Court: ${item}`))
    ];
    if (!combinedGovernmentActions.length) {
      const row = document.createElement('div');
      row.className = 'list-item';
      row.textContent = 'No active government actions.';
      governmentBillsEl.appendChild(row);
    } else {
      combinedGovernmentActions.forEach((bill) => {
        const row = document.createElement('div');
        row.className = 'list-item';
        row.textContent = bill;
        governmentBillsEl.appendChild(row);
      });
    }
  } else {
    actionItems.forEach((item) => {
      governmentBillsEl.appendChild(renderGovernmentActionItem(item));
    });
  }

  const partySummaries = summary.partySummaries || [];
  if (!partySummaries.length) {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.textContent = 'No parties tracked.';
    governmentPartiesEl.appendChild(row);
  } else {
    partySummaries.forEach((party) => {
      const row = document.createElement('div');
      row.className = 'list-item';
      row.textContent = party;
      governmentPartiesEl.appendChild(row);
    });
  }
}

function renderGovernmentActionItem(item) {
  const row = document.createElement('div');
  row.className = 'list-item government-action-item';

  const title = document.createElement('div');
  title.className = 'list-item-title';
  title.textContent = item && item.title ? item.title : 'Government action';
  row.appendChild(title);

  if (item && item.meta) {
    const meta = document.createElement('div');
    meta.className = 'list-item-meta';
    meta.textContent = item.meta;
    row.appendChild(meta);
  }

  if (item && item.summary) {
    const summary = document.createElement('div');
    summary.className = 'list-item-summary';
    summary.textContent = item.summary;
    row.appendChild(summary);
  }
  return row;
}

function renderBudgetBreakdownCard(summary) {
  const budget = summary && summary.federalBudget;
  if (!budget) return null;
  const card = document.createElement('div');
  card.className = 'list-item government-detail-card';
  card.appendChild(buildDetailTitle('Federal budget breakdown'));
  card.appendChild(buildMetricGrid([
    ['Education', budget.education || 0],
    ['Health', budget.health || 0],
    ['Infrastructure', budget.infrastructure || 0],
    ['Welfare', budget.welfare || 0],
    ['Justice', budget.justice || 0],
    ['Payroll', budget.payroll || 0]
  ]));
  const foot = document.createElement('div');
  foot.className = 'list-item-summary';
  foot.textContent = `Service access: ${formatScore(budget.serviceAccess)} · Revenue: ${budget.revenue || 0}`;
  card.appendChild(foot);
  return card;
}

function renderStateSummaryGroup(summary) {
  const states = Array.isArray(summary && summary.stateSummaries) ? summary.stateSummaries : [];
  if (!states.length) return null;
  const wrap = document.createElement('div');
  wrap.className = 'government-state-group';
  states.forEach((state) => {
    const card = document.createElement('div');
    card.className = 'list-item government-detail-card government-state-card';
    card.appendChild(buildDetailTitle(state.name || 'State'));

    const meta = document.createElement('div');
    meta.className = 'list-item-meta';
    meta.textContent = `${state.districtCount || 0} districts · ${humanizeLabel(state.districtingMode || 'regional')} map`;
    card.appendChild(meta);

    const control = document.createElement('div');
    control.className = 'list-item-summary';
    control.textContent = `Executive: ${state.executiveParty || 'None'} · Lower/upper: ${state.lowerParty || 'None'} / ${state.upperParty || 'None'}`;
    card.appendChild(control);

    const court = document.createElement('div');
    court.className = 'list-item-summary';
    court.textContent = `Court bloc: ${state.chiefJusticeParty || 'None'} · Trust: ${formatScore(state.trust)} · Service: ${formatScore(state.serviceAccess)}`;
    card.appendChild(court);

    card.appendChild(buildMetricGrid([
      ['Revenue', state.revenue || 0],
      ['Infra', state.infrastructure || 0]
    ]));
    wrap.appendChild(card);
  });
  return wrap;
}

function renderElectionSummaryCard(summary) {
  const election = summary && summary.electionSummary;
  if (!election) return null;
  const card = document.createElement('div');
  card.className = 'list-item government-detail-card';
  card.appendChild(buildDetailTitle(`${election.nextScope || 'Federal'} election`));

  const meta = document.createElement('div');
  meta.className = 'list-item-meta';
  meta.textContent = election.nextElectionAt
    ? `Scheduled ${new Date(election.nextElectionAt).toLocaleString()}`
    : 'Schedule pending';
  card.appendChild(meta);

  if (election.lastSummary || election.lastWinnerParty) {
    const summaryLine = document.createElement('div');
    summaryLine.className = 'list-item-summary';
    summaryLine.textContent = election.lastSummary || `Last winner: ${election.lastWinnerParty}`;
    card.appendChild(summaryLine);
  }

  if (election.lastHeldAt || election.lastWinnerParty) {
    const result = document.createElement('div');
    result.className = 'list-item-summary';
    const parts = [];
    if (election.lastHeldAt) {
      parts.push(`Last held ${new Date(election.lastHeldAt).toLocaleString()}`);
    }
    if (election.lastWinnerParty) {
      parts.push(`Winner: ${election.lastWinnerParty}`);
    }
    if (election.lastTurnout || election.lastTurnout === 0) {
      parts.push(`Turnout: ${formatPercent(election.lastTurnout)}`);
    }
    result.textContent = parts.join(' · ');
    card.appendChild(result);
  }

  return card;
}

function buildDetailTitle(text) {
  const title = document.createElement('div');
  title.className = 'list-item-title';
  title.textContent = text;
  return title;
}

function buildMetricGrid(items) {
  const grid = document.createElement('div');
  grid.className = 'metric-grid';
  items.forEach(([label, value]) => {
    const pill = document.createElement('div');
    pill.className = 'metric-pill';
    pill.textContent = `${label}: ${value}`;
    grid.appendChild(pill);
  });
  return grid;
}

function humanizeLabel(value) {
  if (!value) return '';
  return String(value)
    .replace(/[_-]/g, ' ')
    .replace(/\b\w/g, (match) => match.toUpperCase());
}

function renderBotProfiles() {
  if (!botProfilesList || !roomState) return;
  botProfilesList.innerHTML = '';
  (bots || []).forEach(bot => {
    const card = document.createElement('div');
    card.className = 'bot-profile';

    const title = document.createElement('div');
    title.className = 'bot-profile-title';
    title.textContent = bot.displayName;
    card.appendChild(title);

    const profile = bot.profile || {};
    const badges = document.createElement('div');
    badges.className = 'profile-badges';
    const dominant = dominantTrait(profile);
    const badge = document.createElement('div');
    badge.className = 'profile-badge';
    badge.textContent = dominant ? `Dominant: ${dominant}` : 'Dominant: balanced';
    badges.appendChild(badge);
    const roleBadge = document.createElement('div');
    roleBadge.className = 'profile-badge';
    roleBadge.textContent = bot.role ? `Role: ${bot.role}` : 'Role: unknown';
    badges.appendChild(roleBadge);
    const civicState = bot.civicState || {};
    if (civicState.partyId) {
      const partyBadge = document.createElement('div');
      partyBadge.className = 'profile-badge';
      partyBadge.textContent = `Party: ${civicState.partyId.replace('party-', '')}`;
      badges.appendChild(partyBadge);
    }
    if (civicState.employmentStatus) {
      const workBadge = document.createElement('div');
      workBadge.className = 'profile-badge';
      workBadge.textContent = `${civicState.employmentStatus} / ${civicState.occupationSector || 'general'}`;
      badges.appendChild(workBadge);
    }
    card.appendChild(badges);

    if (civicState.stateId || civicState.districtId) {
      const civicMeta = document.createElement('div');
      civicMeta.className = 'bot-civic-meta';
      const civicBits = [];
      if (civicState.stateId) civicBits.push(civicState.stateId);
      if (civicState.districtId) civicBits.push(civicState.districtId);
      if (civicState.householdId) civicBits.push(civicState.householdId);
      civicMeta.textContent = civicBits.join(' · ');
      card.appendChild(civicMeta);
    }

    const bars = document.createElement('div');
    bars.className = 'profile-bars';
    const traits = [
      { key: 'forest', label: 'Forest', color: '#2a7f62' },
      { key: 'water', label: 'Water', color: '#4aa3df' },
      { key: 'clear', label: 'Clear', color: '#e2c28b' },
      { key: 'industry', label: 'Industry', color: '#6b6f7a' },
      { key: 'consensus', label: 'Consensus', color: '#8e8e8e' },
      { key: 'mobility', label: 'Mobility', color: '#ffb300' },
      { key: 'risk', label: 'Risk', color: '#ef6c00' },
      { key: 'abstain', label: 'Abstain', color: '#8d6e63' },
      { key: 'conflict', label: 'Conflict', color: '#c62828' }
    ];
    traits.forEach(trait => {
      const row = document.createElement('div');
      row.className = 'profile-bar';

      const label = document.createElement('div');
      label.textContent = trait.label;

      const track = document.createElement('div');
      track.className = 'bar-track';
      const fill = document.createElement('div');
      fill.className = 'bar-fill';
      fill.style.width = `${Math.max(0, Math.min(1, profile[trait.key] || 0)) * 100}%`;
      fill.style.background = trait.color;
      track.appendChild(fill);

      const value = document.createElement('div');
      value.textContent = formatScore(profile[trait.key]);

      row.appendChild(label);
      row.appendChild(track);
      row.appendChild(value);
      bars.appendChild(row);
    });

    if (civicState) {
      [
        { label: 'Trust', value: civicState.institutionalTrust, color: '#1f6f8b' },
        { label: 'Rights', value: civicState.rightsSatisfaction, color: '#2a7f62' },
        { label: 'Services', value: civicState.serviceAccess, color: '#7f5539' },
        { label: 'Participation', value: civicState.civicParticipation, color: '#8b5cf6' }
      ].forEach((metric) => {
        if (metric.value === undefined || metric.value === null) return;
        const row = document.createElement('div');
        row.className = 'profile-bar';

        const label = document.createElement('div');
        label.textContent = metric.label;

        const track = document.createElement('div');
        track.className = 'bar-track';
        const fill = document.createElement('div');
        fill.className = 'bar-fill';
        fill.style.width = `${Math.max(0, Math.min(1, metric.value || 0)) * 100}%`;
        fill.style.background = metric.color;
        track.appendChild(fill);

        const value = document.createElement('div');
        value.textContent = formatScore(metric.value);

        row.appendChild(label);
        row.appendChild(track);
        row.appendChild(value);
        bars.appendChild(row);
      });
    }

    card.appendChild(bars);
    botProfilesList.appendChild(card);
  });
  if (!bots.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'No bot profiles available.';
    botProfilesList.appendChild(empty);
  }
}

function formatScore(value) {
  if (value === undefined || value === null) return '0.00';
  return Number(value).toFixed(2);
}

function formatPercent(value) {
  if (value === undefined || value === null) return '0%';
  return `${Math.round(Number(value) * 100)}%`;
}

function dominantTrait(profile) {
  if (!profile) return '';
  const keys = ['forest', 'water', 'clear', 'industry', 'consensus', 'mobility'];
  let bestKey = '';
  let bestVal = -1;
  keys.forEach(key => {
    const val = profile[key];
    if (val !== undefined && val !== null && val > bestVal) {
      bestVal = val;
      bestKey = key;
    }
  });
  return bestKey;
}

function renderProposals() {
  if (!proposalsList || !roomState) return;
  proposalsList.innerHTML = '';
  const proposals = roomState.proposals || [];
  proposals.forEach(p => {
    const row = document.createElement('div');
    row.className = `list-item${selectedProposalId === p.id ? ' active' : ''}`;
    const title = document.createElement('div');
    title.textContent = `Proposal by ${p.proposerName}`;
    const meta = document.createElement('div');
    const needed = p.requiredVotes || 0;
    const expiresAt = p.expiresAt ? new Date(p.expiresAt).getTime() : 0;
    const remaining = expiresAt ? Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000)) : 0;
    const timeLabel = expiresAt ? ` - ${remaining}s` : '';
    meta.textContent = `${p.status} / ${proposalStageLabel(p)} - +${p.approvals} / -${p.rejections} - need ${needed}${timeLabel}`;
    row.appendChild(title);
    row.appendChild(meta);
    row.addEventListener('click', () => {
      selectedProposalId = p.id;
      renderProposals();
      updateLockUI();
    });
    proposalsList.appendChild(row);
  });
  if (!proposals.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'No proposals yet.';
    proposalsList.appendChild(empty);
  }
}

function renderLocalProposals() {
  if (!localProposalsList || !roomState) return;
  localProposalsList.innerHTML = '';
  const me = participants.find(p => p.id === participantId);
  const myName = me ? me.displayName : '';
  const proposals = (roomState.proposals || []).filter(p => isLocalProposal(p) && p.status === 'PENDING' && !dismissedPolls.has(p.id) && p.canVote && !p.owner);
  proposals.forEach(p => {
    const row = document.createElement('div');
    row.className = 'list-item';
    const title = document.createElement('div');
    const label = p.botGenerated ? `Bot: ${p.botLabel || 'Neighbor'}` : p.proposerName;
    title.textContent = `Proposal by ${label}`;
    const meta = document.createElement('div');
    const needed = p.requiredVotes || 0;
    const botYes = p.botYes || 0;
    const botNo = p.botNo || 0;
    const botLabel = botYes || botNo ? ` (bots +${botYes} / -${botNo})` : '';
    meta.textContent = `${p.status} / ${proposalStageLabel(p)} - +${p.approvals} / -${p.rejections}${botLabel} - need ${needed}`;
    const dismiss = document.createElement('button');
    dismiss.className = 'fallback-button';
    dismiss.textContent = '✕';
    dismiss.classList.add('poll-action');
    dismiss.addEventListener('click', (evt) => {
      evt.stopPropagation();
      dismissPollById(p.id);
    });
    row.appendChild(title);
    row.appendChild(meta);
    row.appendChild(dismiss);
    if (!p.botGenerated && myName && p.proposerName === myName) {
      const note = document.createElement('div');
      note.textContent = 'You started this poll.';
      row.appendChild(note);
    } else {
      const actions = document.createElement('div');
      actions.className = 'multiplayer-actions poll-actions-inline';
      const yesBtn = document.createElement('button');
      yesBtn.className = 'fallback-button poll-action';
      yesBtn.textContent = '✓';
      yesBtn.addEventListener('click', () => voteProposalById(p.id, true));
      const noBtn = document.createElement('button');
      noBtn.className = 'fallback-button poll-action';
      noBtn.textContent = '✕';
      noBtn.addEventListener('click', () => voteProposalById(p.id, false));
      actions.appendChild(yesBtn);
      actions.appendChild(noBtn);
      row.appendChild(actions);
    }
    localProposalsList.appendChild(row);
  });
  if (!proposals.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'No local proposals nearby.';
    localProposalsList.appendChild(empty);
  }
}

function renderPollOverlay() {
  if (!pollOverlay || !roomState) return;
  pollOverlay.innerHTML = '';
  const proposals = (roomState.proposals || []).filter(p => isLocalProposal(p) && p.status === 'PENDING' && !dismissedPolls.has(p.id) && p.canVote && !p.owner);
  proposals.forEach(p => {
    if (p.anchorX === undefined || p.anchorY === undefined) return;
    if (p.anchorX < viewX || p.anchorX >= viewX + viewWidth) return;
    if (p.anchorY < viewY || p.anchorY >= viewY + viewHeight) return;
    const marker = document.createElement('div');
    marker.className = p.botGenerated ? 'poll-marker bot-poll-marker' : 'poll-marker';
    marker.style.left = `${(p.anchorX - viewX + 0.5) * tileSize}px`;
    marker.style.top = `${(p.anchorY - viewY + 0.2) * tileSize}px`;
    const label = document.createElement('div');
    const who = p.botGenerated ? `Bot: ${p.botLabel || 'Neighbor'}` : p.proposerName;
    label.textContent = who;
    marker.appendChild(label);
    const typeLabel = document.createElement('div');
    const botYes = p.botYes || 0;
    const botNo = p.botNo || 0;
    const botInfo = botYes || botNo ? ` bots +${botYes}/-${botNo}` : '';
    typeLabel.textContent = `Tile: ${proposalTypeLabel(p)} / ${proposalStageLabel(p)}${botInfo}`;
    marker.appendChild(typeLabel);
    const actions = document.createElement('div');
    actions.className = 'poll-marker-actions';
    const yes = document.createElement('button');
    yes.textContent = '✓';
    yes.className = 'poll-action';
    yes.addEventListener('click', (evt) => {
      evt.stopPropagation();
      voteProposalById(p.id, true);
    });
    const no = document.createElement('button');
    no.textContent = '✕';
    no.className = 'poll-action';
    no.addEventListener('click', (evt) => {
      evt.stopPropagation();
      voteProposalById(p.id, false);
    });
    actions.appendChild(yes);
    actions.appendChild(no);
    marker.appendChild(actions);
    pollOverlay.appendChild(marker);
  });
}

function proposalTypeLabel(p) {
  if (!p) return 'Unknown';
  const type = p.tileType !== undefined ? p.tileType : p.type;
  if (type === 1 || type === 'forest') return 'Forest';
  if (type === 2 || type === 'water') return 'Water';
  if (type === 3 || type === 'industry') return 'Industry';
  if (type === 4 || type === 'highway') return 'Highway';
  if (type === 0 || type === 'clear') return 'Clear';
  return 'Unknown';
}

function renderPollResults() {
  if (!pollResultsList || !roomState) return;
  pollResultsList.innerHTML = '';
  const proposals = (roomState.proposals || []).slice(0, 6);
  proposals.forEach(p => {
    const row = document.createElement('div');
    row.className = 'list-item';
    const label = p.botGenerated ? `Bot: ${p.botLabel || 'Neighbor'}` : (p.proposerName || 'Unknown');
    const x = p.anchorX !== undefined ? p.anchorX : p.x;
    const y = p.anchorY !== undefined ? p.anchorY : p.y;
    const coord = `${x !== undefined && x !== null ? x : '?'}${','}${y !== undefined && y !== null ? y : '?'}`;
    const typeLabel = proposalTypeLabel(p).toUpperCase();
    const status = (p.status || 'UNKNOWN').toUpperCase();
    const phase = proposalStageLabel(p).toUpperCase();
    const title = document.createElement('div');
    title.textContent = `${status} / ${phase} - ${label} (${coord} ${typeLabel})`;
    row.appendChild(title);
    row.addEventListener('click', () => {
      blinkTileAt(x, y);
    });
    pollResultsList.appendChild(row);
  });
  if (!proposals.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'No polls yet.';
    pollResultsList.appendChild(empty);
  }
}

if (togglePollResultsBtn && pollResultsList) {
  togglePollResultsBtn.addEventListener('click', () => {
    const collapsed = pollResultsList.classList.toggle('collapsed');
    togglePollResultsBtn.textContent = collapsed ? 'Expand' : 'Collapse';
  });
}

function isLocalProposal(p) {
  if (!p || p.anchorX === undefined || p.anchorY === undefined) return true;
  if (!roomState) return true;
  const viewX0 = viewX;
  const viewY0 = viewY;
  const viewX1 = viewX + viewWidth;
  const viewY1 = viewY + viewHeight;
  return p.anchorX >= viewX0 && p.anchorX < viewX1 && p.anchorY >= viewY0 && p.anchorY < viewY1;
}

async function voteProposalById(proposalId, approve) {
  if (!roomId || !proposalId) return;
  const res = await fetch(`/room/${roomId}/vote`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, proposalId, approve })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
    selectedProposalId = '';
  } else {
    const message = await res.text();
    roomStatus.textContent = message || 'Vote rejected.';
    tradeoffPanel.textContent = message || 'Vote rejected.';
    if (message && message.toLowerCase().includes('already voted')) {
      selectedProposalId = '';
      await refreshRoomState();
    }
  }
}

async function dismissPollById(proposalId) {
  if (!roomId || !proposalId) return;
  const res = await fetch(`/room/${roomId}/poll/dismiss`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, proposalId })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
  } else {
    const message = await res.text();
    roomStatus.textContent = message || 'Dismiss failed.';
    tradeoffPanel.textContent = message || 'Dismiss failed.';
  }
}

function renderSharedTokens() {
  if (!sharedTokensEl) return;
  sharedTokensEl.innerHTML = '';
}

function renderSharedQuests() {
  if (!sharedQuestsList) return;
  sharedQuestsList.innerHTML = '';
}

function renderMemoryMarkers() {
  if (!memoryList) return;
  memoryList.innerHTML = '';
  memoryList.textContent = 'Memory seeds appear after consensus drops.';
}

async function proposeToRoom() {
  if (!roomId || !pendingChange) return;
  const proposedTiles = applyChangeToTiles(tiles, pendingChange);
  const res = await fetch(`/room/${roomId}/propose`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, tiles: proposedTiles, delta: pendingChange, note: explanationEl.textContent })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
    pendingChange = null;
    roomStatus.textContent = 'Proposal submitted. Waiting for votes.';
    tradeoffPanel.textContent = '';
  } else {
    const message = await res.text();
    roomStatus.textContent = message || 'Proposal failed.';
    tradeoffPanel.textContent = message || 'Proposal failed.';
  }
}

async function voteProposal(approve) {
  if (!roomId || !selectedProposalId) return;
  const res = await fetch(`/room/${roomId}/vote`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, proposalId: selectedProposalId, approve })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
    selectedProposalId = '';
  } else {
    const message = await res.text();
    roomStatus.textContent = message || 'Vote rejected.';
    tradeoffPanel.textContent = message || 'Vote rejected.';
    if (message && message.toLowerCase().includes('already voted')) {
      selectedProposalId = '';
      await refreshRoomState();
    }
  }
}

async function applyProposal() {
  if (!roomId || !selectedProposalId) return;
  const res = await fetch(`/room/${roomId}/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, proposalId: selectedProposalId })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
  }
}

async function moveTo(x, y, options = {}) {
  if (!roomId) return;
  try {
    const body = { token, x, y };
    if (options && Array.isArray(options.path) && options.path.length) {
      const path = options.path.map((step) => {
        if (Array.isArray(step)) return step;
        if (step && Number.isFinite(step.x) && Number.isFinite(step.y)) {
          return [step.x, step.y];
        }
        return null;
      }).filter(Boolean);
      if (path.length) {
        body.path = path;
        const last = path[path.length - 1];
        if (last && last.length >= 2) {
          body.x = last[0];
          body.y = last[1];
        }
      }
    }
    const res = await fetch(`/room/${roomId}/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (res.ok) {
      const data = await res.json();
      setRoomStateFromResponse(data);
      updateViewCenter();
      return true;
    }
    const message = await res.text();
    tradeoffPanel.textContent = message || 'Move rejected.';
    roomStatus.textContent = message || 'Move rejected.';
    setPlayerTarget(player.x, player.y);
    return false;
  } catch (err) {
    tradeoffPanel.textContent = 'Move failed.';
    roomStatus.textContent = 'Move failed.';
    setPlayerTarget(player.x, player.y);
    return false;
  }
}

window.addEventListener('keydown', (evt) => {
  if (isLocked()) return;
  stopAutoNav();
  let nx = player.x;
  let ny = player.y;
  const onRoad = (highways[player.y] && highways[player.y][player.x]) || tiles[player.y][player.x] === 4;
  const step = onRoad ? 3 : 1;
  if (evt.key === 'ArrowUp' || evt.key === 'w') ny -= step;
  if (evt.key === 'ArrowDown' || evt.key === 's') ny += step;
  if (evt.key === 'ArrowLeft' || evt.key === 'a') nx -= step;
  if (evt.key === 'ArrowRight' || evt.key === 'd') nx += step;
  if (nx === player.x && ny === player.y) return;
  if (nx < 0 || nx >= width || ny < 0 || ny >= height) return;
  moveTo(nx, ny);
});

function initMultiplayer() {
  if (createRoomBtn) {
    createRoomBtn.addEventListener('click', async () => {
      const name = roomNameInput ? roomNameInput.value : '';
      const res = await fetch('/room/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, name })
      });
      if (!res.ok) {
        roomStatus.textContent = 'Failed to create room.';
        return;
      }
      const data = await res.json();
      roomId = data.roomId;
      participantId = data.participantId;
      setRoomStateFromResponse(data);
      roomStatus.textContent = `Room created: ${data.roomName}`;
    });
  }

  if (joinRoomBtn) {
    joinRoomBtn.addEventListener('click', async () => {
      const id = roomIdInput ? roomIdInput.value.trim() : '';
      if (!id) return;
      const res = await fetch('/room/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, roomId: id })
      });
      if (!res.ok) {
        roomStatus.textContent = 'Failed to join room.';
        return;
      }
      const data = await res.json();
      roomId = data.roomId;
      participantId = data.participantId;
      roomStatus.textContent = `Joined room: ${data.roomName}`;
      await refreshRoomState();
    });
  }

  if (proposeRoomBtn) {
    proposeRoomBtn.addEventListener('click', async () => {
      await proposeToRoom();
    });
  }

  if (approveProposalBtn) {
    approveProposalBtn.addEventListener('click', async () => {
      await voteProposal(true);
    });
  }

  if (rejectProposalBtn) {
    rejectProposalBtn.addEventListener('click', async () => {
      await voteProposal(false);
    });
  }

  if (applyProposalBtn) {
    applyProposalBtn.addEventListener('click', async () => {
      roomStatus.textContent = 'Proposals apply automatically on majority vote.';
    });
  }

  if (dropMemoryBtn) {
    dropMemoryBtn.addEventListener('click', async () => {
      if (!roomId) return;
      const label = memoryLabelInput ? memoryLabelInput.value : '';
      await fetch(`/room/${roomId}/memory`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, x: player.x, y: player.y, label })
      });
      await refreshRoomState();
    });
  }

  setInterval(() => {
    renderLockStatus();
  }, 1000);
}

function canOccupyClient(x, y) {
  if (!roomState) return true;
  const areaSize = roomState.densityAreaSize || 20;
  const maxOccupants = roomState.maxOccupants || 8;
  const half = Math.floor(areaSize / 2);
  const minX = x - half;
  const maxX = x + half - 1;
  const minY = y - half;
  const maxY = y + half - 1;
  let count = 0;
  participants.forEach(p => {
    if (!p || p.id === participantId) return;
    if (p.x === undefined || p.y === undefined) return;
    if (p.x < 0 || p.y < 0) return;
    if (p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY) {
      count += 1;
    }
  });
  bots.forEach(b => {
    if (!b) return;
    if (b.x === undefined || b.y === undefined) return;
    if (b.x < 0 || b.y < 0) return;
    if (b.x >= minX && b.x <= maxX && b.y >= minY && b.y <= maxY) {
      count += 1;
    }
  });
  return count < maxOccupants;
}

async function refreshRoomState() {
  if (!roomId) return;
  const res = await fetch(`/room/${roomId}/state`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, roomId })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
  }
}

function buildRadialMenu() {
  if (!radialMenu) return;
  radialMenu.innerHTML = '';
  const options = [
    { tool: 'forest', icon: 'forest', label: 'Forest' },
    { tool: 'water', icon: 'water', label: 'Water' },
    { tool: 'clear', icon: 'crop_free', label: 'Clear' },
    { tool: 'industry', icon: 'factory', label: 'Industry' },
    { tool: 'highway', icon: 'route', label: 'Highway' }
  ];
  const radius = 56;
  const step = (Math.PI * 2) / options.length;
  options.forEach((opt, index) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'radial-button';
    btn.setAttribute('data-tool', opt.tool);
    btn.setAttribute('title', opt.label);
    const icon = document.createElement('span');
    icon.className = 'material-symbols-outlined';
    icon.textContent = opt.icon;
    btn.appendChild(icon);
    const angle = index * step - Math.PI / 2;
    const dx = Math.cos(angle) * radius;
    const dy = Math.sin(angle) * radius;
    btn.style.transform = `translate(${dx}px, ${dy}px)`;
    btn.addEventListener('click', async (evt) => {
      evt.stopPropagation();
      if (!pendingAnchor) return;
      const anchor = pendingAnchor;
      pendingAnchor = null;
      tool = opt.tool;
      closeRadialMenu();
      window.requestAnimationFrame(async () => {
        setPendingChange(anchor.x, anchor.y, opt.tool);
        await proposeToRoom();
      });
    });
    radialMenu.appendChild(btn);
  });
}

function openRadialMenu(evt) {
  if (!radialMenu) return;
  if (!radialMenu.dataset.ready) {
    buildRadialMenu();
    radialMenu.dataset.ready = 'true';
  }
  const wrap = canvas.parentElement || canvas;
  const rect = wrap.getBoundingClientRect();
  radialMenu.style.left = `${evt.clientX - rect.left}px`;
  radialMenu.style.top = `${evt.clientY - rect.top}px`;
  radialMenu.classList.add('active');
  radialMenuIgnoreUntil = Date.now() + 200;
  if (pollDetails) {
    pollDetails.textContent = 'Choose a tile type to start the 1x1 poll.';
  }
}

function closeRadialMenu() {
  if (!radialMenu) return;
  radialMenu.classList.remove('active');
}

document.addEventListener('click', (evt) => {
  if (!radialMenu || !radialMenu.classList.contains('active')) return;
  if (Date.now() < radialMenuIgnoreUntil) return;
  if (radialMenu.contains(evt.target)) return;
  closeRadialMenu();
});

function findPathIndex(x, y, path, startIndex = 0) {
  if (!path || !path.length) return -1;
  for (let i = Math.max(0, startIndex); i < path.length; i++) {
    const step = path[i];
    if (step && step.x === x && step.y === y) {
      return i;
    }
  }
  return -1;
}

function reconcileAutoNavFromServer() {
  if (!autoNavPath || !autoNavPath.length) return;
  const idx = findPathIndex(player.x, player.y, autoNavPath, autoNavIndex);
  if (idx >= 0) {
    autoNavIndex = idx;
    if (autoNavSentIndex < autoNavIndex) {
      autoNavSentIndex = autoNavIndex;
    }
    if (autoNavPredictedIndex < autoNavIndex) {
      autoNavPredictedIndex = autoNavIndex;
    }
  }
}

function advanceAutoNavPrediction() {
  if (!autoNavPath || !autoNavPath.length) return;
  if (autoNavPredictedIndex >= autoNavSentIndex) return;
  const now = Date.now();
  if (!autoNavLastPredictAt) {
    autoNavLastPredictAt = now;
  }
  const elapsed = now - autoNavLastPredictAt;
  if (elapsed < AUTO_NAV_PREDICT_INTERVAL_MS) return;
  const steps = Math.max(1, Math.floor(elapsed / AUTO_NAV_PREDICT_INTERVAL_MS));
  autoNavPredictedIndex = Math.min(autoNavSentIndex, autoNavPredictedIndex + steps);
  autoNavLastPredictAt = now;
  const predicted = autoNavPath[autoNavPredictedIndex];
  if (predicted) {
    setPlayerTarget(predicted.x, predicted.y);
  }
}

function beginAutoNav(path) {
  if (!path || !path.length) return;
  autoNavPath = path;
  autoNavIndex = findPathIndex(player.x, player.y, path, 0);
  if (autoNavIndex < 0) {
    autoNavIndex = 0;
  }
  autoNavSentIndex = autoNavIndex;
  autoNavPredictedIndex = autoNavIndex;
  autoNavInFlightCount = 0;
  autoNavInFlight = false;
  autoNavLastPredictAt = 0;
  autoNavTarget = { x: path[path.length - 1].x, y: path[path.length - 1].y };
  pendingRoutes = null;
  renderRouteChoices();
  if (autoNavTimer) return;
  autoNavTimer = setInterval(() => {
    if (!autoNavTarget) return;
    if (isLocked()) return;
    if (!autoNavPath || autoNavPath.length <= 1 || autoNavIndex >= autoNavPath.length - 1) {
      stopAutoNav();
      miniTarget = null;
      draw();
      return;
    }
    while (autoNavInFlightCount < AUTO_NAV_MAX_IN_FLIGHT) {
      if (autoNavSentIndex >= autoNavPath.length - 1) break;
      const startIndex = autoNavSentIndex + 1;
      const maxAdvance = Math.min(autoNavPath.length - 1, startIndex + AUTO_NAV_MAX_STEPS + 6);
      const steps = [];
      let endIndex = -1;
      for (let i = startIndex; i <= maxAdvance && steps.length < AUTO_NAV_MAX_STEPS; i++) {
        const candidate = autoNavPath[i];
        if (!candidate) continue;
        if (!canOccupyClient(candidate.x, candidate.y)) continue;
        steps.push({ x: candidate.x, y: candidate.y });
        endIndex = i;
      }
      if (!steps.length || endIndex < 0) {
        tradeoffPanel.textContent = 'Auto-route blocked by nearby occupancy.';
        stopAutoNav();
        return;
      }
      autoNavSentIndex = endIndex;
      autoNavInFlightCount += 1;
      autoNavInFlight = autoNavInFlightCount > 0;
      const predicted = autoNavPath[endIndex];
      if (predicted) {
        setPlayerTarget(predicted.x, predicted.y);
      }
      moveTo(steps[steps.length - 1].x, steps[steps.length - 1].y, { path: steps })
        .then((ok) => {
          if (!ok) {
            stopAutoNav();
            return;
          }
        })
        .finally(() => {
          autoNavInFlightCount = Math.max(0, autoNavInFlightCount - 1);
          autoNavInFlight = autoNavInFlightCount > 0;
        });
    }
    advanceAutoNavPrediction();
  }, 300);
}

function buildRoadPath(sx, sy, tx, ty) {
  const maxNodes = 800000;
  const startKey = sy * width + sx;
  const targetKey = ty * width + tx;
  const open = new MinHeap();
  const gScore = new Map();
  const fScore = new Map();
  const cameFrom = new Map();
  gScore.set(startKey, 0);
  fScore.set(startKey, heuristic(sx, sy, tx, ty));
  open.push({ key: startKey, x: sx, y: sy, f: fScore.get(startKey) });
  let visited = 0;
  while (!open.isEmpty()) {
    const current = open.pop();
    if (current.key === targetKey) {
      const path = reconstructPath(cameFrom, current.x, current.y);
      return { path, cost: gScore.get(current.key) || Number.POSITIVE_INFINITY };
    }
    visited++;
    if (visited > maxNodes) break;
    const neighbors = [
      { x: current.x + 1, y: current.y },
      { x: current.x - 1, y: current.y },
      { x: current.x, y: current.y + 1 },
      { x: current.x, y: current.y - 1 }
    ];
    for (const n of neighbors) {
      if (n.x < 0 || n.x >= width || n.y < 0 || n.y >= height) continue;
      const nKey = n.y * width + n.x;
      const road = (highways[n.y] && highways[n.y][n.x]) || tiles[n.y][n.x] === 4;
      const stepCost = road ? 1 / 3 : 2.5;
      const tentative = (gScore.get(current.key) ?? Number.POSITIVE_INFINITY) + stepCost;
      if (tentative < (gScore.get(nKey) ?? Number.POSITIVE_INFINITY)) {
        cameFrom.set(nKey, current.key);
        gScore.set(nKey, tentative);
        const f = tentative + heuristic(n.x, n.y, tx, ty);
        fScore.set(nKey, f);
        open.push({ key: nKey, x: n.x, y: n.y, f });
      }
    }
  }
  return { path: [], cost: Number.POSITIVE_INFINITY };
}

function findNearestRoad(x, y, maxRadius = 20) {
  if (!tiles.length) return null;
  for (let r = 0; r <= maxRadius; r++) {
    for (let dy = -r; dy <= r; dy++) {
      const y0 = y + dy;
      if (y0 < 0 || y0 >= height) continue;
      const dx = r - Math.abs(dy);
      const candidates = [x - dx, x + dx];
      for (const x0 of candidates) {
        if (x0 < 0 || x0 >= width) continue;
        if ((highways[y0] && highways[y0][x0]) || tiles[y0][x0] === 4) {
          return { x: x0, y: y0 };
        }
      }
    }
  }
  return null;
}

function buildRoadBiasedPath(sx, sy, tx, ty) {
  const startRoad = findNearestRoad(sx, sy);
  const endRoad = findNearestRoad(tx, ty);
  if (!startRoad || !endRoad) {
    return buildRoadPath(sx, sy, tx, ty);
  }
  const toRoad = buildRoadPath(sx, sy, startRoad.x, startRoad.y).path;
  const onRoad = buildRoadPath(startRoad.x, startRoad.y, endRoad.x, endRoad.y).path;
  const fromRoad = buildRoadPath(endRoad.x, endRoad.y, tx, ty).path;
  const full = [];
  for (const seg of [toRoad, onRoad, fromRoad]) {
    for (const step of seg) {
      if (!full.length || full[full.length - 1].x !== step.x || full[full.length - 1].y !== step.y) {
        full.push(step);
      }
    }
  }
  return { path: full, cost: full.length ? full.length - 1 : Number.POSITIVE_INFINITY };
}

function heuristic(x, y, tx, ty) {
  return Math.abs(tx - x) + Math.abs(ty - y);
}

function reconstructPath(cameFrom, tx, ty) {
  const path = [];
  let key = ty * width + tx;
  path.push({ x: tx, y: ty });
  while (cameFrom.has(key)) {
    key = cameFrom.get(key);
    const x = key % width;
    const y = Math.floor(key / width);
    path.push({ x, y });
  }
  path.reverse();
  return path;
}

class MinHeap {
  constructor() {
    this.items = [];
  }
  push(node) {
    this.items.push(node);
    this.bubbleUp(this.items.length - 1);
  }
  pop() {
    if (this.items.length === 1) return this.items.pop();
    const root = this.items[0];
    this.items[0] = this.items.pop();
    this.bubbleDown(0);
    return root;
  }
  isEmpty() {
    return this.items.length === 0;
  }
  bubbleUp(index) {
    while (index > 0) {
      const parent = Math.floor((index - 1) / 2);
      if (this.items[parent].f <= this.items[index].f) break;
      [this.items[parent], this.items[index]] = [this.items[index], this.items[parent]];
      index = parent;
    }
  }
  bubbleDown(index) {
    const length = this.items.length;
    while (true) {
      let left = index * 2 + 1;
      let right = index * 2 + 2;
      let smallest = index;
      if (left < length && this.items[left].f < this.items[smallest].f) smallest = left;
      if (right < length && this.items[right].f < this.items[smallest].f) smallest = right;
      if (smallest === index) break;
      [this.items[smallest], this.items[index]] = [this.items[index], this.items[smallest]];
      index = smallest;
    }
  }
}

function stopAutoNav() {
  autoNavTarget = null;
  autoNavPath = null;
  autoNavIndex = 0;
  autoNavSentIndex = 0;
  autoNavPredictedIndex = 0;
  autoNavInFlightCount = 0;
  autoNavInFlight = false;
  autoNavLastPredictAt = 0;
  pendingRoutes = null;
  if (autoNavTimer) {
    clearInterval(autoNavTimer);
    autoNavTimer = null;
  }
}

function initBots() {
  if (!botsPanel) return;
  botsPanel.querySelectorAll('[data-bot]').forEach(btn => {
    btn.addEventListener('click', async () => {
      if (!roomId) {
        botPromptEl.textContent = 'Join a room to engage companions.';
        return;
      }
      const type = btn.getAttribute('data-bot');
      const res = await fetch(`/room/${roomId}/bot`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, type })
      });
      if (!res.ok) {
        botPromptEl.textContent = 'Companion unavailable.';
        return;
      }
      const data = await res.json();
      botPromptEl.textContent = data.prompt || 'The companion is quiet for now.';
    });
  });
}

async function init() {
  renderRendererStatus();
  await initWorldFxRenderer();
  const res = await fetch('/room/last', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, name: 'Consensus Field' })
  });
  if (!res.ok) {
    roomStatus.textContent = 'Failed to start room.';
    return;
  }
  const data = await res.json();
  roomId = data.roomId;
  participantId = data.participantId;
  roomStatus.textContent = `Joined room: ${data.roomName}`;
  await refreshRoomState();
  if (infoPanel) {
    document.body.classList.toggle('drawer-open', infoPanel.classList.contains('open'));
  }
  width = tiles[0] ? tiles[0].length : 0;
  height = tiles.length || 0;
  resizeViewport();
  updateViewCenter();
  draw();
  initMultiplayer();
  initBots();
}

window.addEventListener('resize', () => {
  resizeViewport();
  draw();
});

init();

setInterval(() => {
  if (roomState && worldFxRenderer && miniMap && tiles.length) {
    renderWorldFx();
  }
}, 2000);
