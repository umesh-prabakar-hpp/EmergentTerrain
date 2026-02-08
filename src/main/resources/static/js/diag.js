(() => {
  const m3TestElements = ['md-filled-text-field', 'md-outlined-text-field', 'md-text-field'];
  const m3Url = 'https://cdn.skypack.dev/@material/web@2.4.1/all.js?min';
  const robotoCss = 'https://fonts.googleapis.com/css?family=Roboto:300,400,500';
  const symbolsCss = 'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined';

  const issues = [];

  function addIssue(msg) {
    issues.push(msg);
  }

  function showBanner() {
    if (!issues.length) return;
    const existing = document.querySelector('.diag-banner');
    if (existing) existing.remove();
    const banner = document.createElement('div');
    banner.className = 'diag-banner';
    banner.innerHTML = `
      <strong>CDN Diagnostics:</strong>
      <ul>${issues.map(i => `<li>${i}</li>`).join('')}</ul>
      <div class="diag-actions">
        <a href="/diag/cdn" target="_blank">Server CDN check</a>
      </div>
    `;
    document.body.appendChild(banner);
  }

  function checkM3Loaded() {
    const start = Date.now();
    const maxWaitMs = 6000;
    const intervalMs = 500;

    const overlay = document.createElement('div');
    overlay.className = 'diag-overlay';
    const loader = document.createElement('div');
    loader.className = 'diag-loader';
    loader.textContent = 'Loading Material Web…';
    overlay.appendChild(loader);
    document.body.appendChild(overlay);

    const timer = setInterval(() => {
      if (document.documentElement.dataset.m3 === 'ready') {
        overlay.remove();
        clearInterval(timer);
        return;
      }
      if (window.__m3Status && window.__m3Status.loaded) {
        overlay.remove();
        clearInterval(timer);
        return;
      }
      const anyDefined = m3TestElements.some(name => customElements.get(name));
      if (anyDefined) {
        overlay.remove();
        clearInterval(timer);
        return;
      }
      if (Date.now() - start >= maxWaitMs) {
        const extra = window.__m3Status && window.__m3Status.error
          ? ` (${window.__m3Status.error})`
          : '';
        addIssue(`Material Web components not loaded. Check ${m3Url}.${extra}`);
        showBanner();
        loader.textContent = 'Material Web failed to load.';
        clearInterval(timer);
      }
    }, intervalMs);
  }

  async function checkFonts() {
    if (!document.fonts || !document.fonts.check) return;
    try {
      await document.fonts.ready;
      const robotoOk = document.fonts.check('14px "Roboto"');
      const symbolsOk = document.fonts.check('14px "Material Symbols Outlined"');
      if (!robotoOk) addIssue(`Roboto font not loaded. Check ${robotoCss}.`);
      if (!symbolsOk && document.querySelector('md-icon')) {
        addIssue(`Material Symbols not loaded. Check ${symbolsCss}.`);
      }
    } catch (_) {
      addIssue('Font loading check failed.');
    }
  }

  function parseCsp(header) {
    const directives = {};
    header.split(';').forEach(part => {
      const trimmed = part.trim();
      if (!trimmed) return;
      const [name, ...vals] = trimmed.split(/\s+/);
      directives[name] = vals;
    });
    return directives;
  }

  function allowsHost(values, host) {
    if (!values) return true;
    return values.some(v => v.includes(host) || v === '*');
  }

  async function checkCsp() {
    try {
      const res = await fetch(window.location.href, { method: 'HEAD' });
      const csp = res.headers.get('content-security-policy');
      if (!csp) return;
      const directives = parseCsp(csp);
      if (!allowsHost(directives['script-src'], 'cdn.skypack.dev')) {
        addIssue('CSP may block scripts from cdn.skypack.dev.');
      }
      if (!allowsHost(directives['style-src'], 'fonts.googleapis.com')) {
        addIssue('CSP may block styles from fonts.googleapis.com.');
      }
      if (!allowsHost(directives['font-src'], 'fonts.gstatic.com')) {
        addIssue('CSP may block fonts from fonts.gstatic.com.');
      }
    } catch (_) {
      addIssue('CSP header check failed.');
    }
  }

  Promise.all([checkFonts(), checkCsp()]).then(() => {
    checkM3Loaded();
  });

  setTimeout(() => {
    if (window.__m3Status && window.__m3Status.error) {
      console.warn('M3 load error:', window.__m3Status.error);
    }
  }, 1600);
})();
