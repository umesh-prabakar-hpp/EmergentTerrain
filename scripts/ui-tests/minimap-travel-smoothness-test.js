/* eslint-disable no-console */
// UI test: measure minimap travel smoothness (movement update rate + move latency).
// Usage:
//   START_APP=true node scripts/ui-tests/minimap-travel-smoothness-test.js
//
// Optional:
//   BASE_URL=http://localhost:8080/game/test?debugMinimap=1
//   APP_READY_URL=http://localhost:8080/game/test?debugMinimap=1
//   APP_START_TIMEOUT_MS=120000
//   WATCHDOG_MS=150000
//   SAMPLE_WINDOW_MS=8000
//   SAMPLE_INTERVAL_MS=200
//
// Requires Playwright installed:
//   cd scripts/ui-tests && npm ci
//   cd scripts/ui-tests && npx playwright install chromium

const { chromium } = require('playwright');
const { spawn } = require('child_process');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080/game/test?debugMinimap=1';
const APP_READY_URL = process.env.APP_READY_URL || BASE_URL;
const START_APP = String(process.env.START_APP || '').toLowerCase() === 'true'
  || process.env.START_APP === '1';
const FORCE_STOP = String(process.env.FORCE_STOP || '').toLowerCase() === 'true'
  || process.env.FORCE_STOP === '1';
const APP_START_TIMEOUT_MS = Number(process.env.APP_START_TIMEOUT_MS || 90000);
const STEP_TIMEOUT_MS = 15000;
const WATCHDOG_MS = Number(process.env.WATCHDOG_MS || (START_APP ? APP_START_TIMEOUT_MS + 30000 : 30000));
const SAMPLE_WINDOW_MS = Number(process.env.SAMPLE_WINDOW_MS || 8000);
const SAMPLE_INTERVAL_MS = Number(process.env.SAMPLE_INTERVAL_MS || 200);
const MAVEN_CMD = process.env.MAVEN_CMD || 'mvn';
const MAVEN_ARGS = (process.env.MAVEN_ARGS || 'spring-boot:run').split(' ');

const MINI_MAP_SELECTOR = '#miniMap';
const ROUTE_CHOICES_BUTTON = '#routeChoices .list-item .fallback-button';

async function waitForUrl(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2000);
      const res = await fetch(url, { signal: controller.signal });
      clearTimeout(timer);
      if (res.ok) return true;
    } catch (err) {
      // retry
    }
    await new Promise(r => setTimeout(r, 1500));
  }
  return false;
}

function summarizeSamples(samples) {
  let changes = 0;
  let last = null;
  const deltas = [];
  for (const s of samples) {
    if (!last) {
      last = s;
      continue;
    }
    if (s.x !== last.x || s.y !== last.y) {
      changes += 1;
      deltas.push(s.t - last.t);
      last = s;
    }
  }
  const avgDelta = deltas.length
    ? deltas.reduce((a, b) => a + b, 0) / deltas.length
    : 0;
  const maxDelta = deltas.length ? Math.max(...deltas) : 0;
  return {
    changes,
    avgDelta,
    maxDelta
  };
}

async function run() {
  const watchdog = setTimeout(() => {
    console.error(`FAIL: watchdog timeout after ${WATCHDOG_MS}ms`);
    process.exit(1);
  }, WATCHDOG_MS);

  let browser = null;
  let appProc = null;
  try {
    if (START_APP) {
      const ready = await waitForUrl(APP_READY_URL, 3000);
      if (!ready) {
        console.log('Starting app for test...');
        appProc = spawn(MAVEN_CMD, MAVEN_ARGS, {
          cwd: process.cwd(),
          env: {
            ...process.env,
            APP_E2E_ENABLED: 'true'
          },
          stdio: 'inherit',
          shell: true
        });
        const started = await waitForUrl(APP_READY_URL, APP_START_TIMEOUT_MS);
        if (!started) {
          throw new Error(`App did not become ready at ${APP_READY_URL}`);
        }
      } else {
        console.log('App already running, skipping start.');
      }
    }

    console.log('Launching Chromium...');
    browser = await chromium.launch({ headless: true, timeout: STEP_TIMEOUT_MS });
    const page = await browser.newPage();
    page.setDefaultTimeout(STEP_TIMEOUT_MS);
    page.on('console', (msg) => {
      if (msg.type() === 'log' && msg.text().includes('[minimap]')) {
        console.log(`Page log: ${msg.text()}`);
        return;
      }
      if (['error', 'warning'].includes(msg.type())) {
        console.warn(`Page ${msg.type()}:`, msg.text());
      }
    });

    console.log(`Opening ${BASE_URL}`);
    await page.goto(BASE_URL, { waitUntil: 'domcontentloaded', timeout: STEP_TIMEOUT_MS });
    await page.waitForSelector(MINI_MAP_SELECTOR, { timeout: STEP_TIMEOUT_MS, state: 'attached' });
    await page.waitForFunction(() => {
      const el = document.getElementById('roomStatus');
      const text = el ? el.textContent || '' : '';
      return text.includes('Joined room') || text.includes('Negotiation active');
    }, { timeout: STEP_TIMEOUT_MS });
    await page.waitForFunction(() => {
      return typeof width === 'number' && width > 0
        && typeof height === 'number' && height > 0
        && player && Number.isFinite(player.x) && Number.isFinite(player.y);
    }, { timeout: STEP_TIMEOUT_MS });

    // Wrap moveTo to capture latency metrics and fetch to capture timing headers.
    await page.evaluate(() => {
      if (!window.__moveMetrics && typeof moveTo === 'function') {
        window.__moveMetrics = [];
        const original = moveTo;
        // eslint-disable-next-line no-global-assign
        moveTo = async function wrappedMoveTo(x, y) {
          const t0 = performance.now();
          try {
            return await original(x, y);
          } finally {
            window.__moveMetrics.push({ x, y, ms: performance.now() - t0 });
          }
        };
      }
      if (!window.__moveTimingHeaders && typeof fetch === 'function') {
        window.__moveTimingHeaders = [];
        window.__moveErrors = [];
        const originalFetch = fetch;
        // eslint-disable-next-line no-global-assign
        fetch = async function wrappedFetch(...args) {
          const res = await originalFetch(...args);
          try {
            const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
            if (url && url.includes('/move')) {
              const timing = res.headers.get('server-timing') || res.headers.get('x-move-timing') || '';
              window.__moveTimingHeaders.push(timing);
              if (!res.ok) {
                res.clone().text().then(text => {
                  window.__moveErrors.push({ status: res.status, text });
                }).catch(() => {});
              }
            }
          } catch (err) {
            // ignore
          }
          return res;
        };
      }
    });

    // Click minimap on opposite corner relative to player for a longer path.
    await page.evaluate(() => {
      const mini = document.querySelector('#miniMap');
      if (!mini) throw new Error('Mini map canvas not found.');
      const rect = mini.getBoundingClientRect();
      const tx = player.x < width / 2 ? Math.max(0, width - 2) : 1;
      const ty = player.y < height / 2 ? Math.max(0, height - 2) : 1;
      const x = rect.left + rect.width * (tx / width);
      const y = rect.top + rect.height * (ty / height);
      if (!Number.isFinite(x) || !Number.isFinite(y)) {
        throw new Error(`Invalid minimap click coords x=${x} y=${y} width=${width} height=${height}`);
      }
      mini.dispatchEvent(new MouseEvent('click', { clientX: x, clientY: y, bubbles: true }));
    });

    await page.waitForSelector(ROUTE_CHOICES_BUTTON, { timeout: STEP_TIMEOUT_MS, state: 'attached' });
    await page.evaluate(() => {
      const overlay = document.querySelector('.diag-overlay');
      if (overlay) {
        overlay.remove();
      }
    });
    try {
      await page.click(ROUTE_CHOICES_BUTTON, { timeout: 3000 });
    } catch (err) {
      await page.evaluate(() => {
        if (typeof beginAutoNav === 'function' && pendingRoutes && pendingRoutes.road) {
          beginAutoNav(pendingRoutes.road);
        }
      });
    }

    const samples = [];
    const start = Date.now();
    while (Date.now() - start < SAMPLE_WINDOW_MS) {
      const state = await page.evaluate(() => ({
        t: performance.now(),
        x: typeof playerRender !== 'undefined' && playerRender && Number.isFinite(playerRender.x) ? playerRender.x : (player ? player.x : null),
        y: typeof playerRender !== 'undefined' && playerRender && Number.isFinite(playerRender.y) ? playerRender.y : (player ? player.y : null),
        rawX: player ? player.x : null,
        rawY: player ? player.y : null,
        inFlight: typeof autoNavInFlight !== 'undefined' ? autoNavInFlight : null,
        index: typeof autoNavIndex !== 'undefined' ? autoNavIndex : null,
        total: typeof autoNavPath !== 'undefined' && autoNavPath ? autoNavPath.length : 0,
        active: typeof autoNavTimer !== 'undefined' && autoNavTimer ? true : false
      }));
      samples.push(state);
      if (!state.active && samples.length > 5) break;
      await page.waitForTimeout(SAMPLE_INTERVAL_MS);
    }

    const metrics = summarizeSamples(samples);
    const rawMetrics = summarizeSamples(samples.map(s => ({ t: s.t, x: s.rawX, y: s.rawY })));
    const moveMetrics = await page.evaluate(() => window.__moveMetrics || []);
    const moveTimingHeaders = await page.evaluate(() => window.__moveTimingHeaders || []);
    const moveErrors = await page.evaluate(() => window.__moveErrors || []);
    const moveAvg = moveMetrics.length
      ? moveMetrics.reduce((a, b) => a + b.ms, 0) / moveMetrics.length
      : 0;
    const moveMax = moveMetrics.length ? Math.max(...moveMetrics.map(m => m.ms)) : 0;

    console.log('Movement sample summary:');
    console.log(`- samples: ${samples.length}`);
    console.log(`- render position changes: ${metrics.changes}`);
    console.log(`- avg render interval between changes: ${metrics.avgDelta.toFixed(1)}ms`);
    console.log(`- max render interval between changes: ${metrics.maxDelta.toFixed(1)}ms`);
    console.log(`- raw position changes: ${rawMetrics.changes}`);
    console.log(`- avg raw interval between changes: ${rawMetrics.avgDelta.toFixed(1)}ms`);
    console.log(`- max raw interval between changes: ${rawMetrics.maxDelta.toFixed(1)}ms`);
    console.log(`- move requests: ${moveMetrics.length}`);
    console.log(`- avg move request duration: ${moveAvg.toFixed(1)}ms`);
    console.log(`- max move request duration: ${moveMax.toFixed(1)}ms`);
    if (moveTimingHeaders.length) {
      console.log('- server timing headers (last 3):');
      moveTimingHeaders.slice(-3).forEach((h, i) => {
        console.log(`  ${moveTimingHeaders.length - 3 + i + 1}: ${h}`);
      });
    }
    if (moveErrors.length) {
      console.log('- move errors (last 3):');
      moveErrors.slice(-3).forEach((err, i) => {
        const idx = moveErrors.length - 3 + i + 1;
        console.log(`  ${idx}: ${err.status} ${err.text}`);
      });
    }

    if (metrics.changes < 3) {
      throw new Error('Render position did not update enough during auto-nav.');
    }
  } finally {
    clearTimeout(watchdog);
    if (browser) {
      await browser.close();
    }
    if (appProc) {
      console.log('Stopping app...');
      appProc.kill('SIGTERM');
    }
    console.log('Force-stopping any process on port 8080...');
    try {
      const { execSync } = require('child_process');
      const output = execSync(
        'powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess"',
        { stdio: ['ignore', 'pipe', 'ignore'] }
      ).toString().trim();
      if (output) {
        output.split(/\\s+/).forEach((pid) => {
          if (pid) {
            execSync(`powershell -NoProfile -Command \"Stop-Process -Id ${pid} -Force\"`, { stdio: 'ignore' });
          }
        });
      }
    } catch (err) {
      // ignore if no process or insufficient permissions
    }
  }
}

run().catch((err) => {
  console.error('FAIL:', err.message);
  process.exitCode = 1;
});
