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
  const seriesInputs = Array.from(document.querySelectorAll('[data-series]'));

  const WINDOWS = [
    { label: '1h', minutes: 60 },
    { label: '6h', minutes: 360 },
    { label: '24h', minutes: 1440 },
    { label: '7d', minutes: 10080 }
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

  function buildWindowButtons() {
    if (!windowButtonsEl) return;
    windowButtonsEl.innerHTML = '';
    WINDOWS.forEach((windowOption) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `text-button government-trend-button${state.windowMinutes === windowOption.minutes ? ' is-active' : ''}`;
      button.textContent = windowOption.label;
      button.addEventListener('click', () => {
        if (state.windowMinutes === windowOption.minutes) {
          return;
        }
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
    latestEl.textContent = `Trust ${formatScore(latest.federalTrust)} · Service ${formatScore(latest.federalServiceAccess)} · State trust ${formatScore(latest.avgStateTrust)} · Bills/orders/cases ${latest.activeBillCount || 0}/${latest.activeExecutiveOrderCount || 0}/${latest.activeCourtCaseCount || 0}`;
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

  function renderChart() {
    if (!chartEl) return;
    chartEl.innerHTML = '';
    if (tooltipEl) {
      tooltipEl.hidden = true;
    }
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
    const height = 420;
    const marginLeft = 56;
    const marginRight = 24;
    const mainTop = 24;
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
    const actionMax = Math.max(1, ...points.map((point) =>
      (point.activeBillCount || 0) + (point.activeExecutiveOrderCount || 0) + (point.activeCourtCaseCount || 0)
    ));
    const budgetMax = Math.max(1, ...points.map(totalBudget));

    const svg = createSvg('svg', {
      viewBox: `0 0 ${width} ${height}`,
      role: 'img',
      'aria-label': 'Government trend full chart'
    });
    svg.appendChild(createSvg('rect', {
      x: 0,
      y: 0,
      width,
      height,
      rx: 18,
      fill: 'rgba(255,255,255,0.92)'
    }));

    scale.gridValues.forEach((value) => {
      const y = yForMetric(value);
      svg.appendChild(createSvg('line', {
        x1: marginLeft,
        y1: y,
        x2: width - marginRight,
        y2: y,
        stroke: 'rgba(15, 23, 42, 0.09)',
        'stroke-width': 1
      }));
      svg.appendChild(createSvg('text', {
        x: 10,
        y: y + 4,
        fill: 'rgba(15, 23, 42, 0.5)',
        'font-size': 12
      }, formatScore(value)));
    });

    ['Metric lines', 'Budget mix', 'Event markers'].forEach((label, index) => {
      const y = index === 0 ? mainTop - 8 : index === 1 ? budgetTop - 8 : eventTop - 8;
      svg.appendChild(createSvg('text', {
        x: marginLeft,
        y,
        fill: 'rgba(15, 23, 42, 0.55)',
        'font-size': 12
      }, label));
    });

    extractPartyShiftMarkers(points).forEach((marker) => {
      svg.appendChild(createSvg('line', {
        x1: xForMs(marker.timestampMs),
        y1: mainTop,
        x2: xForMs(marker.timestampMs),
        y2: mainBottom,
        stroke: 'rgba(20, 83, 45, 0.28)',
        'stroke-width': 1.2,
        'stroke-dasharray': '4 4'
      }, marker.label));
    });

    points.forEach((point, index) => {
      const x = xForMs(trendTimestampMs(point));
      const nextX = index < points.length - 1 ? xForMs(trendTimestampMs(points[index + 1])) : x + Math.max(3, innerWidth / points.length);
      if (state.series.actions) {
        const actionCount = (point.activeBillCount || 0) + (point.activeExecutiveOrderCount || 0) + (point.activeCourtCaseCount || 0);
        const actionHeight = (actionCount / actionMax) * 42;
        svg.appendChild(createSvg('rect', {
          x,
          y: mainBottom - actionHeight,
          width: Math.max(3, nextX - x - 1),
          height: Math.max(1, actionHeight),
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
          svg.appendChild(createSvg('rect', {
            x,
            y: stackY,
            width: barWidth,
            height: segmentHeight,
            fill: color,
            opacity: 0.78
          }));
        });
        const totalHeight = (total / budgetMax) * (budgetBottom - budgetTop);
        svg.appendChild(createSvg('line', {
          x1: x,
          y1: budgetBottom - totalHeight,
          x2: x + barWidth,
          y2: budgetBottom - totalHeight,
          stroke: 'rgba(15, 23, 42, 0.18)',
          'stroke-width': 1
        }));
      }
    });

    if (state.series.trust) {
      svg.appendChild(buildLine(points, 'federalTrust', xForMs, yForMetric, '#0f766e', 3));
    }
    if (state.series.service) {
      svg.appendChild(buildLine(points, 'federalServiceAccess', xForMs, yForMetric, '#2563eb', 3));
    }
    if (state.series.stateTrust) {
      svg.appendChild(buildLine(points, 'avgStateTrust', xForMs, yForMetric, '#7c3aed', 2.5));
    }

    if (state.series.events) {
      events.forEach((event) => {
        const eventX = xForMs(Math.max(startMs, Math.min(endMs, eventTimestampMs(event, startMs))));
        const laneY = eventLaneY(event.kind, eventTop, eventBottom);
        svg.appendChild(buildEventMarker(eventX, laneY, event));
      });
    }

    [startMs, startMs + spanMs / 2, endMs].forEach((valueMs) => {
      svg.appendChild(createSvg('text', {
        x: xForMs(valueMs),
        y: height - 10,
        'text-anchor': valueMs === startMs ? 'start' : valueMs === endMs ? 'end' : 'middle',
        fill: 'rgba(15, 23, 42, 0.55)',
        'font-size': 12
      }, formatAxisTime(valueMs)));
    });

    points.forEach((point) => {
      const x = xForMs(trendTimestampMs(point));
      const hit = createSvg('circle', {
        cx: x,
        cy: yForMetric(point.federalTrust),
        r: 8,
        fill: 'transparent',
        'data-role': 'hover-target'
      });
      hit.addEventListener('mouseenter', (evt) => showTooltip(evt, point));
      hit.addEventListener('mousemove', (evt) => showTooltip(evt, point));
      hit.addEventListener('mouseleave', hideTooltip);
      svg.appendChild(hit);
    });

    chartEl.appendChild(svg);
  }

  function showTooltip(evt, point) {
    if (!tooltipEl) return;
    tooltipEl.hidden = false;
    tooltipEl.innerHTML = '';
    [
      `Tick ${point.tick || 0}`,
      point.sampledAt || point.bucketStartAt ? formatDateTime(point.sampledAt || point.bucketStartAt) : '',
      `Trust ${formatScore(point.federalTrust)}`,
      `Service ${formatScore(point.federalServiceAccess)}`,
      `State trust ${formatScore(point.avgStateTrust)}`,
      `Bills/orders/cases ${point.activeBillCount || 0}/${point.activeExecutiveOrderCount || 0}/${point.activeCourtCaseCount || 0}`,
      `Budget ${formatter.format(totalBudget(point))}`
    ].filter(Boolean).forEach((line) => {
      const row = document.createElement('div');
      row.textContent = line;
      tooltipEl.appendChild(row);
    });
    const rect = chartEl.getBoundingClientRect();
    const offsetX = evt.clientX - rect.left;
    const offsetY = evt.clientY - rect.top;
    tooltipEl.style.left = `${Math.min(rect.width - 220, Math.max(12, offsetX + 14))}px`;
    tooltipEl.style.top = `${Math.min(rect.height - 120, Math.max(12, offsetY + 14))}px`;
  }

  function hideTooltip() {
    if (tooltipEl) {
      tooltipEl.hidden = true;
    }
  }

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

  function buildLine(points, key, xForMs, yForMetric, color, width) {
    const commands = [];
    points.forEach((point, index) => {
      commands.push(`${index === 0 ? 'M' : 'L'} ${xForMs(trendTimestampMs(point)).toFixed(2)} ${yForMetric(point[key]).toFixed(2)}`);
    });
    return createSvg('path', {
      d: commands.join(' '),
      fill: 'none',
      stroke: color,
      'stroke-width': width,
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round'
    });
  }

  function buildEventMarker(x, y, event) {
    const color = eventColor(event.kind);
    const label = `${humanizeLabel(event.kind)} · ${event.title || event.referenceId}`;
    if (event.kind === 'court') {
      return createSvg('rect', {
        x: x - 6,
        y: y - 6,
        width: 12,
        height: 12,
        rx: 2,
        fill: color
      }, label);
    }
    if (event.kind === 'order') {
      return createSvg('path', {
        d: `M ${x} ${y - 7} L ${x + 7} ${y} L ${x} ${y + 7} L ${x - 7} ${y} Z`,
        fill: color
      }, label);
    }
    if (event.kind === 'election') {
      return createSvg('path', {
        d: `M ${x} ${y - 8} L ${x + 7} ${y + 6} L ${x - 7} ${y + 6} Z`,
        fill: color
      }, label);
    }
    return createSvg('circle', {
      cx: x,
      cy: y,
      r: 5.5,
      fill: color
    }, label);
  }

  function buildMetricScale(points) {
    const values = [];
    points.forEach((point) => {
      ['federalTrust', 'federalServiceAccess', 'avgStateTrust'].forEach((key) => {
        const value = Number(point[key]);
        if (Number.isFinite(value)) values.push(value);
      });
    });
    if (!values.length) {
      return { min: 0, max: 1, range: 1, gridValues: [0, 0.5, 1] };
    }
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
        markers.push({
          timestampMs: trendTimestampMs(point),
          label: `Control shift to ${point.federalExecutiveParty || 'None'}`
        });
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
        revenue: average(group, 'revenue'),
        education: average(group, 'education'),
        health: average(group, 'health'),
        infrastructure: average(group, 'infrastructure'),
        welfare: average(group, 'welfare'),
        justice: average(group, 'justice'),
        payroll: average(group, 'payroll')
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
    if (selected[selected.length - 1] !== latest) {
      selected[selected.length - 1] = latest;
    }
    return selected;
  }

  function totalBudget(point) {
    return (Number(point.education) || 0)
      + (Number(point.health) || 0)
      + (Number(point.infrastructure) || 0)
      + (Number(point.welfare) || 0)
      + (Number(point.justice) || 0)
      + (Number(point.payroll) || 0);
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
      bill: top,
      order: top + 10,
      court: top + 20,
      election: bottom - 4
    };
    return lanes[kind] || bottom - 8;
  }

  function eventColor(kind) {
    return {
      bill: '#0f766e',
      order: '#ea580c',
      court: '#334155',
      election: '#7c3aed'
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
    return date.toLocaleString([], {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit'
    });
  }

  function formatAxisTime(timestampMs) {
    const date = new Date(timestampMs);
    if (!Number.isFinite(date.getTime())) return '';
    return date.toLocaleString([], {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit'
    });
  }

  function formatScore(value) {
    if (value === undefined || value === null) return '0.00';
    return Number(value).toFixed(2);
  }

  function humanizeLabel(value) {
    if (!value) return '';
    return String(value)
      .replace(/[_:-]/g, ' ')
      .replace(/\b\w/g, (match) => match.toUpperCase());
  }
})();
