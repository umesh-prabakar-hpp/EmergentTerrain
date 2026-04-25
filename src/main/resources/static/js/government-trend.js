(function () {
  const token = window.GOVERNMENT_TREND_TOKEN;
  const roomId = window.GOVERNMENT_TREND_ROOM_ID;
  const initialWindow = Number(window.GOVERNMENT_TREND_WINDOW) || 360;
  const chartEl = document.getElementById('trendPageChart');
  const tooltipEl = document.getElementById('trendPageTooltip');
  const metaEl = document.getElementById('trendPageMeta');
  const latestEl = document.getElementById('trendPageLatest');
  const eventsEl = document.getElementById('trendPageEvents');
  const roomEl = document.getElementById('trendPageRoom');
  const refreshBtn = document.getElementById('trendPageRefresh');
  const windowButtonsEl = document.getElementById('trendPageWindowButtons');
  const liveEl = document.getElementById('trendPageLive');
  const seriesInputs = Array.from(document.querySelectorAll('[data-series]'));

  const WINDOWS = [
    { label: '1h', minutes: 60 },
    { label: '6h', minutes: 360 },
    { label: '24h', minutes: 1440 },
    { label: '7d', minutes: 10080 }
  ];

  const SERIES_CONFIG = [
    { key: 'federalTrust',        label: 'Trust',          color: '#0f766e', seriesKey: 'trust' },
    { key: 'federalServiceAccess', label: 'Service',        color: '#2563eb', seriesKey: 'service' },
    { key: 'avgStateTrust',        label: 'State trust',    color: '#7c3aed', seriesKey: 'stateTrust' },
    { key: 'pressureIntensity',    label: 'Pressure',       color: '#dc2626', seriesKey: 'pressure' },
    { key: 'implementationScore',  label: 'Implementation', color: '#059669', seriesKey: 'implementation' },
    { key: 'petitioningShare',     label: 'Petitioning',    color: '#7c2d12', seriesKey: 'civicLegal' },
    { key: 'sanctionedShare',      label: 'Sanctioned',     color: '#be123c', seriesKey: 'civicLegal' }
  ];

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const formatter = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
  const state = {
    windowMinutes: initialWindow,
    loading: false,
    error: '',
    data: null,
    series: {
      trust: true,
      service: true,
      stateTrust: true,
      pressure: true,
      implementation: true,
      civicLegal: true,
      actions: true,
      budget: true,
      events: true
    }
  };

  if (roomEl) {
    roomEl.textContent = `Room ${roomId}`;
  }

  buildWindowButtons();
  bindSeriesToggles();
  if (refreshBtn) {
    refreshBtn.addEventListener('click', () => fetchTrend(true));
  }
  fetchTrend(true);
  connectWebSocket();

  // --- WebSocket ---

  let ws = null;
  let wsReconnectTimer = null;

  function connectWebSocket() {
    if (!roomId) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${protocol}://${window.location.host}/ws/room`);
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'subscribe', roomId }));
      setLiveStatus(true);
    };
    ws.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data);
        if (msg.roomId !== roomId) return;
        if (msg.type === 'government_updated') {
          fetchTrend(false);
        }
      } catch (e) { /* ignore */ }
    };
    ws.onclose = () => {
      setLiveStatus(false);
      if (wsReconnectTimer) return;
      wsReconnectTimer = setTimeout(() => {
        wsReconnectTimer = null;
        connectWebSocket();
      }, 3000);
    };
    ws.onerror = () => {
      setLiveStatus(false);
    };
  }

  function setLiveStatus(connected) {
    if (!liveEl) return;
    liveEl.hidden = false;
    liveEl.textContent = connected ? '● Live' : '○ Reconnecting…';
    liveEl.className = connected ? 'trend-live-badge is-live' : 'trend-live-badge';
  }

  // --- Data fetching ---

  function buildWindowButtons() {
    if (!windowButtonsEl) return;
    windowButtonsEl.innerHTML = '';
    WINDOWS.forEach((windowOption) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `text-button government-trend-button${state.windowMinutes === windowOption.minutes ? ' is-active' : ''}`;
      button.textContent = windowOption.label;
      button.addEventListener('click', () => {
        if (state.windowMinutes === windowOption.minutes) return;
        state.windowMinutes = windowOption.minutes;
        buildWindowButtons();
        fetchTrend(true);
      });
      windowButtonsEl.appendChild(button);
    });
  }

  function bindSeriesToggles() {
    seriesInputs.forEach((input) => {
      input.checked = !!state.series[input.dataset.series];
      input.addEventListener('change', () => {
        state.series[input.dataset.series] = input.checked;
        render();
      });
    });
  }

  async function fetchTrend(force) {
    if (!roomId || !token || state.loading) return;
    state.loading = true;
    state.error = '';
    renderMeta();
    try {
      const res = await fetch(`/room/${roomId}/government-trend`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, windowMinutes: state.windowMinutes })
      });
      if (!res.ok) {
        const message = await res.text();
        throw new Error(message || 'Trend request failed.');
      }
      state.data = await res.json();
    } catch (err) {
      state.error = err && err.message ? err.message : 'Trend request failed.';
    } finally {
      state.loading = false;
      render();
    }
  }

  function render() {
    renderMeta();
    renderLatest();
    renderEvents();
    renderChart();
  }

  function renderMeta() {
    if (!metaEl) return;
    if (state.loading) {
      metaEl.textContent = `Loading ${formatWindowMinutes(state.windowMinutes)} government trend…`;
      return;
    }
    if (state.error) {
      metaEl.textContent = state.error;
      return;
    }
    const data = state.data;
    if (!data) {
      metaEl.textContent = 'No trend data loaded yet.';
      return;
    }
    metaEl.textContent = [
      `${formatWindowMinutes(data.windowMinutes || state.windowMinutes)} window`,
      `${formatter.format((data.points || []).length)} points`,
      `${formatter.format((data.events || []).length)} markers`,
      data.generatedAt ? `Updated ${formatDateTime(data.generatedAt)}` : null
    ].filter(Boolean).join(' · ');
  }

  function renderLatest() {
    if (!latestEl) return;
    const latest = latestPoint();
    if (!latest) {
      latestEl.textContent = state.error || 'No current trend sample yet.';
      return;
    }
    latestEl.textContent = `Trust ${formatScore(latest.federalTrust)} · Service ${formatScore(latest.federalServiceAccess)} · Pressure ${formatScore(latest.pressureIntensity)} · Implementation ${formatScore(latest.implementationScore)} · Petition/review/sanction ${latest.petitioningBotCount || 0}/${latest.reviewBotCount || 0}/${latest.sanctionedBotCount || 0}`;
  }

  function renderEvents() {
    if (!eventsEl) return;
    eventsEl.innerHTML = '';
    const events = selectEvents((state.data && state.data.events) || [], 8);
    if (!events.length) {
      const empty = document.createElement('div');
      empty.className = 'list-item-summary';
      empty.textContent = 'No event markers in the selected window.';
      eventsEl.appendChild(empty);
      return;
    }
    events.forEach((event) => {
      const row = document.createElement('div');
      row.className = 'government-trend-event';
      const title = document.createElement('div');
      title.className = 'list-item-summary';
      title.textContent = `${humanizeLabel(event.kind)} · ${event.title || event.referenceId}`;
      row.appendChild(title);
      const meta = document.createElement('div');
      meta.className = 'list-item-meta';
      meta.textContent = [
        event.phase ? humanizeLabel(event.phase) : null,
        event.occurredAt ? formatDateTime(event.occurredAt) : null
      ].filter(Boolean).join(' · ');
      row.appendChild(meta);
      if (event.summary || event.impact) {
        const summary = document.createElement('div');
        summary.className = 'list-item-summary';
        summary.textContent = event.summary || event.impact;
        row.appendChild(summary);
      }
      eventsEl.appendChild(row);
    });
  }

  // --- Chart ---

  function renderChart() {
    if (!chartEl) return;
    chartEl.innerHTML = '';
    if (tooltipEl) tooltipEl.hidden = true;
    if (state.loading && !state.data) {
      chartEl.textContent = 'Loading chart…';
      return;
    }
    if (state.error && !state.data) {
      chartEl.textContent = state.error;
      return;
    }
    const points = downsamplePoints((state.data && state.data.points) || [], 240);
    if (!points.length) {
      chartEl.textContent = 'No trend points available for this room yet.';
      return;
    }

    const events = selectEvents((state.data && state.data.events) || [], 36);
    const width = 1120;
    const height = 440;
    const marginLeft = 56;
    const marginRight = 24;
    const mainTop = 42;
    const mainBottom = 230;
    const budgetTop = 254;
    const budgetBottom = 320;
    const eventTop = 346;
    const eventBottom = 382;
    const innerWidth = width - marginLeft - marginRight;

    const startMs = trendTimestampMs(points[0]);
    const endMs = trendTimestampMs(points[points.length - 1]);
    const spanMs = Math.max(1, endMs - startMs);
    const xForMs = (valueMs) => marginLeft + ((valueMs - startMs) / spanMs) * innerWidth;
    const scale = buildMetricScale(points);
    const yForMetric = (value) => {
      const safe = Number.isFinite(Number(value)) ? Number(value) : scale.min;
      const normalized = (safe - scale.min) / Math.max(scale.range, 0.000001);
      return mainBottom - Math.max(0, Math.min(1, normalized)) * (mainBottom - mainTop);
    };
    const actionMax = Math.max(1, ...points.map((p) =>
      (p.activeBillCount || 0) + (p.activeExecutiveOrderCount || 0) +
      (p.activeCourtCaseCount || 0) + (p.activeAdministrativeCount || 0) +
      (p.activeConflictCount || 0)
    ));
    const budgetMax = Math.max(1, ...points.map(totalBudget));

    const svg = createSvg('svg', {
      viewBox: `0 0 ${width} ${height}`,
      role: 'img',
      'aria-label': 'Government trend full chart'
    });

    // Background
    svg.appendChild(createSvg('rect', { x: 0, y: 0, width, height, rx: 18, fill: 'rgba(255,255,255,0.92)' }));

    // Grid lines + y-axis labels
    scale.gridValues.forEach((value) => {
      const y = yForMetric(value);
      svg.appendChild(createSvg('line', {
        x1: marginLeft, y1: y, x2: width - marginRight, y2: y,
        stroke: 'rgba(15, 23, 42, 0.09)', 'stroke-width': 1
      }));
      svg.appendChild(createSvg('text', {
        x: 10, y: y + 4,
        fill: 'rgba(15, 23, 42, 0.5)', 'font-size': 12
      }, formatScore(value)));
    });

    // Section labels
    ['Metric lines', 'Budget mix', 'Event markers'].forEach((label, index) => {
      const y = index === 0 ? mainTop - 30 : index === 1 ? budgetTop - 8 : eventTop - 8;
      svg.appendChild(createSvg('text', {
        x: marginLeft, y,
        fill: 'rgba(15, 23, 42, 0.42)', 'font-size': 11
      }, label));
    });

    // Legend (inline with "Metric lines" label, top of chart)
    buildInlineLegend(svg, marginLeft + 82, mainTop - 30);

    // Party shift markers
    extractPartyShiftMarkers(points).forEach((marker) => {
      svg.appendChild(createSvg('line', {
        x1: xForMs(marker.timestampMs), y1: mainTop,
        x2: xForMs(marker.timestampMs), y2: mainBottom,
        stroke: 'rgba(20, 83, 45, 0.28)', 'stroke-width': 1.2, 'stroke-dasharray': '4 4'
      }, marker.label));
    });

    // Action + budget bars
    points.forEach((point, index) => {
      const x = xForMs(trendTimestampMs(point));
      const nextX = index < points.length - 1
        ? xForMs(trendTimestampMs(points[index + 1]))
        : x + Math.max(3, innerWidth / points.length);

      if (state.series.actions) {
        const actionCount = (point.activeBillCount || 0) + (point.activeExecutiveOrderCount || 0) +
          (point.activeCourtCaseCount || 0) + (point.activeAdministrativeCount || 0) +
          (point.activeConflictCount || 0);
        const actionHeight = (actionCount / actionMax) * 42;
        svg.appendChild(createSvg('rect', {
          x, y: mainBottom - actionHeight,
          width: Math.max(3, nextX - x - 1), height: Math.max(1, actionHeight),
          fill: 'rgba(245, 158, 11, 0.18)'
        }));
      }

      if (state.series.budget) {
        const barWidth = Math.max(3, nextX - x);
        let stackY = budgetBottom;
        const total = Math.max(1, totalBudget(point));
        [
          ['education', '#94a3b8'],
          ['health', '#60a5fa'],
          ['infrastructure', '#f59e0b'],
          ['welfare', '#34d399'],
          ['justice', '#f97316'],
          ['payroll', '#a78bfa']
        ].forEach(([key, color]) => {
          const raw = Math.max(0, Number(point[key]) || 0);
          const segmentHeight = (raw / total) * (budgetBottom - budgetTop);
          if (!segmentHeight) return;
          stackY -= segmentHeight;
          svg.appendChild(createSvg('rect', { x, y: stackY, width: barWidth, height: segmentHeight, fill: color, opacity: 0.78 }));
        });
        const totalHeight = (total / budgetMax) * (budgetBottom - budgetTop);
        svg.appendChild(createSvg('line', {
          x1: x, y1: budgetBottom - totalHeight, x2: x + barWidth, y2: budgetBottom - totalHeight,
          stroke: 'rgba(15, 23, 42, 0.18)', 'stroke-width': 1
        }));
      }
    });

    // Series lines
    if (state.series.trust)          svg.appendChild(buildLine(points, 'federalTrust',        xForMs, yForMetric, '#0f766e', 3));
    if (state.series.service)        svg.appendChild(buildLine(points, 'federalServiceAccess', xForMs, yForMetric, '#2563eb', 3));
    if (state.series.stateTrust)     svg.appendChild(buildLine(points, 'avgStateTrust',        xForMs, yForMetric, '#7c3aed', 2.5));
    if (state.series.pressure)       svg.appendChild(buildLine(points, 'pressureIntensity',    xForMs, yForMetric, '#dc2626', 2.2));
    if (state.series.implementation) svg.appendChild(buildLine(points, 'implementationScore',  xForMs, yForMetric, '#059669', 2.2));
    if (state.series.civicLegal) {
      svg.appendChild(buildLine(points, 'petitioningShare', xForMs, yForMetric, '#7c2d12', 2.0));
      svg.appendChild(buildLine(points, 'sanctionedShare',  xForMs, yForMetric, '#be123c', 2.0));
    }

    // Event markers
    if (state.series.events) {
      events.forEach((event) => {
        const eventX = xForMs(Math.max(startMs, Math.min(endMs, eventTimestampMs(event, startMs))));
        const laneY = eventLaneY(event.kind, eventTop, eventBottom);
        svg.appendChild(buildEventMarker(eventX, laneY, event));
      });
    }

    // X-axis time labels
    [startMs, startMs + spanMs / 2, endMs].forEach((valueMs) => {
      svg.appendChild(createSvg('text', {
        x: xForMs(valueMs), y: height - 10,
        'text-anchor': valueMs === startMs ? 'start' : valueMs === endMs ? 'end' : 'middle',
        fill: 'rgba(15, 23, 42, 0.55)', 'font-size': 12
      }, formatAxisTime(valueMs)));
    });

    // Crosshair group (hidden until hover)
    const crosshairGroup = createSvg('g', { 'pointer-events': 'none', opacity: '0' });
    const crosshairLine = createSvg('line', {
      x1: 0, y1: mainTop, x2: 0, y2: mainBottom,
      stroke: 'rgba(15, 23, 42, 0.22)', 'stroke-width': 1, 'stroke-dasharray': '4 3'
    });
    crosshairGroup.appendChild(crosshairLine);

    const highlightDots = {};
    SERIES_CONFIG.forEach(({ key, color }) => {
      const dot = createSvg('circle', {
        cx: 0, cy: 0, r: 4.5,
        fill: color, stroke: 'white', 'stroke-width': 2
      });
      highlightDots[key] = dot;
      crosshairGroup.appendChild(dot);
    });
    svg.appendChild(crosshairGroup);

    // Transparent overlay for hover tracking
    const overlay = createSvg('rect', {
      x: marginLeft, y: mainTop,
      width: innerWidth, height: mainBottom - mainTop,
      fill: 'transparent', cursor: 'crosshair'
    });

    overlay.addEventListener('mousemove', (evt) => {
      const svgRect = svg.getBoundingClientRect();
      const scaleX = width / svgRect.width;
      const mouseX = (evt.clientX - svgRect.left) * scaleX;
      const targetMs = startMs + ((mouseX - marginLeft) / innerWidth) * spanMs;

      let nearestPoint = null;
      let nearestDist = Infinity;
      let nearestX = marginLeft;
      points.forEach((point) => {
        const pointMs = trendTimestampMs(point);
        const dist = Math.abs(pointMs - targetMs);
        if (dist < nearestDist) {
          nearestDist = dist;
          nearestPoint = point;
          nearestX = xForMs(pointMs);
        }
      });

      if (!nearestPoint) return;

      // Update crosshair position
      crosshairLine.setAttribute('x1', nearestX);
      crosshairLine.setAttribute('x2', nearestX);

      // Update highlight dots
      SERIES_CONFIG.forEach(({ key, seriesKey }) => {
        const dot = highlightDots[key];
        const seriesEnabled = state.series[seriesKey];
        if (seriesEnabled) {
          dot.setAttribute('cx', nearestX);
          dot.setAttribute('cy', yForMetric(nearestPoint[key]));
          dot.setAttribute('display', '');
        } else {
          dot.setAttribute('display', 'none');
        }
      });

      crosshairGroup.setAttribute('opacity', '1');
      showTooltip(evt, nearestPoint);
    });

    overlay.addEventListener('mouseleave', () => {
      crosshairGroup.setAttribute('opacity', '0');
      hideTooltip();
    });

    svg.appendChild(overlay);
    chartEl.appendChild(svg);
  }

  function buildInlineLegend(svg, startX, y) {
    const activeItems = SERIES_CONFIG.filter(({ seriesKey }) => state.series[seriesKey]);
    // Deduplicate civicLegal — petitioning and sanctioned share the same toggle
    const seen = new Set();
    const items = activeItems.filter(({ seriesKey, key }) => {
      const dedupeKey = seriesKey === 'civicLegal' ? key : seriesKey;
      if (seen.has(dedupeKey)) return false;
      seen.add(dedupeKey);
      return true;
    });

    let x = startX;
    items.forEach(({ label, color }) => {
      const swatch = createSvg('rect', {
        x, y: y - 8, width: 10, height: 10, rx: 2, fill: color, opacity: 0.9
      });
      svg.appendChild(swatch);
      const text = createSvg('text', {
        x: x + 14, y,
        fill: 'rgba(15, 23, 42, 0.65)', 'font-size': 11
      }, label);
      svg.appendChild(text);
      // Approximate text width: ~6.5px per char
      x += 14 + label.length * 6.5 + 14;
    });
  }

  function showTooltip(evt, point) {
    if (!tooltipEl) return;
    tooltipEl.hidden = false;
    tooltipEl.innerHTML = '';

    // Header
    const header = document.createElement('div');
    header.style.cssText = 'font-weight:600;margin-bottom:5px;opacity:0.7;font-size:11px;';
    header.textContent = [
      `Tick ${point.tick || 0}`,
      point.sampledAt || point.bucketStartAt ? formatDateTime(point.sampledAt || point.bucketStartAt) : null
    ].filter(Boolean).join(' · ');
    tooltipEl.appendChild(header);

    // Series rows with color swatches
    SERIES_CONFIG.forEach(({ key, label, color, seriesKey }) => {
      if (!state.series[seriesKey]) return;
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:6px;line-height:1.6;';
      const swatch = document.createElement('span');
      swatch.style.cssText = `display:inline-block;width:9px;height:9px;border-radius:2px;background:${color};flex-shrink:0;`;
      const text = document.createElement('span');
      text.textContent = `${label}  ${formatScore(point[key])}`;
      row.appendChild(swatch);
      row.appendChild(text);
      tooltipEl.appendChild(row);
    });

    // Separator
    const sep = document.createElement('div');
    sep.style.cssText = 'margin:5px 0 3px;border-top:1px solid rgba(255,255,255,0.15);';
    tooltipEl.appendChild(sep);

    // Counts
    [
      `Bills/orders/cases ${point.activeBillCount || 0}/${point.activeExecutiveOrderCount || 0}/${point.activeCourtCaseCount || 0}`,
      `Admin/conflicts ${point.activeAdministrativeCount || 0}/${point.activeConflictCount || 0}`,
      `Petition/review/sanction ${Math.round(point.petitioningBotCount || 0)}/${Math.round(point.reviewBotCount || 0)}/${Math.round(point.sanctionedBotCount || 0)}`,
      `Budget ${formatter.format(totalBudget(point))}`
    ].forEach((line) => {
      const row = document.createElement('div');
      row.style.cssText = 'opacity:0.75;line-height:1.55;';
      row.textContent = line;
      tooltipEl.appendChild(row);
    });

    positionTooltip(evt);
  }

  function positionTooltip(evt) {
    if (!tooltipEl || !chartEl) return;
    const rect = chartEl.getBoundingClientRect();
    const offsetX = evt.clientX - rect.left;
    const offsetY = evt.clientY - rect.top;
    const tipW = tooltipEl.offsetWidth || 220;
    const tipH = tooltipEl.offsetHeight || 160;
    tooltipEl.style.left = `${Math.min(rect.width - tipW - 8, Math.max(8, offsetX + 16))}px`;
    tooltipEl.style.top = `${Math.min(rect.height - tipH - 8, Math.max(8, offsetY - tipH / 2))}px`;
  }

  function hideTooltip() {
    if (tooltipEl) tooltipEl.hidden = true;
  }

  // --- Helpers ---

  function latestPoint() {
    const points = (state.data && state.data.points) || [];
    return points.length ? points[points.length - 1] : null;
  }

  function createSvg(tag, attrs, titleText) {
    const element = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        element.setAttribute(key, String(value));
      }
    });
    if (titleText) {
      const title = document.createElementNS(SVG_NS, 'title');
      title.textContent = titleText;
      element.appendChild(title);
    }
    return element;
  }

  function buildLine(points, key, xForMs, yForMetric, color, strokeWidth) {
    const commands = [];
    points.forEach((point, index) => {
      commands.push(`${index === 0 ? 'M' : 'L'} ${xForMs(trendTimestampMs(point)).toFixed(2)} ${yForMetric(point[key]).toFixed(2)}`);
    });
    return createSvg('path', {
      d: commands.join(' '),
      fill: 'none',
      stroke: color,
      'stroke-width': strokeWidth,
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round'
    });
  }

  function buildEventMarker(x, y, event) {
    const color = eventColor(event.kind);
    const label = `${humanizeLabel(event.kind)} · ${event.title || event.referenceId}`;
    if (event.kind === 'court') {
      return createSvg('rect', { x: x - 6, y: y - 6, width: 12, height: 12, rx: 2, fill: color }, label);
    }
    if (event.kind === 'order') {
      return createSvg('path', { d: `M ${x} ${y - 7} L ${x + 7} ${y} L ${x} ${y + 7} L ${x - 7} ${y} Z`, fill: color }, label);
    }
    if (event.kind === 'election') {
      return createSvg('path', { d: `M ${x} ${y - 8} L ${x + 7} ${y + 6} L ${x - 7} ${y + 6} Z`, fill: color }, label);
    }
    return createSvg('circle', { cx: x, cy: y, r: 5.5, fill: color }, label);
  }

  function buildMetricScale(points) {
    const values = [];
    points.forEach((point) => {
      SERIES_CONFIG.forEach(({ key }) => {
        const value = Number(point[key]);
        if (Number.isFinite(value)) values.push(value);
      });
    });
    if (!values.length) return { min: 0, max: 1, range: 1, gridValues: [0, 0.5, 1] };
    let min = Math.min(...values);
    let max = Math.max(...values);
    const spread = max - min;
    const padding = Math.max(0.015, spread * 0.22);
    min = Math.max(0, min - padding);
    max = Math.min(1, max + padding);
    if (max - min < 0.05) {
      const midpoint = (max + min) / 2;
      min = Math.max(0, midpoint - 0.025);
      max = Math.min(1, midpoint + 0.025);
    }
    const range = Math.max(0.000001, max - min);
    return { min, max, range, gridValues: [min, min + range / 2, max] };
  }

  function extractPartyShiftMarkers(points) {
    const markers = [];
    let previousKey = '';
    points.forEach((point, index) => {
      const controlKey = [
        point.federalExecutiveParty || '',
        point.federalLowerParty || '',
        point.federalUpperParty || '',
        point.federalChiefJusticeParty || ''
      ].join('|');
      if (index > 0 && controlKey !== previousKey) {
        markers.push({ timestampMs: trendTimestampMs(point), label: `Control shift to ${point.federalExecutiveParty || 'None'}` });
      }
      previousKey = controlKey;
    });
    return markers;
  }

  function downsamplePoints(points, maxPoints) {
    if (!Array.isArray(points) || points.length <= maxPoints) return points || [];
    const bucketSize = Math.ceil(points.length / maxPoints);
    const compact = [];
    for (let index = 0; index < points.length; index += bucketSize) {
      const group = points.slice(index, index + bucketSize);
      const latest = group[group.length - 1];
      compact.push({
        ...latest,
        federalTrust: average(group, 'federalTrust'),
        federalServiceAccess: average(group, 'federalServiceAccess'),
        avgStateTrust: average(group, 'avgStateTrust'),
        avgStateServiceAccess: average(group, 'avgStateServiceAccess'),
        pressureIntensity: average(group, 'pressureIntensity'),
        implementationScore: average(group, 'implementationScore'),
        petitioningShare: average(group, 'petitioningShare'),
        sanctionedShare: average(group, 'sanctionedShare'),
        revenue: average(group, 'revenue'),
        education: average(group, 'education'),
        health: average(group, 'health'),
        infrastructure: average(group, 'infrastructure'),
        welfare: average(group, 'welfare'),
        justice: average(group, 'justice'),
        payroll: average(group, 'payroll'),
        petitioningBotCount: average(group, 'petitioningBotCount'),
        reviewBotCount: average(group, 'reviewBotCount'),
        sanctionedBotCount: average(group, 'sanctionedBotCount'),
        activeAdministrativeCount: average(group, 'activeAdministrativeCount'),
        activeConflictCount: average(group, 'activeConflictCount')
      });
    }
    return compact;
  }

  function average(group, key) {
    return group.reduce((sum, item) => sum + (Number(item[key]) || 0), 0) / Math.max(1, group.length);
  }

  function selectEvents(events, maxEvents) {
    if (!Array.isArray(events) || !events.length) return [];
    if (events.length <= maxEvents) return events.slice();
    const step = Math.max(1, Math.floor(events.length / maxEvents));
    const selected = [];
    for (let index = 0; index < events.length && selected.length < maxEvents; index += step) {
      selected.push(events[index]);
    }
    const latest = events[events.length - 1];
    if (selected[selected.length - 1] !== latest) selected[selected.length - 1] = latest;
    return selected;
  }

  function totalBudget(point) {
    return (Number(point.education) || 0) + (Number(point.health) || 0) +
      (Number(point.infrastructure) || 0) + (Number(point.welfare) || 0) +
      (Number(point.justice) || 0) + (Number(point.payroll) || 0);
  }

  function trendTimestampMs(point) {
    const raw = point && (point.sampledAt || point.bucketStartAt);
    const parsed = raw ? new Date(raw).getTime() : Date.now();
    return Number.isFinite(parsed) ? parsed : Date.now();
  }

  function eventTimestampMs(event, fallback) {
    const parsed = event && event.occurredAt ? new Date(event.occurredAt).getTime() : fallback;
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  function eventLaneY(kind, top, bottom) {
    const lanes = {
      bill: top, order: top + 10, court: top + 20, election: bottom - 4,
      administrative: top + 28, pressure: top + 36, conflict: bottom - 12, implementation: bottom - 20
    };
    return lanes[kind] || bottom - 8;
  }

  function eventColor(kind) {
    return {
      bill: '#0f766e', order: '#ea580c', court: '#334155', election: '#7c3aed',
      administrative: '#0f766e', pressure: '#dc2626', conflict: '#b45309', implementation: '#059669'
    }[kind] || '#475569';
  }

  function formatWindowMinutes(windowMinutes) {
    const total = Number(windowMinutes) || 0;
    if (total % 1440 === 0 && total >= 1440) return `${Math.round(total / 1440)}d`;
    if (total % 60 === 0 && total >= 60) return `${Math.round(total / 60)}h`;
    return `${total}m`;
  }

  function formatDateTime(value) {
    const date = value instanceof Date ? value : new Date(value);
    if (!Number.isFinite(date.getTime())) return '';
    return date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  function formatAxisTime(timestampMs) {
    const date = new Date(timestampMs);
    if (!Number.isFinite(date.getTime())) return '';
    return date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  function formatScore(value) {
    if (value === undefined || value === null) return '0.00';
    return Number(value).toFixed(2);
  }

  function humanizeLabel(value) {
    if (!value) return '';
    return String(value).replace(/[_:-]/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
  }
})();
