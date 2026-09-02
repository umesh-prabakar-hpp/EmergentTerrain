/* eslint-disable no-console */
// UI smoke test for minimap route rendering.
// Usage (app must be running):
//   cd scripts/ui-tests && npm ci
//   cd scripts/ui-tests && npx playwright install chromium
//   node scripts/ui-tests/minimap-route-ui-test.js
//
// Optional:
//   BASE_URL=http://localhost:8080 node scripts/ui-tests/minimap-route-ui-test.js

const { chromium } = require('playwright');
const { spawn } = require('child_process');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080/game/test?debugMinimap=1';
const LOGIN_EMAIL = process.env.LOGIN_EMAIL || '';
const LOGIN_PASSWORD = process.env.LOGIN_PASSWORD || '';
const MINI_MAP_SELECTOR = '#miniMap';
const ROUTE_CHOICES_SELECTOR = '#routeChoices .list-item';
const STEP_TIMEOUT_MS = 15000;
const START_APP = String(process.env.START_APP || '').toLowerCase() === 'true'
  || process.env.START_APP === '1';
const APP_READY_URL = process.env.APP_READY_URL || BASE_URL;
const APP_START_TIMEOUT_MS = Number(process.env.APP_START_TIMEOUT_MS || 90000);
const WATCHDOG_MS = Number(process.env.WATCHDOG_MS || (START_APP ? APP_START_TIMEOUT_MS + 30000 : 30000));
const MAVEN_CMD = process.env.MAVEN_CMD || 'mvn';
const MAVEN_ARGS = (process.env.MAVEN_ARGS || 'spring-boot:run').split(' ');

function diffPixels(a, b, threshold) {
  if (!a || !b || a.length !== b.length) return 0;
  let diff = 0;
  for (let i = 0; i < a.length; i += 4) {
    const dr = Math.abs(a[i] - b[i]);
    const dg = Math.abs(a[i + 1] - b[i + 1]);
    const db = Math.abs(a[i + 2] - b[i + 2]);
    if (dr + dg + db > threshold) diff += 1;
  }
  return diff;
}

async function getCanvasPixels(page, selector) {
  return page.evaluate((sel) => {
    const canvas = document.querySelector(sel);
    if (!canvas) return null;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    const { width, height } = canvas;
    const data = ctx.getImageData(0, 0, width, height).data;
    return Array.from(data);
  }, selector);
}

async function clickMiniMap(page) {
  await page.evaluate((sel) => {
    const mini = document.querySelector(sel);
    if (!mini) throw new Error('Mini map canvas not found.');
    const rect = mini.getBoundingClientRect();
    const x = rect.left + rect.width * 0.72;
    const y = rect.top + rect.height * 0.28;
    mini.dispatchEvent(new MouseEvent('click', { clientX: x, clientY: y, bubbles: true }));
  }, MINI_MAP_SELECTOR);
}

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
    page.on('pageerror', (err) => {
      console.error('Page error:', err.message);
    });
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
    console.log('Page loaded.');

    const loginForm = page.locator('form[action="/login"]');
    if (await loginForm.count()) {
      if (!LOGIN_EMAIL || !LOGIN_PASSWORD) {
        throw new Error('Login required. Set LOGIN_EMAIL and LOGIN_PASSWORD to run this test.');
      }
      console.log('Login required. Signing in...');
      await page.fill('input[name="username"]', LOGIN_EMAIL);
      await page.fill('input[name="password"]', LOGIN_PASSWORD);
      await page.keyboard.press('Enter');
    }

    console.log('Waiting for mini map...');
    await page.waitForSelector(MINI_MAP_SELECTOR, { timeout: STEP_TIMEOUT_MS, state: 'attached' });
    await page.waitForTimeout(500);
    console.log('Waiting for room to join...');
    try {
      await page.waitForFunction(() => {
        const el = document.getElementById('roomStatus');
        const text = el ? el.textContent || '' : '';
        return text.includes('Joined room') || text.includes('Negotiation active');
      }, { timeout: STEP_TIMEOUT_MS });
    } catch (err) {
      throw new Error('Room status did not become active. Is /room/last failing?');
    }

    const before = await getCanvasPixels(page, MINI_MAP_SELECTOR);
    await clickMiniMap(page);
    console.log('Clicked mini map, waiting for route choice...');
    await page.waitForSelector(ROUTE_CHOICES_SELECTOR, { timeout: STEP_TIMEOUT_MS, state: 'attached' });
    await page.waitForTimeout(300);
    const after = await getCanvasPixels(page, MINI_MAP_SELECTOR);

    const diff = diffPixels(before, after, 10);
    if (diff < 150) {
      throw new Error(`Mini map did not change enough after click (diff=${diff}).`);
    }

    console.log('PASS: minimap route choice visible and canvas updated.');
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
        output.split(/\s+/).forEach((pid) => {
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
