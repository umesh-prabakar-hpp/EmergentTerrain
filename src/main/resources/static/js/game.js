const token = window.APP_TOKEN;
const canvas = document.getElementById('world');
const ctx = canvas.getContext('2d');
const miniMap = document.getElementById('miniMap');
const miniCtx = miniMap ? miniMap.getContext('2d') : null;
const explanationEl = document.getElementById('explanation');
const tradeoffPanel = document.getElementById('tradeoffPanel');
const radialMenu = document.getElementById('radialMenu');
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
const pollResultsList = document.getElementById('pollResultsList');
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
let roomId = '';
let participantId = '';
let roomState = null;
let participants = [];
let bots = [];
let pendingChange = null;
let pendingAnchor = null;
let radialMenuIgnoreUntil = 0;
let player = { x: 0, y: 0 };
let playerLockedUntil = 0;
let selectedProposalId = '';

let tool = 'forest';

const panelMap = {
  tokens: tokensPanel,
  quests: questsPanel,
  milestones: milestonesPanel,
  snapshots: snapshotsPanel,
  multiplayer: multiplayerPanel,
  bots: botsPanel
};

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

function draw() {
  if (!tiles.length) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const startX = viewX;
  const startY = viewY;
  const endX = Math.min(width, viewX + viewWidth);
  const endY = Math.min(height, viewY + viewHeight);
  for (let y = startY; y < endY; y++) {
    for (let x = startX; x < endX; x++) {
      const v = tiles[y][x];
      if (v === 2) {
        ctx.fillStyle = '#4aa3df';
      } else if (v === 1) {
        ctx.fillStyle = '#2a7f62';
      } else if (v === 3) {
        ctx.fillStyle = '#6b6f7a';
      } else if (v === 4) {
        ctx.fillStyle = '#c39b6b';
      } else {
        ctx.fillStyle = '#e2c28b';
      }
      ctx.fillRect((x - viewX) * tileSize, (y - viewY) * tileSize, tileSize, tileSize);
    }
  }

  if (highways.length) {
    ctx.fillStyle = 'rgba(131, 95, 60, 0.85)';
    for (let y = startY; y < endY; y++) {
      for (let x = startX; x < endX; x++) {
        if ((highways[y] && highways[y][x]) || tiles[y][x] === 4) {
          ctx.fillRect((x - viewX) * tileSize, (y - viewY) * tileSize, tileSize, tileSize);
        }
      }
    }
    ctx.strokeStyle = 'rgba(245, 235, 220, 0.65)';
    ctx.lineWidth = Math.max(1, Math.floor(tileSize * 0.06));
    for (let y = startY; y < endY; y++) {
      for (let x = startX; x < endX; x++) {
        if ((highways[y] && highways[y][x]) || tiles[y][x] === 4) {
          const cx = (x - viewX) * tileSize + tileSize / 2;
          const cy = (y - viewY) * tileSize + tileSize / 2;
          ctx.beginPath();
          ctx.moveTo(cx - tileSize * 0.18, cy);
          ctx.lineTo(cx + tileSize * 0.18, cy);
          ctx.stroke();
        }
      }
    }
  }

  const playerRadius = Math.max(4, Math.floor(tileSize * 0.9));
  const botRadius = Math.max(3, Math.floor(tileSize * 0.7));

  participants.forEach(p => {
    if (p.x === undefined || p.y === undefined || p.x < 0 || p.y < 0) return;
    if (p.x < startX || p.x >= endX || p.y < startY || p.y >= endY) return;
    const cx = (p.x - viewX) * tileSize + tileSize / 2;
    const cy = (p.y - viewY) * tileSize + tileSize / 2;
    const isSelf = p.id === participantId;
    ctx.fillStyle = isSelf ? '#ffb300' : '#ffd180';
    ctx.beginPath();
    ctx.arc(cx, cy, playerRadius, 0, Math.PI * 2);
    ctx.fill();
    if (isSelf) {
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(cx, cy, playerRadius + 2, 0, Math.PI * 2);
      ctx.stroke();
    }
  });

  if (!participants.length && player.x >= 0 && player.y >= 0) {
    if (player.x >= startX && player.x < endX && player.y >= startY && player.y < endY) {
    const cx = player.x * tileSize + tileSize / 2;
    const cy = player.y * tileSize + tileSize / 2;
    ctx.fillStyle = '#ffb300';
    ctx.beginPath();
    ctx.arc(cx, cy, playerRadius, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(cx, cy, playerRadius + 2, 0, Math.PI * 2);
    ctx.stroke();
    }
  }

  bots.forEach(b => {
    if (b.x === undefined || b.y === undefined || b.x < 0 || b.y < 0) return;
    if (b.x < startX || b.x >= endX || b.y < startY || b.y >= endY) return;
    ctx.fillStyle = '#ff6f61';
    ctx.beginPath();
    ctx.arc((b.x - viewX) * tileSize + tileSize / 2, (b.y - viewY) * tileSize + tileSize / 2, botRadius, 0, Math.PI * 2);
    ctx.fill();
  });

  if (pendingChange) {
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.8)';
    ctx.lineWidth = 2;
    if (pendingChange.x >= startX && pendingChange.x < endX && pendingChange.y >= startY && pendingChange.y < endY) {
      ctx.strokeRect((pendingChange.x - viewX) * tileSize, (pendingChange.y - viewY) * tileSize, tileSize, tileSize);
    }
  }

  drawMiniMap();
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
    miniCtx.fillStyle = '#ffb300';
    miniCtx.beginPath();
    miniCtx.arc(player.x * sx, player.y * sy, 3, 0, Math.PI * 2);
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
    startAutoNav(miniTarget);
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
  if (data.roomId) {
    roomId = data.roomId;
    if (roomIdInput) roomIdInput.value = roomId;
  }
  if (data.tiles) {
    tiles = data.tiles;
    if (!width || !height) {
      width = tiles[0] ? tiles[0].length : 0;
      height = tiles.length || 0;
      viewWidth = Math.min(width, 48);
      viewHeight = Math.min(height, 36);
      tileSize = Math.max(6, Math.floor(640 / Math.max(viewWidth, 1)));
      canvas.width = viewWidth * tileSize;
      canvas.height = viewHeight * tileSize;
    }
    pendingChange = null;
    if (pollDetails) {
      pollDetails.textContent = 'Select a 1x1 area to start a poll.';
    }
  }
  if (data.highways) {
    highways = data.highways;
  }
  participants = data.participants || [];
  bots = data.bots || [];
  const me = participants.find(p => p.id === participantId);
  if (me) {
    player = { x: me.x, y: me.y };
    playerLockedUntil = me.lockedUntil ? new Date(me.lockedUntil).getTime() : 0;
  } else if (width && height) {
    player = { x: Math.floor(width / 2), y: Math.floor(height / 2) };
  }
  updateViewCenter();
  renderRoomState();
  if (roomState) {
    roomStatus.textContent = `${roomState.roomName} - Negotiation active`;
  }
  updateLockUI();
  renderLockStatus();
  draw();
}

function renderRoomState() {
  renderNeighborStats();
  renderParticipants();
  renderBotProfiles();
  renderLocalProposals();
  renderLockStatus();
  renderPollResults();
  renderProposals();
  renderSharedTokens();
  renderSharedQuests();
  renderMemoryMarkers();
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
    card.appendChild(badges);

    const bars = document.createElement('div');
    bars.className = 'profile-bars';
    const traits = [
      { key: 'forest', label: 'Forest', color: '#2a7f62' },
      { key: 'water', label: 'Water', color: '#4aa3df' },
      { key: 'clear', label: 'Clear', color: '#e2c28b' },
      { key: 'industry', label: 'Industry', color: '#6b6f7a' },
      { key: 'consensus', label: 'Consensus', color: '#8e8e8e' },
      { key: 'mobility', label: 'Mobility', color: '#ffb300' }
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
    meta.textContent = `${p.status} - +${p.approvals} / -${p.rejections} - need ${needed}${timeLabel}`;
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
  const proposals = (roomState.proposals || []).filter(p => isLocalProposal(p) && p.status === 'PENDING');
  proposals.forEach(p => {
    const row = document.createElement('div');
    row.className = 'list-item';
    const title = document.createElement('div');
    const label = p.botGenerated ? `Bot: ${p.botLabel || 'Neighbor'}` : p.proposerName;
    title.textContent = `Proposal by ${label}`;
    const meta = document.createElement('div');
    const needed = p.requiredVotes || 0;
    meta.textContent = `${p.status} - +${p.approvals} / -${p.rejections} - need ${needed}`;
    row.appendChild(title);
    row.appendChild(meta);
    if (!p.botGenerated && myName && p.proposerName === myName) {
      const note = document.createElement('div');
      note.textContent = 'You started this poll.';
      row.appendChild(note);
    } else {
      const actions = document.createElement('div');
      actions.className = 'multiplayer-actions';
      const yesBtn = document.createElement('button');
      yesBtn.className = 'fallback-button';
      yesBtn.textContent = 'Yes';
      yesBtn.addEventListener('click', () => voteProposalById(p.id, true));
      const noBtn = document.createElement('button');
      noBtn.className = 'fallback-button';
      noBtn.textContent = 'No';
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

function renderPollResults() {
  if (!pollResultsList || !roomState) return;
  pollResultsList.innerHTML = '';
  const proposals = (roomState.proposals || []).slice(0, 6);
  proposals.forEach(p => {
    const row = document.createElement('div');
    row.className = 'list-item';
    const title = document.createElement('div');
    const label = p.botGenerated ? `Bot: ${p.botLabel || 'Neighbor'}` : p.proposerName;
    title.textContent = `${p.status} - ${label}`;
    const meta = document.createElement('div');
    const needed = p.requiredVotes || 0;
    meta.textContent = `+${p.approvals} / -${p.rejections} - need ${needed}`;
    row.appendChild(title);
    row.appendChild(meta);
    pollResultsList.appendChild(row);
  });
  if (!proposals.length) {
    const empty = document.createElement('div');
    empty.className = 'md-typescale-body-medium';
    empty.textContent = 'No polls yet.';
    pollResultsList.appendChild(empty);
  }
  setTimeout(() => {
    pollResultsList.innerHTML = '';
  }, 3000);
}

function isLocalProposal(p) {
  if (!p || p.anchorX === undefined || p.anchorY === undefined) return true;
  if (!roomState || !roomState.neighborRadius) return true;
  const dx = p.anchorX - player.x;
  const dy = p.anchorY - player.y;
  return dx * dx + dy * dy <= roomState.neighborRadius * roomState.neighborRadius;
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

async function moveTo(x, y) {
  if (!roomId) return;
  const res = await fetch(`/room/${roomId}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, x, y })
  });
  if (res.ok) {
    const data = await res.json();
    setRoomStateFromResponse(data);
    updateViewCenter();
  } else {
    const message = await res.text();
    tradeoffPanel.textContent = message || 'Move rejected.';
    roomStatus.textContent = message || 'Move rejected.';
  }
}

window.addEventListener('keydown', (evt) => {
  if (isLocked()) return;
  stopAutoNav();
  let nx = player.x;
  let ny = player.y;
  const onRoad = (highways[player.y] && highways[player.y][player.x]) || tiles[player.y][player.x] === 4;
  const step = onRoad ? 2 : 1;
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
    if (roomId) {
      refreshRoomState();
    }
  }, 10000);

  setInterval(() => {
    renderLockStatus();
  }, 1000);
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
      tool = opt.tool;
      setPendingChange(pendingAnchor.x, pendingAnchor.y, opt.tool);
      await proposeToRoom();
      closeRadialMenu();
      pendingAnchor = null;
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

function startAutoNav(target) {
  if (!target) return;
  autoNavTarget = { x: target.x, y: target.y };
  if (autoNavTimer) return;
  autoNavTimer = setInterval(() => {
    if (!autoNavTarget) return;
    if (isLocked()) return;
    if (autoNavInFlight) return;
    const dx = autoNavTarget.x - player.x;
    const dy = autoNavTarget.y - player.y;
    if (Math.abs(dx) <= 0 && Math.abs(dy) <= 0) {
      stopAutoNav();
      miniTarget = null;
      draw();
      return;
    }
    const onRoad = (highways[player.y] && highways[player.y][player.x]) || tiles[player.y][player.x] === 4;
    const step = onRoad ? 2 : 1;
    const choice = pickRoadBiasedStep(dx, dy, step);
    let nx = choice.x;
    let ny = choice.y;
    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
      stopAutoNav();
      return;
    }
    autoNavInFlight = true;
    moveTo(nx, ny).finally(() => {
      autoNavInFlight = false;
    });
  }, 350);
}

function pickRoadBiasedStep(dx, dy, step) {
  const candidates = [];
  if (dx !== 0) {
    const nx = player.x + (dx > 0 ? Math.min(step, Math.abs(dx)) : -Math.min(step, Math.abs(dx)));
    candidates.push({ x: nx, y: player.y });
  }
  if (dy !== 0) {
    const ny = player.y + (dy > 0 ? Math.min(step, Math.abs(dy)) : -Math.min(step, Math.abs(dy)));
    candidates.push({ x: player.x, y: ny });
  }
  if (candidates.length === 1) {
    return candidates[0];
  }
  const inBounds = candidates.filter(c => c.x >= 0 && c.x < width && c.y >= 0 && c.y < height);
  const roadFirst = inBounds.filter(c => (highways[c.y] && highways[c.y][c.x]) || tiles[c.y][c.x] === 4);
  if (roadFirst.length) {
    return roadFirst[0];
  }
  return inBounds[0] || candidates[0];
}

function stopAutoNav() {
  autoNavTarget = null;
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
  const res = await fetch('/room/create', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, name: 'Consensus Field' })
  });
  if (!res.ok) {
    roomStatus.textContent = 'Failed to create room.';
    return;
  }
  const data = await res.json();
  roomId = data.roomId;
  participantId = data.participantId;
  setRoomStateFromResponse(data);
  width = tiles[0] ? tiles[0].length : 0;
  height = tiles.length || 0;
  viewWidth = Math.min(width, 48);
  viewHeight = Math.min(height, 36);
  tileSize = Math.max(6, Math.floor(640 / Math.max(viewWidth, 1)));
  canvas.width = viewWidth * tileSize;
  canvas.height = viewHeight * tileSize;
  updateViewCenter();
  draw();
  initMultiplayer();
  initBots();
}

init();
