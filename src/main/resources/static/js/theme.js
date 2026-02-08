(() => {
  const storageKey = 'et-theme';
  const root = document.documentElement;
  const toggle = document.getElementById('themeToggle') || document.getElementById('themeToggleFallback');
  const label = document.getElementById('themeLabel');
  const icon = document.getElementById('themeIcon');

  function applyTheme(theme) {
    root.dataset.theme = theme;
    if (toggle) {
      const isDark = theme === 'dark';
      toggle.setAttribute('aria-pressed', isDark ? 'true' : 'false');
      if (label) {
        label.textContent = isDark ? 'Light mode' : 'Dark mode';
      }
      if (icon) {
        icon.textContent = isDark ? 'light_mode' : 'dark_mode';
      }
      if (!label) {
        toggle.textContent = isDark ? 'Light mode' : 'Dark mode';
      }
    }
  }

  const saved = localStorage.getItem(storageKey);
  if (saved === 'dark' || saved === 'light') {
    applyTheme(saved);
  } else {
    applyTheme('light');
  }

  if (toggle) {
    toggle.addEventListener('click', () => {
      const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      localStorage.setItem(storageKey, next);
      applyTheme(next);
    });
  }
})();
