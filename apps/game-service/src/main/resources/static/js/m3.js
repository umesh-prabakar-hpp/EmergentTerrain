try {
  const moduleUrl = "https://cdn.skypack.dev/@material/web@2.4.1/all.js?min";
  window.__m3Status = { loaded: false, url: moduleUrl };
  await import(moduleUrl);
  await customElements.whenDefined('md-filled-text-field');
  document.documentElement.dataset.m3 = "ready";
  window.__m3Status = { loaded: true, url: moduleUrl };
} catch (err) {
  window.__m3Status = {
    loaded: false,
    url: "https://cdn.skypack.dev/@material/web@2.4.1?min",
    error: String(err && err.message ? err.message : err)
  };
  console.warn('Material Web failed to load', err);
}
