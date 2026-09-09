function renderDefaults() {
  const categories = JSON.parse(System.listCategories());
  const defaults = JSON.parse(System.getDefaultApps());
  const apps = JSON.parse(System.listApps());

  const el = document.getElementById('defaults-list');
  el.innerHTML = '';
  categories.forEach(cat => {
    const defaultAppId = defaults[cat.id];
    const defaultApp = apps.find(a => a.id === defaultAppId);
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `
      <div>
        <div class="title">${cat.label}</div>
        <div class="subtitle">${defaultApp ? defaultApp.name : 'Sin predeterminada — se preguntará cada vez'}</div>
      </div>
      <button class="pill" onclick="openPicker('${cat.id}', '${cat.label}')">Cambiar</button>
    `;
    el.appendChild(row);
  });
}

function renderApps() {
  const apps = JSON.parse(System.listApps());
  const el = document.getElementById('apps-list');
  el.innerHTML = '';
  apps.forEach(app => {
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `
      <div>
        <div class="title">${app.name} ${app.isSystem ? '<span class="pill system">sistema</span>' : ''}</div>
        <div class="subtitle">v${app.version} · ${app.categoryLabel} · ${app.status === 'SUSPENDED' ? 'suspendida' : 'activa'}</div>
      </div>
      <div class="btn-row">
        <button class="btn ghost" onclick="System.openCodeViewer('${app.id}')">Código</button>
        <button class="btn ghost" onclick="System.pickAndUpdateApp('${app.id}')">Actualizar</button>
        ${app.isSystem ? '' : `<button class="btn danger" onclick="uninstall('${app.id}')">Borrar</button>`}
      </div>
    `;
    el.appendChild(row);
  });
}

function uninstall(id) {
  if (System.uninstallApp(id)) {
    renderApps();
    renderDefaults();
  }
}

let pickerCategory = null;

function openPicker(categoryId, categoryLabel) {
  pickerCategory = categoryId;
  document.getElementById('picker-title').innerText = 'Predeterminada para: ' + categoryLabel;

  const apps = JSON.parse(System.listApps()).filter(a => a.category === categoryId);
  const optionsEl = document.getElementById('picker-options');
  optionsEl.innerHTML = '';

  if (apps.length === 0) {
    optionsEl.innerHTML = '<p>No hay ninguna app instalada de esta categoría todavía.</p>';
  }

  apps.forEach(app => {
    const opt = document.createElement('div');
    opt.className = 'option';
    opt.innerText = app.name;
    opt.onclick = () => {
      System.setDefaultApp(pickerCategory, app.id);
      closePicker();
      renderDefaults();
    };
    optionsEl.appendChild(opt);
  });

  const clearOpt = document.createElement('div');
  clearOpt.className = 'option';
  clearOpt.style.color = '#FF3B30';
  clearOpt.innerText = 'Quitar predeterminada (preguntar siempre)';
  clearOpt.onclick = () => {
    System.clearDefaultApp(pickerCategory);
    closePicker();
    renderDefaults();
  };
  optionsEl.appendChild(clearOpt);

  document.getElementById('picker-overlay').classList.remove('hidden');
}

function closePicker() {
  document.getElementById('picker-overlay').classList.add('hidden');
}

renderDefaults();
renderApps();
