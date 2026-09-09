# MbMdroid

Launcher nativo en Kotlin/Compose que instala y ejecuta paquetes HTML/CSS/JS
como si fueran apps del teléfono. Funciona 100% offline. Un "mini Android
dentro de Android".

## Abrir el proyecto

1. Descomprime este zip.
2. Ábrelo con Android Studio (Koala o más reciente) → "Open" → carpeta `MbMdroid`.
3. Deja que sincronice Gradle (pide internet la primera vez, como cualquier
   proyecto Android nuevo).
4. Ejecuta ▶ en un dispositivo o emulador.

## Cómo se empaqueta una mini-app

```
mi-app.zip
├── manifest.json
├── index.html
├── otra_pantalla.html
├── css/estilos.css
├── js/app.js
└── assets/icon.png
```

```json
{
  "name": "Mi Reproductor",
  "entry": "index.html",
  "icon": "assets/icon.png",
  "version": "1.0",
  "category": "video_player",
  "permissions": ["storage"],
  "background": true
}
```

- **`category`**: obligatoria en la práctica — si no la declaras, MbMdroid te la
  pregunta justo después de instalar. Valores con sentido de "predeterminada":
  `music_player`, `video_player`, `image_viewer`, `document_viewer`. También:
  `clock`, `game`, `utility`, `social`, `other`.
- **`permissions`**: `camera`, `microphone`, `location`, `contacts`,
  `bluetooth`, `notifications`, `storage`.
- **`background`**: `true` si necesita seguir corriendo al salir (música, etc).

## 8 mejoras nuevas de esta ronda (de la lista de "qué le falta a la app")

1. **Validación de manifest.json con mensajes claros** — si falta el campo `name`,
   el `entry` no existe dentro del paquete, o el ícono declarado no está, la
   instalación se cancela limpio y te dice exactamente qué falta (antes fallaba
   silenciosamente con "Paquete inválido").
2. **Límite de tamaño anti zip-bomb / anti llenar el disco** — 300 MB sin
   comprimir por mini-app (`AppManager.MAX_INSTALL_BYTES`); si un .zip lo
   supera, se aborta la instalación con el mensaje exacto.
3. **Manejo de nombres duplicados al instalar** — si ya tienes una app con el
   mismo nombre, aparece un diálogo: **Reemplazar** (borra la vieja) o
   **Instalar aparte** (queda como "Nombre (2)"). Las instalaciones en 2do
   plano (desde la Tienda) resuelven solo, sin bloquear, renombrando si hace falta.
4. **Papelera con "Deshacer"** — desinstalar ya NO borra los archivos al
   toque: aparece un Snackbar "Deshacer" de varios segundos, y además todo
   queda recuperable 7 días desde el menú ⋮ → "Papelera" (restaurar o borrar
   ya definitivamente).
5. **Buscador global** — ícono de lupa en la barra superior del launcher
   principal; busca por nombre entre tus apps instaladas/suspendidas al
   instante, sin filtros de categoría de por medio.
6. **Modo oscuro** — ícono de sol/luna en la barra superior, con la
   preferencia guardada (no depende del tema del sistema salvo la primera vez).
7. **Notificación real de progreso de descarga** — ahora, además de verse
   dentro de la Tienda, cada descarga muestra una notificación de Android con
   barra de progreso ("Descargando… 42%" → "Instalando…" → "Instalación
   completa" o el error), usando el mismo canal de notificaciones que ya
   existía para apps en 2do plano.
8. **Copia de seguridad / restauración completa** — menú ⋮ → "Exportar copia
   de seguridad" empaqueta TODAS tus mini-apps (no las de sistema) en un
   único .zip que eliges dónde guardar; "Restaurar copia de seguridad" la
   trae de vuelta completa (útil si cambias de teléfono o reinstalas MbMdroid).

## Lo nuevo en la actualización anterior (Tienda + Almacenamiento)

### A. Antes de descargar: peso real y tiempo estimado
En la Tienda, cada ítem del catálogo consulta el peso real del `.zip` remoto
(`SystemBridge.getUrlSizeInfo`, HEAD/GET sin descargar el archivo) y lo
muestra antes de que toques "Instalar". Si el servidor no informa el tamaño,
se avisa honestamente ("Peso no disponible") en vez de inventar un número.

### B. Progreso real de descarga + instalación por separado
Al tocar "Instalar", `AppRuntimeActivity.startTrackedDownload` descarga por
streaming (bloques de 16 KB) actualizando cada ~200ms: bytes descargados,
velocidad medida (no estimada) y tiempo restante calculado con esa velocidad
real. Cuando termina de bajar, pasa a fase **"Instalando…"** (extracción +
registro) antes de marcarse como completada. Todo esto se ve tanto en la
tarjeta del catálogo como en la pestaña nueva de Descargas.

### C. Pestaña "Descargas" dentro de la Tienda (estilo App Store reciente)
`store/descargas.html`... en realidad va integrado como una segunda pestaña
de `store/index.html` (barra inferior Tienda / Descargas) para no duplicar
lógica de instalación. Lista TODAS las descargas de todas las apps —no solo
la última—, con:
- Ícono con anillo de progreso circular (como en el App Store de iOS).
- Velocidad y tiempo restante en vivo mientras descarga.
- Botón cancelar durante la descarga (cancelación cooperativa real, corta el
  stream).
- Reintentar / quitar en descargas fallidas o canceladas.
- "Borrar completadas" para limpiar el historial.
- Badge con el número de descargas activas en la pestaña.

Internamente esto vive en `DownloadsManager.kt` (registro persistente en
`filesDir/downloads.json`, sin datos simulados: si algo no se sabe, se
muestra como desconocido) y se consulta por *polling* desde el HTML
(`SystemBridge.listDownloads`) cada 400–700ms mientras hay algo activo.

### D. Nueva app de sistema: Almacenamiento (gestor de archivos)
`system_apps/storage/` — protegida como Ajustes y Tienda, preinstalada y no
desinstalable. Muestra:
- Barra de espacio REAL del teléfono (`StatFs` sobre el almacenamiento
  interno) con cuánto ocupan tus mini-apps y cuánto queda libre.
- Lista de tus mini-apps con su peso real en disco (suma recursiva de
  archivos, no un número inventado) y cantidad de archivos; al tocar una
  se abre una ficha con más detalle.
- Lista de las apps REALES instaladas en el teléfono (ícono y peso del APK
  reales, vía `PackageManager`), con buscador — y al tocar una se abre
  directamente (`SystemBridge.launchPhoneApp`). Esto es lo que cubre "abrir
  otras apps del teléfono" desde dentro de MbMdroid.

Requiere el permiso `QUERY_ALL_PACKAGES` (agregado al manifest) para poder
listar apps de terceros en Android 11+.

### Qué NO se incluyó todavía (honesto, para que sepas qué falta)
- Streaming real para reproducir mientras se descarga (hoy la descarga
  termina y LUEGO se instala; no hay reproducción progresiva).
- Notificación persistente del progreso de descarga en la barra de Android
  (hoy el progreso solo se ve dentro de la app, como pediste para la
  pantalla de Descargas — si además quieres la notificación del sistema,
  se puede añadir con `MiniAppForegroundService`, que ya existe para
  música).
- Selector "abrir apps del teléfono" como accesos directos en la pantalla
  principal de MbMdroid (hoy vive dentro de Almacenamiento, no en el
  launcher principal).
- Borrado/gestión de archivos sueltos del teléfono (galería, descargas del
  navegador, etc.) — Almacenamiento hoy solo gestiona lo que es de MbMdroid
  + abrir apps ajenas, no un explorador de archivos genérico de Android
  (eso requeriría permisos de almacenamiento amplios adicionales).
- Vista de diferencias línea por línea del actualizador (ya estaba pendiente
  antes de este cambio).

## Lo nuevo en la versión anterior

### 1. Categorías + apps predeterminadas ("abrir con... siempre")
`ChooserActivity` está registrada en el `AndroidManifest` con intent-filters
reales para `video/*`, `audio/*`, `image/*` y `application/pdf`. Esto hace que
**Android ofrezca a MbMdroid en su propio diálogo nativo de "Abrir con..."**
cuando el usuario abre un archivo desde el explorador, la galería, WhatsApp,
etc — no solo dentro de MbMdroid.

- Si ya hay una predeterminada para esa categoría (`DefaultAppsManager`), se
  lanza directo la mini-app correspondiente.
- Si no, MbMdroid muestra su propio diálogo "Abrir con..." con las mini-apps
  de esa categoría y los botones **Siempre** (guarda el default) / **Solo esta
  vez** (no guarda nada, se vuelve a preguntar la próxima vez).
- La mini-app elegida recibe el archivo vía
  `AndroidBridge.getOpenedFileInfo()` / `AndroidBridge.readOpenedFileAsDataUrl()`
  (esta última devuelve un `data:` URL en base64 listo para `<video src="">`
  o `<img src="">`; para archivos muy grandes, ver "Limitaciones" abajo).

Los defaults se gestionan desde el HTML de **Ajustes** (ver más abajo).

### 2. Apps de sistema protegidas: Ajustes y Tienda
Vienen **preinstaladas dentro de la APK** (`assets/system_apps/settings` y
`assets/system_apps/store`) y se copian a almacenamiento interno la primera
vez que arranca la app (`AppManager.installSystemAppFromAssets`, llamado
desde `MainActivity.onCreate`). Tienen `isSystem = true`:

- El usuario **no puede desinstalarlas** (`AppManager.uninstall` las rechaza,
  y el botón "Desinstalar" ni siquiera aparece en su ficha).
- **Nadie puede editarlas desde MbMdroid** — no hay ninguna función que
  permita a un usuario final modificar el HTML de una app de sistema, solo verlo
  (botón "Código", de solo lectura).
- **Se actualizan sin recompilar el APK**: usan exactamente el mismo mecanismo
  de actualización que cualquier mini-app de terceros (ver punto 4) — el
  creador les distribuye un `.zip` nuevo (por la Tienda, por archivo, por
  donde sea) y `AppUpdater` reemplaza sus archivos con precisión, sin tocar
  el `id` ni el flag `isSystem`.

Ambas son HTML reales, ubicadas en la **raíz del proyecto**
(`app/src/main/assets/system_apps/`), y hablan con un puente exclusivo
llamado `System` (`SystemBridge.kt`) que **solo se inyecta en apps con
`isSystem = true`** — una mini-app normal jamás lo recibe.

**Ajustes** (`system_apps/settings/`): lista todas las categorías "abribles"
con su predeterminada actual y un selector para cambiarla; lista todas las
apps instaladas con botones Código / Actualizar / Borrar.

**Tienda** (`system_apps/store/`): botón para instalar desde un `.zip` local
(funciona sin conexión) y un catálogo (`catalog.json`, bundled de ejemplo —
reemplázalo por uno real alojado en tu propio servidor) con botones
"Instalar" que descargan por URL (esto sí requiere internet en ese momento).

### 3. Ver el código de cualquier app, de cualquier formato
`CodeViewerActivity` (nativa) lista todos los archivos de la carpeta de una
mini-app —cualquier extensión, no solo HTML/CSS/JS— y al tocar uno muestra su
contenido como texto plano si es un formato de texto conocido
(html/css/js/json/txt/svg/md), o "archivo binario · N bytes" si no lo es (una
imagen, un mp3, etc). Se accede desde "Ver código" en la ficha de cualquier
app (mantener presionado su ícono) o desde el botón "Código" en Ajustes.

### 4. Actualizar con diff real, exacto y offline
Desde la ficha de cualquier app (mantener presionado → **Actualizar**) o
desde Ajustes: eliges un `.zip` nuevo del teléfono. `AppUpdater.kt`:

1. Extrae el paquete nuevo a una carpeta temporal.
2. Compara **archivo por archivo** contra el paquete instalado (hash SHA-256
   para saber si cambió) y clasifica cada uno como agregado, eliminado,
   modificado o sin cambios.
3. Para archivos de texto modificados (html/css/js/json/txt/svg/md), calcula
   además **líneas agregadas/eliminadas** con un algoritmo LCS clásico
   (programación dinámica).
4. Te muestra un resumen (archivos nuevos/eliminados/modificados, líneas +/-,
   lista de qué cambió) **antes de tocar nada**.
5. Al confirmar, aplica **exactamente** ese plan: copia los archivos
   nuevos/modificados, borra los eliminados, no toca los que no cambiaron —
   y actualiza el registro (versión, permisos, categoría) desde el
   `manifest.json` nuevo.

Todo corre sobre archivos locales ya extraídos — no depende de internet en
ningún paso de esta comparación ni de la aplicación del cambio.

## Cómo funciona lo que ya tenías (resumen)

- **Múltiples HTML/CSS/JS por app**: `WebViewAssetLoader` sirve toda la
  carpeta de la mini-app en `https://appassets.androidplatform.net/`.
- **Permisos por app**: declarados en el manifest, pedidos vía
  `AndroidBridge.requestPermission(...)`, con diálogo real de Android.
- **Segundo plano real** (música que sigue sonando): el WebView vive en
  `RuntimeRegistry`, no atado a la Activity; `MiniAppForegroundService`
  sostiene el proceso con una notificación.
- **Alarmas que suenan con la app cerrada del todo**: `AlarmManager` del
  sistema operativo directamente, vía `AlarmScheduler` / `AlarmReceiver`.

## Estructura del proyecto

```
model/      MiniApp, AppStatus
data/       AppManager, DefaultAppsManager, AppUpdater, CategoryCatalog
bridge/     MbmJsBridge (AndroidBridge, todas las apps), SystemBridge (System, solo isSystem), PermissionMapper
runtime/    AppRuntimeActivity, RuntimeRegistry, MiniAppForegroundService, ChooserActivity, OpenedFileInfo
alarm/      AlarmScheduler, AlarmReceiver
ui/         MainActivity (launcher), CodeViewerActivity
assets/system_apps/settings/   HTML de Ajustes (app de sistema, protegida)
assets/system_apps/store/      HTML de Tienda (app de sistema, protegida)
```

## Limitaciones a tener en cuenta

- `readOpenedFileAsDataUrl()` carga el archivo completo en memoria como
  base64. Para videos muy grandes conviene, más adelante, servirlo por
  streaming en vez de un `data:` URL — queda anotado como mejora futura.
- El diff de líneas usa una matriz LCS O(n·m); perfecto para archivos típicos
  de una mini-app (HTML/CSS/JS de tamaño normal), no pensado para archivos de
  texto enormes.
- El catálogo de la Tienda (`catalog.json`) es un ejemplo — para que
  "Instalar" funcione de verdad hace falta alojar tus propios `.zip` en un
  servidor real y apuntar las URLs ahí.

## Próximos pasos sugeridos (no incluidos aún)

- Pantalla de detalle de app (ficha tipo Play Store) antes de abrir/instalar
- Splash screen y firma de release
- Vista de diferencias línea por línea (hoy se muestra el conteo +/-, no el
  texto exacto que cambió línea por línea)
