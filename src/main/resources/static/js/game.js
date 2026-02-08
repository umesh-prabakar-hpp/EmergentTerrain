const token = window.APP_TOKEN;
const canvas = document.getElementById('world');
const ctx = canvas.getContext('2d');
const explanationEl = document.getElementById('explanation');
const liveQr = document.getElementById('liveQr');
const qrCaption = document.getElementById('qrCaption');
const tradeoffPanel = document.getElementById('tradeoffPanel');
const ahaPanel = document.getElementById('ahaPanel');
const snapshotList = document.getElementById('snapshotList');
const loadLatestSnapshotBtn = document.getElementById('loadLatestSnapshot');
const questList = document.getElementById('questList');
const seasonPanel = document.getElementById('seasonPanel');
const observerPanel = document.getElementById('observerPanel');
const observerText = document.getElementById('observerText');
const observerAck = document.getElementById('observerAck');
const milestoneList = document.getElementById('milestoneList');
const tokensPanel = document.getElementById('tokensPanel');
const questsPanel = document.getElementById('questsPanel');
const milestonesPanel = document.getElementById('milestonesPanel');
const snapshotsPanel = document.getElementById('snapshotsPanel');
const insightPointsEl = document.getElementById('insightPoints');
const patternStabilityEl = document.getElementById('patternStability');
const cognitiveEnergyEl = document.getElementById('cognitiveEnergy');
const toggleFogBtn = document.getElementById('toggleFog');
const toggleTimeBtn = document.getElementById('toggleTime');
const toolWaterBtn = document.getElementById('toolWater');
const applyBtn = document.getElementById('applyChanges');
const confirmYes = document.getElementById('confirmYes');
const confirmNo = document.getElementById('confirmNo');
const toggleTokensBtn = document.getElementById('toggleTokens');
const toggleQuestsBtn = document.getElementById('toggleQuests');
const toggleMilestonesBtn = document.getElementById('toggleMilestones');
const toggleSnapshotsBtn = document.getElementById('toggleSnapshots');

let tool = 'forest';
let tiles = [];
let baseTiles = [];
let regionIds = [];
let regions = [];
let fog = [];
let width = 0;
let height = 0;
let tileSize = 16;
let pendingDelta = null;
let isPainting = false;
let player = { x: 0, y: 0 };
let lastClick = { x: -1, y: -1 };
let discoveredRegions = new Set();
let tokens = { insight: 0, stability: 0, energy: 0 };
let fogEnabled = true;
let duskEnabled = false;
let lockUntil = 0;
let observerTimer = null;
let lastActionAt = Date.now();
let lastDelta = null;
let lastObserverPrompt = '';
let lastStateHash = '';
let snapshots = [];
let quests = [];
let season = 'Exploration Phase';
let lastSeasonSwitch = Date.now();
let milestones = [];

function copyTiles(src) {
  return src.map(row => row.slice());
}

function draw() {
  if (!tiles.length) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = tiles[y][x];
      if (v === 2) {
        ctx.fillStyle = '#4aa3df';
      } else {
        ctx.fillStyle = v === 1 ? '#2a7f62' : '#e2c28b';
      }
      ctx.fillRect(x * tileSize, y * tileSize, tileSize, tileSize);
    }
  }

  if (fog.length && fogEnabled) {
    ctx.fillStyle = 'rgba(15, 22, 15, 0.45)';
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        if (fog[y][x]) {
          ctx.fillRect(x * tileSize, y * tileSize, tileSize, tileSize);
        }
      }
    }
  }

  ctx.fillStyle = '#ffb300';
  ctx.beginPath();
  ctx.arc(player.x * tileSize + tileSize / 2, player.y * tileSize + tileSize / 2, tileSize / 3, 0, Math.PI * 2);
  ctx.fill();

  if (duskEnabled) {
    ctx.fillStyle = 'rgba(25, 30, 45, 0.25)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
  }
}

function applyTool(x, y) {
  if (isLocked()) return;
  if (x < 0 || x >= width || y < 0 || y >= height) return;
  if (tool === 'water') {
    tiles[y][x] = 2;
  } else {
    if (tiles[y][x] === 2) return;
    tiles[y][x] = tool === 'forest' ? 1 : 0;
  }
  lastActionAt = Date.now();
  revealAt(x, y, 4);
  draw();
}

function gridFromEvent(evt) {
  const rect = canvas.getBoundingClientRect();
  const x = Math.floor((evt.clientX - rect.left) / tileSize);
  const y = Math.floor((evt.clientY - rect.top) / tileSize);
  return { x, y };
}

canvas.addEventListener('mousedown', (evt) => {
  isPainting = true;
  const { x, y } = gridFromEvent(evt);
  applyTool(x, y);
  lastClick = { x, y };
});

canvas.addEventListener('mousemove', (evt) => {
  if (!isPainting) return;
  const { x, y } = gridFromEvent(evt);
  applyTool(x, y);
});

window.addEventListener('mouseup', () => {
  isPainting = false;
});

canvas.addEventListener('click', (evt) => {
  const { x, y } = gridFromEvent(evt);
  if (x === lastClick.x && y === lastClick.y) {
    showRegionInfo(x, y);
  }
});

for (const btn of document.querySelectorAll('[data-tool]')) {
  btn.addEventListener('click', () => {
    tool = btn.dataset.tool;
  });
}

const panelMap = {
  tokens: tokensPanel,
  quests: questsPanel,
  milestones: milestonesPanel,
  snapshots: snapshotsPanel
};

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

for (const btn of document.querySelectorAll('[data-close]')) {
  btn.addEventListener('click', () => {
    const target = btn.getAttribute('data-close');
    const panel = document.getElementById(target);
    if (panel) panel.classList.remove('active');
  });
}

if (observerAck) {
  observerAck.addEventListener('click', () => {
    if (observerPanel) observerPanel.classList.remove('active');
  });
}

function computeDelta() {
  let forestAdded = 0;
  let forestRemoved = 0;
  let clearAdded = 0;

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const b = baseTiles[y][x];
      const c = tiles[y][x];
      if (b === 0 && c === 1) forestAdded++;
      if (b === 1 && c === 0) forestRemoved++;
      if (b === 1 && c === 0) clearAdded++;
    }
  }
  return { forestTilesAdded: forestAdded, forestTilesRemoved: forestRemoved, clearTilesAdded: clearAdded };
}

applyBtn.addEventListener('click', async () => {
  if (isLocked()) return;
  pendingDelta = computeDelta();
  const res = await fetch('/game/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, delta: pendingDelta })
  });
  const data = await res.json();
  explanationEl.textContent = data.explanation;
  tradeoffPanel.textContent = tradeoffText(pendingDelta);
  ahaPanel.textContent = contextualExplanation(pendingDelta);
  lastDelta = pendingDelta;
  observerPrompt('beforeConfirm');
  awardTokens('explain');
  checkQuests();
  revealAt(player.x, player.y, 5);
  updateLiveQr(false);
});

confirmYes.addEventListener('click', async () => {
  if (isLocked()) return;
  if (!pendingDelta) return;
  await fetch('/game/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, delta: pendingDelta, confirmed: true })
  });
  baseTiles = copyTiles(tiles);
  pendingDelta = null;
  explanationEl.textContent = 'Confirmed. Keep exploring.';
  tradeoffPanel.textContent = '';
  observerPrompt('confirm');
  awardTokens('confirm');
  checkQuests();
  maybeAha();
  revealAt(player.x, player.y, 6);
  startLock(6);
  updateLiveQr(true);
});

confirmNo.addEventListener('click', () => {
  explanationEl.textContent = 'Adjust the terrain to better reflect your preferences.';
  tradeoffPanel.textContent = '';
  observerPrompt('reconsider');
});

async function init() {
  const res = await fetch('/game/state', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, snapshotId: window.SNAPSHOT_ID })
  });
  const data = await res.json();
  width = data.width;
  height = data.height;
  tiles = data.tiles;
  regionIds = data.regionIds || [];
  regions = data.regions || [];
  fog = data.fog || [];
  player = data.player || { x: Math.floor(width / 2), y: Math.floor(height / 2) };
  snapshots = data.snapshots || [];
  baseTiles = copyTiles(tiles);
  tileSize = Math.floor(640 / width);
  canvas.width = width * tileSize;
  canvas.height = height * tileSize;
  revealAt(player.x, player.y, 3);
  updateTokensUI();
  renderSnapshotList();
  initSeason(data.questState);
  initQuests(data.questState);
  initMilestones();
  if (data.questState && data.questState.lastObserverPrompt) {
    lastObserverPrompt = data.questState.lastObserverPrompt;
    if (observerText) observerText.textContent = lastObserverPrompt;
  }
  initObserver();
  if (window.SNAPSHOT_ID) {
    qrCaption.textContent = "You've changed since then. Explore what's different.";
  }
  updateLiveQr(false);
  draw();
}

init();

function revealAt(cx, cy, radius) {
  if (!fog.length) return;
  for (let y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
    for (let x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
      const dx = x - cx;
      const dy = y - cy;
      if (dx * dx + dy * dy <= radius * radius) {
        fog[y][x] = false;
      }
    }
  }
}

function showRegionInfo(x, y) {
  if (!regionIds.length || !regions.length) return;
  const regionId = regionIds[y][x];
  const info = regions.find(r => r.id === regionId);
  if (!info) return;
  explanationEl.textContent = info.explanation || `${info.name}: ${info.meaning}`;
  if (!discoveredRegions.has(regionId)) {
    discoveredRegions.add(regionId);
    awardTokens('explore');
    checkQuests();
    maybeAha();
  }
}

window.addEventListener('keydown', (evt) => {
  if (isLocked()) return;
  let nx = player.x;
  let ny = player.y;
  if (evt.key === 'ArrowUp' || evt.key === 'w') ny -= 1;
  if (evt.key === 'ArrowDown' || evt.key === 's') ny += 1;
  if (evt.key === 'ArrowLeft' || evt.key === 'a') nx -= 1;
  if (evt.key === 'ArrowRight' || evt.key === 'd') nx += 1;
  if (nx === player.x && ny === player.y) return;
  if (nx < 0 || nx >= width || ny < 0 || ny >= height) return;
  player = { x: nx, y: ny };
  revealAt(player.x, player.y, 3);
  draw();
  const regionId = regionIds[ny] ? regionIds[ny][nx] : null;
  if (regionId !== null && !discoveredRegions.has(regionId)) {
    discoveredRegions.add(regionId);
    awardTokens('explore');
    maybeAha();
  }
});

toggleFogBtn.addEventListener('click', () => {
  if (isLocked()) return;
  fogEnabled = !fogEnabled;
  toggleFogBtn.textContent = fogEnabled ? 'Hide Fog' : 'Show Fog';
  draw();
});

toggleTimeBtn.addEventListener('click', () => {
  if (isLocked()) return;
  duskEnabled = !duskEnabled;
  toggleTimeBtn.textContent = duskEnabled ? 'Clear Dusk' : 'Toggle Dusk';
  draw();
});

function awardTokens(type) {
  if (type === 'confirm') {
    tokens.insight += 3;
    tokens.stability += 2;
  } else if (type === 'explore') {
    tokens.energy += 2;
  } else if (type === 'explain') {
    tokens.insight += 1;
  }
  updateTokensUI();
}

function updateTokensUI() {
  insightPointsEl.textContent = tokens.insight;
  patternStabilityEl.textContent = tokens.stability;
  cognitiveEnergyEl.textContent = tokens.energy;
  updateUnlocks();
  checkMilestones();
}

function maybeAha() {
  if (!regions.length) return;
  if (tokens.insight < 3) return;
  const totals = regionTotals();
  const forestShare = totals.forest / totals.total;
  const clearShare = totals.clear / totals.total;
  const waterShare = totals.water / totals.total;
  let message = 'Your terrain hints at balanced preferences.';
  if (forestShare > 0.45 && clearShare > 0.25) {
    message = 'You tend to favor structured freedom — open areas surrounded by forests.';
  } else if (waterShare > 0.18) {
    message = 'Your landscape prioritizes emotional flow — water pockets anchor the pattern.';
  } else if (forestShare > 0.5) {
    message = 'Depth and complexity dominate — dense clusters suggest introspective focus.';
  } else if (clearShare > 0.5) {
    message = 'Clarity rises first — open clearings dominate your pattern.';
  }
  ahaPanel.textContent = message;
}

function tradeoffText(delta) {
  if (!delta) return '';
  const lines = [];
  if (delta.forestTilesAdded > delta.forestTilesRemoved) {
    lines.push('More forest increases stability but may slow exploration.');
  }
  if (delta.clearTilesAdded > 0) {
    lines.push('More open fields boost adaptability but can fragment cohesion.');
  }
  if (lines.length === 0) {
    lines.push('This change may reduce balance in another region.');
  }
  return lines.join(' ');
}

function contextualExplanation(delta) {
  if (!delta) return '';
  if (delta.clearTilesAdded > 0) {
    return 'You increased openness here. People often do this when they want flexibility without chaos.';
  }
  if (delta.forestTilesAdded > delta.forestTilesRemoved) {
    return 'You leaned into density. People often do this when they want depth without noise.';
  }
  return 'You adjusted the balance. People often do this when they want change without losing themselves.';
}

function startLock(seconds) {
  lockUntil = Date.now() + seconds * 1000;
  updateLockUI();
  const timer = setInterval(() => {
    updateLockUI();
    if (!isLocked()) {
      clearInterval(timer);
    }
  }, 500);
}

function isLocked() {
  return Date.now() < lockUntil;
}

function updateLockUI() {
  const locked = isLocked();
  applyBtn.disabled = locked;
  confirmYes.disabled = locked;
  confirmNo.disabled = locked;
  if (toolWaterBtn) toolWaterBtn.disabled = locked || tokens.insight < 5;
  if (toggleFogBtn) toggleFogBtn.disabled = locked || tokens.energy < 4;
  if (toggleTimeBtn) toggleTimeBtn.disabled = locked || tokens.stability < 4;
  if (locked) {
    const remaining = Math.ceil((lockUntil - Date.now()) / 1000);
    tradeoffPanel.textContent = `Terrain is settling. Please wait ${remaining}s.`;
  } else if (tradeoffPanel.textContent.startsWith('Terrain is settling')) {
    tradeoffPanel.textContent = '';
  }
}

function updateUnlocks() {
  if (toolWaterBtn) {
    toolWaterBtn.disabled = tokens.insight < 5;
  }
  if (toggleFogBtn) {
    toggleFogBtn.disabled = tokens.energy < 4;
  }
  if (toggleTimeBtn) {
    toggleTimeBtn.disabled = tokens.stability < 4;
  }
}

async function updateLiveQr(saveSnapshot) {
  if (!liveQr) return;
  const endpoint = saveSnapshot ? '/game/snapshot' : '/game/qr/state';
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, tiles })
  });
  const data = await res.json();
  lastStateHash = data.stateHash || lastStateHash;
  const url = data.qrData || '';
  const base = window.location.origin;
  const full = url.startsWith('http') ? url : `${base}${url}`;
  liveQr.src = `/qr/url?data=${encodeURIComponent(full)}`;
  qrCaption.textContent = buildQrCaption();
  if (data.snapshotId && saveSnapshot) {
    snapshots.unshift({
      id: data.snapshotId,
      stateHash: data.stateHash || '',
      createdAt: new Date().toISOString()
    });
    snapshots = snapshots.slice(0, 10);
    renderSnapshotList();
  }
}

function buildQrCaption() {
  const totals = regionTotals();
  const forestShare = totals.forest / totals.total;
  const clearShare = totals.clear / totals.total;
  const waterShare = totals.water / totals.total;
  let alignment = 'balanced';
  if (forestShare > 0.5) alignment = 'introspective';
  if (clearShare > 0.5) alignment = 'clarity-seeking';
  if (waterShare > 0.2) alignment = 'adaptive';
  const noise = Math.abs(forestShare - clearShare) + waterShare;
  const texture = noise > 0.6 ? 'noisy' : 'symmetrical';
  return `QR reflects ${alignment} alignment with a ${texture} pattern.`;
}

function renderSnapshotList() {
  if (!snapshotList) return;
  if (!snapshots.length) {
    snapshotList.textContent = 'No snapshots yet.';
    if (loadLatestSnapshotBtn) loadLatestSnapshotBtn.disabled = true;
    return;
  }
  snapshotList.innerHTML = '';
  snapshots.forEach(s => {
    const row = document.createElement('div');
    row.className = 'snapshot-item';
    const label = document.createElement('div');
    const when = s.createdAt ? new Date(s.createdAt).toLocaleString() : 'Unknown';
    label.textContent = `Snapshot • ${when}`;
    const btn = document.createElement('button');
    btn.className = 'fallback-button';
    btn.textContent = 'Load';
    btn.addEventListener('click', () => loadSnapshot(s.id));
    row.appendChild(label);
    row.appendChild(btn);
    snapshotList.appendChild(row);
  });
  if (loadLatestSnapshotBtn) loadLatestSnapshotBtn.disabled = false;
}

function initSeason(savedState) {
  if (savedState && savedState.season) {
    season = savedState.season;
  }
  updateSeason();
  setInterval(() => {
    if (Date.now() - lastSeasonSwitch > 60000) {
      cycleSeason();
    }
  }, 5000);
}

function cycleSeason() {
  const options = ['Exploration Phase', 'Consolidation Phase', 'Growth Phase'];
  const index = (options.indexOf(season) + 1) % options.length;
  season = options[index];
  lastSeasonSwitch = Date.now();
  updateSeason();
  saveQuestState();
}

function updateSeason() {
  if (!seasonPanel) return;
  seasonPanel.textContent = `Season: ${season}`;
}

function initQuests(savedState) {
  if (savedState && savedState.quests && savedState.quests.length) {
    quests = savedState.quests;
  } else {
    quests = [
      {
        id: 'clear-path',
        title: 'Create a clear path between two forest regions',
        hint: 'Increase clear tiles while keeping forest clusters intact.',
        completed: false
      },
    {
      id: 'stabilize-zone',
      title: 'Stabilize an unstable zone',
      hint: 'Reduce sharp contrast by balancing forest and clearing.',
      completed: false
    },
    {
      id: 'coherence-boost',
        title: 'Increase terrain coherence by 10%',
        hint: 'Grow connected forests or clearings to reduce fragmentation.',
        completed: false
      }
    ];
  }
  renderQuests();
}

function renderQuests() {
  if (!questList) return;
  questList.innerHTML = '';
  quests.forEach(q => {
    const row = document.createElement('div');
    row.className = `quest-item${q.completed ? ' completed' : ''}`;
    const title = document.createElement('div');
    title.className = 'quest-title';
    title.textContent = q.title;
    const hint = document.createElement('div');
    hint.textContent = q.hint;
    row.appendChild(title);
    row.appendChild(hint);
    questList.appendChild(row);
  });
}

function checkQuests() {
  const totals = regionTotals();
  const forestShare = totals.forest / totals.total;
  const clearShare = totals.clear / totals.total;
  const coherence = computeCoherence();

  quests.forEach(q => {
    if (q.completed) return;
    if (q.id === 'clear-path' && clearShare > 0.35 && forestShare > 0.25) {
      q.completed = true;
      rewardQuest('Deeper explanations unlocked.');
    }
    if (q.id === 'stabilize-zone' && Math.abs(forestShare - clearShare) < 0.1) {
      q.completed = true;
      rewardQuest('Stability patterns strengthened.');
    }
    if (q.id === 'coherence-boost' && coherence > 0.58) {
      q.completed = true;
      rewardQuest('Rare visual effect unlocked: soft dusk.');
      duskEnabled = true;
      draw();
    }
  });

  renderQuests();
  saveQuestState();
}

function rewardQuest(message) {
  tokens.insight += 2;
  tokens.stability += 1;
  updateTokensUI();
  ahaPanel.textContent = message;
}

function computeCoherence() {
  let matches = 0;
  let checks = 0;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = tiles[y][x];
      if (x + 1 < width) {
        checks++;
        if (tiles[y][x + 1] === v) matches++;
      }
      if (y + 1 < height) {
        checks++;
        if (tiles[y + 1][x] === v) matches++;
      }
    }
  }
  return checks === 0 ? 0 : matches / checks;
}

function initMilestones() {
  milestones = [
    { id: 'pattern', title: 'Pattern Recognized', hint: 'Form a clear dominant pattern.', done: false },
    { id: 'consistency', title: 'Internal Consistency Achieved', hint: 'Reach sustained terrain coherence.', done: false },
    { id: 'plateau', title: 'Exploration Plateau Reached', hint: 'Discover all regions.', done: false }
  ];
  renderMilestones();
}

function checkMilestones() {
  const totals = regionTotals();
  const forestShare = totals.forest / totals.total;
  const clearShare = totals.clear / totals.total;
  const waterShare = totals.water / totals.total;
  const coherence = computeCoherence();
  const discoveredAll = regions.length > 0 && discoveredRegions.size >= regions.length;

  milestones.forEach(m => {
    if (m.done) return;
    if (m.id === 'pattern' && (forestShare > 0.5 || clearShare > 0.5 || waterShare > 0.25)) {
      m.done = true;
      ahaPanel.textContent = 'Pattern Recognized. The terrain is starting to echo you.';
    }
    if (m.id === 'consistency' && coherence > 0.6) {
      m.done = true;
      ahaPanel.textContent = 'Internal Consistency Achieved. Things are lining up.';
    }
    if (m.id === 'plateau' && discoveredAll) {
      m.done = true;
      ahaPanel.textContent = 'Exploration Plateau Reached. You can return when you change.';
    }
  });

  renderMilestones();
}

function renderMilestones() {
  if (!milestoneList) return;
  milestoneList.innerHTML = '';
  milestones.forEach(m => {
    const row = document.createElement('div');
    row.className = `quest-item milestone${m.done ? ' completed' : ''}`;
    const title = document.createElement('div');
    title.className = 'quest-title';
    title.textContent = m.title;
    const hint = document.createElement('div');
    hint.textContent = m.done ? 'Unlocked: deeper explanations and new QR layer.' : m.hint;
    row.appendChild(title);
    row.appendChild(hint);
    milestoneList.appendChild(row);
  });
}

async function saveQuestState() {
  try {
    await fetch('/game/quest/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, season, quests, lastObserverPrompt })
    });
  } catch (_) {
  }
}

function initObserver() {
  if (!observerPanel) return;
  if (!lastObserverPrompt) {
    if (observerText) observerText.textContent = 'Listening…';
  }
  if (observerTimer) clearInterval(observerTimer);
  observerTimer = setInterval(() => {
    const idleMs = Date.now() - lastActionAt;
    if (idleMs > 20000) {
      setObserverPrompt('The Observer waits. What feels unresolved?');
    }
  }, 5000);
}

function observerPrompt(type) {
  if (!observerPanel) return;
  const prompts = {
    beforeConfirm: [
      'Does this change feel honest?',
      'What are you protecting with this shift?',
      'What are you trying to make easier?'
    ],
    clear: [
      'Why did you clear this area?',
      'What space are you making for yourself?',
      'What feels lighter here?'
    ],
    forest: [
      'What draws you back into the forest?',
      'Is depth what you need right now?',
      'What feels safer under the canopy?'
    ],
    confirm: [
      'Noted. Let it settle.',
      'This choice is now part of the pattern.',
      'You chose this on purpose. Notice how it feels.'
    ],
    reconsider: [
      'Hesitation can be a signal. What changed?',
      'What made you pull back?',
      'Is this change too fast for now?'
    ]
  };
  if (type === 'beforeConfirm') {
    if (lastDelta && lastDelta.clearTilesAdded > 0) {
      setObserverPrompt(randomPick(prompts.clear));
      return;
    }
    if (lastDelta && lastDelta.forestTilesAdded > lastDelta.forestTilesRemoved) {
      setObserverPrompt(randomPick(prompts.forest));
      return;
    }
    setObserverPrompt(randomPick(prompts.beforeConfirm));
    return;
  }
  if (type === 'confirm') {
    setObserverPrompt(randomPick(prompts.confirm));
    return;
  }
  if (type === 'reconsider') {
    setObserverPrompt(randomPick(prompts.reconsider));
  }
}

function setObserverPrompt(text) {
  if (!observerPanel || !text) return;
  if (text === lastObserverPrompt) return;
  if (observerText) observerText.textContent = text;
  observerPanel.classList.add('active');
  lastObserverPrompt = text;
  saveQuestState();
}

function randomPick(list) {
  if (!list || !list.length) return '';
  return list[Math.floor(Math.random() * list.length)];
}

async function loadSnapshot(snapshotId) {
  if (!snapshotId) return;
  const res = await fetch('/game/snapshot/load', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, snapshotId })
  });
  const data = await res.json();
  tiles = data.tiles || tiles;
  baseTiles = copyTiles(tiles);
  explanationEl.textContent = "You've changed since then. Explore what's different.";
  draw();
}

if (loadLatestSnapshotBtn) {
  loadLatestSnapshotBtn.addEventListener('click', async () => {
    const res = await fetch('/game/snapshot/latest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, tiles })
    });
    if (res.ok) {
      const data = await res.json();
      if (data.snapshotId) {
        await loadSnapshot(data.snapshotId);
      }
    }
  });
}

function regionTotals() {
  let forest = 0;
  let water = 0;
  let total = 0;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      total++;
      if (tiles[y][x] === 1) forest++;
      if (tiles[y][x] === 2) water++;
    }
  }
  return { total, forest, water, clear: total - forest - water };
}
