(() => {
  const m3TestElement = 'md-filled-text-field';
  const m3Url = 'https://cdn.jsdelivr.net/npm/@material/web@2.4.1/all.js';
  const robotoCss = 'https://fonts.googleapis.com/css?family=Roboto:300,400,500';
  const symbolsCss = 'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined';

  const issues = [];

  function addIssue(msg) {
    issues.push(msg);
  }

  function showBanner() {
    if (!issues.length) return;
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
    setTimeout(() => {
      if (!customElements.get(m3TestElement)) {
        addIssue(`Material Web components not loaded. Check ${m3Url}.`);
        showBanner();
      }
    }, 1500);
  }

  async function checkFonts() {
    if (!document.fonts || !document.fonts.check) return;
    try {
      await document.fonts.ready;
      const robotoOk = document.fonts.check('14px "Roboto"');
      const symbolsOk = document.fonts.check('14px "Material Symbols Outlined"');
      if (!robotoOk) addIssue(`Roboto font not loaded. Check ${robotoCss}.`);
      if (!symbolsOk) addIssue(`Material Symbols not loaded. Check ${symbolsCss}.`);
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
      if (!allowsHost(directives['script-src'], 'unpkg.com')) {
        addIssue('CSP may block scripts from unpkg.com.');
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
})();
