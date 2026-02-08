const token = window.APP_TOKEN;
const canvas = document.getElementById('world');
const ctx = canvas.getContext('2d');
const explanationEl = document.getElementById('explanation');
const applyBtn = document.getElementById('applyChanges');
const confirmYes = document.getElementById('confirmYes');
const confirmNo = document.getElementById('confirmNo');

let tool = 'forest';
let tiles = [];
let baseTiles = [];
let width = 0;
let height = 0;
let tileSize = 16;
let pendingDelta = null;
let isPainting = false;

function copyTiles(src) {
  return src.map(row => row.slice());
}

function draw() {
  if (!tiles.length) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = tiles[y][x];
      ctx.fillStyle = v === 1 ? '#2a7f62' : '#e2c28b';
      ctx.fillRect(x * tileSize, y * tileSize, tileSize, tileSize);
    }
  }
}

function applyTool(x, y) {
  if (x < 0 || x >= width || y < 0 || y >= height) return;
  tiles[y][x] = tool === 'forest' ? 1 : 0;
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
});

canvas.addEventListener('mousemove', (evt) => {
  if (!isPainting) return;
  const { x, y } = gridFromEvent(evt);
  applyTool(x, y);
});

window.addEventListener('mouseup', () => {
  isPainting = false;
});

for (const btn of document.querySelectorAll('.tools button[data-tool]')) {
  btn.addEventListener('click', () => {
    tool = btn.dataset.tool;
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
  pendingDelta = computeDelta();
  const res = await fetch('/game/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, delta: pendingDelta })
  });
  const data = await res.json();
  explanationEl.textContent = data.explanation;
});

confirmYes.addEventListener('click', async () => {
  if (!pendingDelta) return;
  await fetch('/game/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, delta: pendingDelta, confirmed: true })
  });
  baseTiles = copyTiles(tiles);
  pendingDelta = null;
  explanationEl.textContent = 'Confirmed. Keep exploring.';
});

confirmNo.addEventListener('click', () => {
  explanationEl.textContent = 'Adjust the terrain to better reflect your preferences.';
});

async function init() {
  const res = await fetch('/game/state', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token })
  });
  const data = await res.json();
  width = data.width;
  height = data.height;
  tiles = data.tiles;
  baseTiles = copyTiles(tiles);
  tileSize = Math.floor(640 / width);
  canvas.width = width * tileSize;
  canvas.height = height * tileSize;
  draw();
}

init();
