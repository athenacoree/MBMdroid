function fmtBytes(bytes) {
  if (bytes == null || bytes < 0) return '—';
  if (bytes === 0) return '0 KB';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0, v = bytes;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 10 || i === 0 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
}

let phoneAppsCache = [];
let miniAppsCache = [];

function loadSummary() {
  const summary = JSON.parse(System.getStorageSummary());
  const pct = summary.totalBytes > 0 ? (summary.usedByMiniAppsBytes / summary.totalBytes) * 100 : 0;
  document.getElementById('bar-miniapps').style.width = Math.min(100, pct).toFixed(2) + '%';
  document.getElementById('legend-miniapps').innerText = fmtBytes(summary.usedByMiniAppsBytes);
  document.getElementById('legend-free').innerText = fmtBytes(summary.freeBytes);
  document.getElementById('storage-total').innerText =
    `${fmtBytes(summary.usedBytes)} usados de ${fmtBytes(summary.totalBytes)} en el almacenamiento interno`;
}

function loadMiniApps() {
  miniAppsCache = JSON.parse(System.listAppStorage());
  const list = document.getElementById('miniapps-list');
  document.getElementById('miniapps-count').innerText = `${miniAppsCache.length} app${miniAppsCache.length === 1 ? '' : 's'}`;
  if (!miniAppsCache.length) {
    list.innerHTML = '<p class="hint">No hay mini-apps instaladas todavía.</p>';
    return;
  }
  miniAppsCache.sort((a, b) => b.sizeBytes - a.sizeBytes);
  list.innerHTML = miniAppsCache.map(app => `
    <div class="row tappable" onclick="showMiniAppDetail('${app.id}')">
      <div class="row-icon">${app.iconDataUrl ? `<img src="${app.iconDataUrl}">` : (app.name || '?').charAt(0).toUpperCase()}</div>
      <div class="info">
        <div class="title">${app.name}${app.isSystem ? '<span class="sys-badge">SISTEMA</span>' : ''}</div>
        <div class="subtitle">${app.fileCount} archivo${app.fileCount === 1 ? '' : 's'} · v${app.version}</div>
      </div>
      <div class="size">${fmtBytes(app.sizeBytes)}</div>
    </div>
  `).join('');
}

function showMiniAppDetail(appId) {
  const app = miniAppsCache.find(a => a.id === appId);
  if (!app) return;
  document.getElementById('detail-sheet-body').innerHTML = `
    <div class="detail-title">${app.name}</div>
    <div class="detail-row"><span>Peso real en disco</span><span>${fmtBytes(app.sizeBytes)}</span></div>
    <div class="detail-row"><span>Archivos</span><span>${app.fileCount}</span></div>
    <div class="detail-row"><span>Versión</span><span>${app.version}</span></div>
    <div class="detail-row"><span>Categoría</span><span>${app.category}</span></div>
    <div class="detail-row"><span>Estado</span><span>${app.status === 'INSTALLED' ? 'Activa' : 'Suspendida'}</span></div>
    <div class="detail-row"><span>Tipo</span><span>${app.isSystem ? 'App de sistema' : 'Mini-app instalada'}</span></div>
  `;
  document.getElementById('detail-sheet').classList.remove('hidden');
}

function loadPhoneApps() {
  phoneAppsCache = JSON.parse(System.listPhoneApps());
  phoneAppsCache.sort((a, b) => a.label.localeCompare(b.label));
  document.getElementById('phoneapps-count').innerText = `${phoneAppsCache.length} apps`;
  renderPhoneApps(phoneAppsCache);
}

function renderPhoneApps(apps) {
  const list = document.getElementById('phoneapps-list');
  if (!apps.length) {
    list.innerHTML = '<p class="hint">No se encontraron apps.</p>';
    return;
  }
  list.innerHTML = apps.map(app => `
    <div class="row tappable" onclick="openPhoneApp('${app.packageName}')">
      <div class="row-icon">${app.iconDataUrl ? `<img src="${app.iconDataUrl}">` : app.label.charAt(0).toUpperCase()}</div>
      <div class="info">
        <div class="title">${app.label}${app.isSystemApp ? '<span class="sys-badge">SISTEMA</span>' : ''}</div>
        <div class="subtitle">${app.packageName}</div>
      </div>
      <div class="size">${fmtBytes(app.sizeBytes)}</div>
    </div>
  `).join('');
}

function filterPhoneApps(query) {
  const q = query.trim().toLowerCase();
  if (!q) { renderPhoneApps(phoneAppsCache); return; }
  renderPhoneApps(phoneAppsCache.filter(a =>
    a.label.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
  ));
}

function openPhoneApp(packageName) {
  const ok = System.launchPhoneApp(packageName);
  if (!ok) alert('No se pudo abrir esa app.');
}

function closeSheet() { document.getElementById('detail-sheet').classList.add('hidden'); }
function closeSheetOnBackdrop(ev) { if (ev.target.id === 'detail-sheet') closeSheet(); }

loadSummary();
loadMiniApps();
loadPhoneApps();
