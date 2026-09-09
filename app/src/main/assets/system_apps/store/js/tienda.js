// ---------- helpers de formato (compartidos con descargas.js) ----------
function fmtBytes(bytes) {
  if (bytes == null || bytes < 0) return null;
  if (bytes === 0) return '0 KB';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0, v = bytes;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 10 || i === 0 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
}
function fmtSpeed(bps) {
  if (!bps || bps <= 0) return null;
  return fmtBytes(bps) + '/s';
}
function fmtEta(seconds) {
  if (seconds == null || seconds < 0) return null;
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
}

function installFromFile() {
  System.pickAndInstallFromZip('onInstallFromFileResult');
}
function onInstallFromFileResult(success, message) {
  document.getElementById('file-status').innerText = message;
}

let catalogItems = [];

async function loadCatalog() {
  const el = document.getElementById('catalog-list');
  el.innerHTML = '<p class="hint">Cargando catálogo…</p>';
  try {
    const res = await fetch('catalog.json');
    catalogItems = await res.json();
    renderCatalog();
  } catch (e) {
    el.innerHTML = '<p class="hint">No se pudo cargar el catálogo.</p>';
  }
}

function renderCatalog() {
  const el = document.getElementById('catalog-list');
  if (!catalogItems.length) {
    el.innerHTML = '<p class="hint">El catálogo está vacío.</p>';
    return;
  }
  el.innerHTML = '';
  catalogItems.forEach((item, idx) => {
    const row = document.createElement('div');
    row.className = 'row';
    row.id = `catalog-row-${idx}`;
    row.innerHTML = `
      <div class="info">
        <div class="title">${item.name}</div>
        <div class="subtitle">${item.description || ''}</div>
        <div class="meta"><span id="catalog-size-${idx}">Consultando peso…</span></div>
        <div class="progress-track hidden" id="catalog-progress-${idx}"><div class="progress-fill" style="width:0%"></div></div>
      </div>
      <button class="btn" id="catalog-btn-${idx}" onclick="onCatalogButtonTap(${idx})">Instalar</button>
    `;
    el.appendChild(row);
    fetchSizeFor(idx, item.url);
  });
}

function fetchSizeFor(idx, url) {
  window[`__mbmSize${idx}`] = (sizeBytes, error) => {
    const label = document.getElementById(`catalog-size-${idx}`);
    if (!label) return;
    if (sizeBytes && sizeBytes > 0) {
      label.innerText = `${fmtBytes(sizeBytes)} · instalación aparte`;
      label.dataset.size = sizeBytes;
    } else {
      label.innerText = 'Peso no disponible (se verá al descargar)';
    }
  };
  System.getUrlSizeInfo(url, `__mbmSize${idx}`);
}

function onCatalogButtonTap(idx) {
  const item = catalogItems[idx];
  const btn = document.getElementById(`catalog-btn-${idx}`);
  const progressWrap = document.getElementById(`catalog-progress-${idx}`);
  const sizeLabel = document.getElementById(`catalog-size-${idx}`);
  btn.disabled = true;
  btn.innerText = 'Iniciando…';
  progressWrap.classList.remove('hidden');

  const downloadId = System.startDownload(item.url, item.name, item.category || 'other');
  trackCatalogDownload(idx, downloadId, btn, progressWrap, sizeLabel);
  if (window.bumpDownloadsBadge) window.bumpDownloadsBadge();
}

function trackCatalogDownload(idx, downloadId, btn, progressWrap, sizeLabel) {
  const fill = progressWrap.querySelector('.progress-fill');
  const poll = setInterval(() => {
    const listRaw = System.listDownloads();
    const list = JSON.parse(listRaw);
    const rec = list.find(d => d.id === downloadId);
    if (!rec) { clearInterval(poll); return; }

    if (rec.state === 'downloading') {
      if (rec.progressPercent >= 0) {
        fill.classList.remove('indeterminate');
        fill.style.width = rec.progressPercent.toFixed(0) + '%';
        const speed = fmtSpeed(rec.speedBps);
        const eta = fmtEta(rec.etaSeconds);
        sizeLabel.innerText = `${fmtBytes(rec.downloadedBytes)} de ${fmtBytes(rec.totalBytes)}` +
          (speed ? ` · ${speed}` : '') + (eta ? ` · faltan ${eta}` : '');
      } else {
        fill.classList.add('indeterminate');
        sizeLabel.innerText = `Descargando… ${fmtBytes(rec.downloadedBytes)}`;
      }
      btn.innerText = 'Descargando…';
    } else if (rec.state === 'installing') {
      fill.classList.add('indeterminate');
      fill.style.width = '100%';
      sizeLabel.innerText = 'Instalando…';
      btn.innerText = 'Instalando…';
    } else if (rec.state === 'completed') {
      clearInterval(poll);
      fill.classList.remove('indeterminate');
      fill.style.width = '100%';
      sizeLabel.innerText = 'Instalada';
      btn.innerText = 'Instalada';
      btn.classList.add('secondary');
    } else if (rec.state === 'failed' || rec.state === 'canceled') {
      clearInterval(poll);
      sizeLabel.innerText = rec.state === 'canceled' ? 'Descarga cancelada' : (rec.errorMessage || 'Error al instalar');
      btn.disabled = false;
      btn.innerText = 'Reintentar';
    }
  }, 400);
}

loadCatalog();
