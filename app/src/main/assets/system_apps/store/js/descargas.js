// Pantalla "Descargas": todas las descargas de todas las apps, estilo moderno
// de App Store (ícono con anillo de progreso, velocidad y tiempo restante en vivo).

let __descargasPollTimer = null;

function iconLetter(name) {
  return (name || '?').trim().charAt(0).toUpperCase();
}

function ringSvg(percent, size = 60, stroke = 3) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const pct = Math.max(0, Math.min(100, percent));
  const offset = c * (1 - pct / 100);
  return `
    <svg class="dl-ring" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
      <circle class="track" cx="${size / 2}" cy="${size / 2}" r="${r}"></circle>
      <circle class="fill" cx="${size / 2}" cy="${size / 2}" r="${r}"
        stroke-dasharray="${c}" stroke-dashoffset="${offset}"></circle>
    </svg>`;
}

function stateLabel(rec) {
  switch (rec.state) {
    case 'downloading': {
      const speed = fmtSpeed(rec.speedBps);
      const eta = fmtEta(rec.etaSeconds);
      if (rec.progressPercent >= 0) {
        return `${rec.progressPercent.toFixed(0)}% · ${fmtBytes(rec.downloadedBytes)} de ${fmtBytes(rec.totalBytes)}` +
          (speed ? ` <span class="dot">·</span> ${speed}` : '') +
          (eta ? ` <span class="dot">·</span> faltan ${eta}` : '');
      }
      return `Descargando ${fmtBytes(rec.downloadedBytes)}` + (speed ? ` · ${speed}` : '');
    }
    case 'installing':
      return 'Instalando…';
    case 'completed':
      return 'Instalada' + (rec.totalBytes > 0 ? ` · ${fmtBytes(rec.totalBytes)}` : '');
    case 'failed':
      return `Error: ${rec.errorMessage || 'no se pudo instalar'}`;
    case 'canceled':
      return 'Cancelada';
    default:
      return '';
  }
}

function renderDescargas() {
  const root = document.getElementById('descargas-root');
  const listRaw = System.listDownloads();
  const list = JSON.parse(listRaw);

  updateBadge(list);

  if (!list.length) {
    root.innerHTML = `
      <div class="dl-empty">
        <div class="dl-empty-icon">⬇️</div>
        <div>Todavía no has descargado ninguna app.</div>
      </div>`;
    stopDescargasPolling();
    return;
  }

  const active = list.filter(d => d.state === 'downloading' || d.state === 'installing');
  const finished = list.filter(d => d.state !== 'downloading' && d.state !== 'installing');

  let html = '<div class="dl-section-title">Descargas</div>';

  if (active.length) {
    html += `<div class="dl-group"><div class="dl-group-label">En curso</div>${active.map(renderCard).join('')}</div>`;
  }
  if (finished.length) {
    html += `<div class="dl-group"><div class="dl-group-label">Recientes</div>${finished.map(renderCard).join('')}
      <div class="dl-clear-row"><button class="btn ghost" onclick="clearFinishedDownloads()">Borrar completadas</button></div>
    </div>`;
  }

  root.innerHTML = html;

  if (active.length) startDescargasPolling(); else stopDescargasPolling();
}

function renderCard(rec) {
  const percent = rec.progressPercent >= 0 ? rec.progressPercent : (rec.state === 'installing' ? 100 : 0);
  const showRing = rec.state === 'downloading' || rec.state === 'installing';
  let actions = '';
  if (rec.state === 'downloading') {
    actions = `<button class="dl-action-btn" title="Cancelar" onclick="onCancelDownload('${rec.id}')">✕</button>`;
  } else if (rec.state === 'failed' || rec.state === 'canceled') {
    actions = `
      <span class="dl-state-pill ${rec.state}">${rec.state === 'canceled' ? 'Cancelada' : 'Falló'}</span>
      <button class="dl-action-btn" title="Reintentar" onclick="onRetryDownload('${rec.id}','${rec.url}','${escapeJs(rec.appName)}','${rec.category}')">↻</button>
      <button class="dl-action-btn" title="Quitar" onclick="onClearDownload('${rec.id}')">🗑</button>`;
  } else if (rec.state === 'completed') {
    actions = `
      <span class="dl-state-pill completed">Instalada</span>
      <button class="dl-action-btn" title="Quitar de la lista" onclick="onClearDownload('${rec.id}')">🗑</button>`;
  }

  return `
    <div class="dl-card" id="dl-card-${rec.id}">
      <div class="dl-icon-wrap">
        <div class="dl-icon">${iconLetter(rec.appName)}</div>
        ${showRing ? ringSvg(percent) : ''}
      </div>
      <div class="dl-body">
        <div class="dl-name">${rec.appName}</div>
        <div class="dl-sub">${stateLabel(rec)}</div>
      </div>
      <div class="dl-actions">${actions}</div>
    </div>`;
}

function escapeJs(s) { return (s || '').replace(/'/g, "\\'"); }

function onCancelDownload(id) {
  System.cancelDownload(id);
  renderDescargas();
}
function onRetryDownload(id, url, appName, category) {
  System.retryDownload(id, url, appName, category);
  renderDescargas();
}
function onClearDownload(id) {
  System.clearDownload(id);
  renderDescargas();
}
function clearFinishedDownloads() {
  System.clearFinishedDownloads();
  renderDescargas();
}

function startDescargasPolling() {
  stopDescargasPolling();
  __descargasPollTimer = setInterval(() => {
    const view = document.getElementById('view-descargas');
    if (view && !view.classList.contains('hidden')) renderDescargas();
  }, 700);
}
function stopDescargasPolling() {
  if (__descargasPollTimer) { clearInterval(__descargasPollTimer); __descargasPollTimer = null; }
}

function updateBadge(list) {
  const badge = document.getElementById('tab-badge');
  const activeCount = list.filter(d => d.state === 'downloading' || d.state === 'installing').length;
  if (activeCount > 0) {
    badge.innerText = activeCount;
    badge.classList.remove('hidden');
  } else {
    badge.classList.add('hidden');
  }
}

// Permite que tienda.js "empuje" una actualización del badge apenas se inicia una descarga,
// sin esperar al primer poll de esta pantalla.
window.bumpDownloadsBadge = function () {
  const list = JSON.parse(System.listDownloads());
  updateBadge(list);
};

window.renderDescargas = renderDescargas;

// Si el usuario entra directo a esta pantalla y hay descargas activas, mantener el badge vivo
// aunque esté viendo la pestaña Tienda.
setInterval(() => {
  try {
    const list = JSON.parse(System.listDownloads());
    updateBadge(list);
  } catch (e) { /* System aún no listo */ }
}, 1500);
