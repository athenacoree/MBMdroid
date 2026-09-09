package com.locol.mbmdroid.runtime

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.locol.mbmdroid.bridge.MbmJsBridge
import com.locol.mbmdroid.bridge.PermissionMapper
import com.locol.mbmdroid.bridge.SystemBridge
import com.locol.mbmdroid.data.AppManager
import com.locol.mbmdroid.data.AppUpdater
import com.locol.mbmdroid.data.DownloadRecord
import com.locol.mbmdroid.data.DownloadState
import com.locol.mbmdroid.data.DownloadsManager
import com.locol.mbmdroid.data.FileChangeType
import com.locol.mbmdroid.data.UpdatePlan
import com.locol.mbmdroid.model.MiniApp
import com.locol.mbmdroid.ui.CodeViewerActivity
import com.locol.mbmdroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ejecuta una mini-app instalada. Usa WebViewAssetLoader para servir los archivos
 * locales, reutiliza el WebView desde RuntimeRegistry si venía de segundo plano,
 * y expone `AndroidBridge` a todas las apps y `System` (SystemBridge) solo a las
 * apps de sistema (Ajustes, Tienda).
 */
class AppRuntimeActivity : AppCompatActivity() {

    private lateinit var appId: String
    private lateinit var appManager: AppManager
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingInstallCallback: ((Boolean, String) -> Unit)? = null
    private var openedFile: OpenedFileInfo? = null
    private var pendingUpdateAppId: String? = null
    private val downloadsManager by lazy { DownloadsManager(applicationContext) }
    private val activeDownloadJobs = mutableMapOf<String, Job>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        pendingPermissionCallback?.invoke(granted)
        pendingPermissionCallback = null
    }

    private val installPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            pendingInstallCallback?.invoke(false, "Cancelado")
        } else {
            val result = appManager.installAutoResolving(uri)
            pendingInstallCallback?.invoke(
                result.isSuccess,
                result.fold({ "Instalada: ${it.name}" }, { it.message ?: "Error al instalar" })
            )
        }
        pendingInstallCallback = null
    }

    private val updatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val targetId = pendingUpdateAppId
        pendingUpdateAppId = null
        if (uri != null && targetId != null) runUpdateFlow(targetId, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appId = intent.getStringExtra(EXTRA_APP_ID) ?: return finish()
        appManager = AppManager(this)
        val app = appManager.getById(appId) ?: return finish()
        title = app.name

        val uriExtra = getOpenedUriCompat()
        if (uriExtra != null) {
            openedFile = OpenedFileInfo(
                uri = uriExtra,
                mimeType = intent.getStringExtra(EXTRA_OPENED_MIME) ?: "*/*",
                displayName = uriExtra.lastPathSegment ?: "archivo"
            )
        }

        val existing = RuntimeRegistry.get(appId)
        val webView = existing ?: createWebView(app)

        setContentView(webView)

        if (existing == null) {
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(this, appManager.appDir(appId)))
                .build()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    RuntimeRegistry.destroy(appId)
                    AlertDialog.Builder(this@AppRuntimeActivity)
                        .setTitle("La app se cerró inesperadamente")
                        .setMessage("El proceso de renderizado de la mini-app fallo. Se ha cerrado de forma segura.")
                        .setPositiveButton("Volver al launcher") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                    return true
                }
            }
            webView.loadUrl("https://appassets.androidplatform.net/${app.entryPoint}")
        } else {
            RuntimeRegistry.detach(appId)
        }
    }

    private fun getOpenedUriCompat(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_OPENED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_OPENED_URI)
        }

    private fun createWebView(app: MiniApp): WebView {
        val webView = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        webView.addJavascriptInterface(MbmJsBridge(this, app, webView), "AndroidBridge")
        if (app.isSystem && app.isOfficialSystemId()) {
            webView.addJavascriptInterface(SystemBridge(this, app, webView), "System")
        }
        RuntimeRegistry.put(appId, webView)
        return webView
    }

    fun openedFileInfo(): OpenedFileInfo? = openedFile

    // ---- permisos ----

    fun hasAppPermission(name: String): Boolean {
        val perms = PermissionMapper.androidPermissionsFor(name)
        if (perms.isEmpty()) return false
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun requestAppPermission(name: String, callback: (Boolean) -> Unit) {
        val perms = PermissionMapper.androidPermissionsFor(name)
        if (perms.isEmpty()) return callback(false)
        if (hasAppPermission(name)) return callback(true)
        pendingPermissionCallback = callback
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun openExactAlarmSettings() {
        startActivity(com.locol.mbmdroid.alarm.AlarmScheduler(this).exactAlarmSettingsIntent())
    }

    // ---- segundo plano ----

    fun enableBackgroundMode() {
        RuntimeRegistry.enableBackground(appId)
        ContextCompat.startForegroundService(this, Intent(this, MiniAppForegroundService::class.java))
    }

    fun disableBackgroundMode() {
        RuntimeRegistry.disableBackground(appId)
        if (!RuntimeRegistry.hasAnyBackgroundApp()) {
            stopService(Intent(this, MiniAppForegroundService::class.java))
        }
    }

    // ---- solo apps de sistema (SystemBridge) ----

    fun launchOtherApp(targetAppId: String) {
        startActivity(Intent(this, AppRuntimeActivity::class.java).putExtra(EXTRA_APP_ID, targetAppId))
    }

    fun launchInstallPicker(callback: (Boolean, String) -> Unit) {
        pendingInstallCallback = callback
        installPickerLauncher.launch("application/zip")
    }

    fun launchUpdateFlow(targetAppId: String) {
        pendingUpdateAppId = targetAppId
        updatePickerLauncher.launch("application/zip")
    }

    fun openCodeViewer(targetAppId: String) {
        startActivity(Intent(this, CodeViewerActivity::class.java).putExtra(CodeViewerActivity.EXTRA_APP_ID, targetAppId))
    }

    /** Consulta el tamaño real de un archivo remoto ANTES de descargarlo (para mostrarlo en la Tienda). */
    fun fetchUrlSize(url: String, callback: (Long, String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var length = -1L
                var lastError: String? = null
                // Primero HEAD (rápido, no baja nada). Si el servidor no lo soporta bien, probamos GET.
                for (method in listOf("HEAD", "GET")) {
                    if (length > 0L) break
                    try {
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            requestMethod = method
                            connectTimeout = 8000
                            readTimeout = 8000
                        }
                        connection.connect()
                        length = connection.contentLengthLong
                        connection.disconnect()
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                }
                withContext(Dispatchers.Main) { callback(length, if (length > 0L) null else lastError) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(-1L, e.message ?: "No se pudo consultar el archivo") }
            }
        }
    }

    /**
     * Inicia una descarga con progreso REAL (bytes descargados, velocidad medida cada ~200ms,
     * tiempo estimado restante) registrada en [DownloadsManager], y al terminar instala el
     * paquete automáticamente. Devuelve de inmediato el id de la descarga para que el HTML
     * pueda mostrarla en "Descargas" mientras corre en segundo plano.
     */
    fun startTrackedDownload(url: String, appName: String, category: String, expectedSha256: String = ""): String {
        val record = downloadsManager.create(appName, url, category, expectedSha256)
        ensureDownloadChannel()

        fun pushUpdate(rec: DownloadRecord) {
            downloadsManager.update(rec)
            notifyDownloadProgress(rec)
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            val tempFile = File(cacheDir, "download_${record.id}.zip")
            try {
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://localhost", ignoreCase = true)) {
                    throw SecurityException("Por seguridad, solo se permiten descargas HTTPS")
                }

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                connection.connect()
                val total = connection.contentLengthLong
                pushUpdate(record.copy(totalBytes = total))

                var downloaded = 0L
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var lastTick = System.currentTimeMillis()
                        var lastDownloaded = 0L
                        while (true) {
                            if (downloadsManager.isCanceled(record.id)) {
                                throw InterruptedIOException("Cancelado por el usuario")
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 200) {
                                val elapsedSec = (now - lastTick) / 1000.0
                                val speed = if (elapsedSec > 0) (downloaded - lastDownloaded) / elapsedSec else 0.0
                                pushUpdate(
                                    (downloadsManager.get(record.id) ?: record).copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        speedBps = speed
                                    )
                                )
                                lastTick = now
                                lastDownloaded = downloaded
                            }
                        }
                    }
                }
                connection.disconnect()

                if (expectedSha256.isNotBlank()) {
                    val actualSha = computeSha256(tempFile)
                    if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                        throw SecurityException("Verificación de integridad fallida: el hash SHA-256 no coincide")
                    }
                }

                pushUpdate(
                    (downloadsManager.get(record.id) ?: record).copy(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        state = DownloadState.INSTALLING
                    )
                )

                val result = appManager.installAutoResolving(Uri.fromFile(tempFile))
                tempFile.delete()
                val current = downloadsManager.get(record.id) ?: record
                result.onSuccess { app ->
                    pushUpdate(current.copy(state = DownloadState.COMPLETED, installedAppId = app.id))
                }.onFailure { e ->
                    pushUpdate(current.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "Error al instalar"))
                }
            } catch (e: Exception) {
                tempFile.delete()
                val current = downloadsManager.get(record.id) ?: record
                val wasCanceled = current.canceled || e is InterruptedIOException
                pushUpdate(
                    current.copy(
                        state = if (wasCanceled) DownloadState.CANCELED else DownloadState.FAILED,
                        errorMessage = if (wasCanceled) "Cancelado por el usuario" else (e.message ?: "Sin conexión o error de red")
                    )
                )
            } finally {
                activeDownloadJobs.remove(record.id)
            }
        }
        activeDownloadJobs[record.id] = job
        return record.id
    }

    private fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureDownloadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(DOWNLOAD_CHANNEL_ID, "MbMdroid — Descargas", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Muestra/actualiza en la barra de notificaciones de Android el progreso real de una descarga. */
    private fun notifyDownloadProgress(rec: DownloadRecord) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        val notificationId = DOWNLOAD_NOTIFICATION_BASE_ID + rec.id.hashCode()

        val openAppIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(rec.appName)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)

        when (rec.state) {
            DownloadState.DOWNLOADING -> {
                builder.setOngoing(true)
                if (rec.totalBytes > 0) {
                    val percent = ((rec.downloadedBytes * 100.0) / rec.totalBytes).toInt()
                    builder.setContentText("Descargando… $percent%").setProgress(100, percent, false)
                } else {
                    builder.setContentText("Descargando…").setProgress(0, 0, true)
                }
            }
            DownloadState.INSTALLING -> {
                builder.setOngoing(true).setContentText("Instalando…").setProgress(0, 0, true)
            }
            DownloadState.COMPLETED -> {
                builder.setOngoing(false).setAutoCancel(true).setProgress(0, 0, false)
                    .setContentText("Instalación completa").setSmallIcon(android.R.drawable.stat_sys_download_done)
            }
            DownloadState.FAILED -> {
                builder.setOngoing(false).setAutoCancel(true).setProgress(0, 0, false)
                    .setContentText(rec.errorMessage ?: "No se pudo instalar")
            }
            DownloadState.CANCELED -> {
                manager.cancel(notificationId)
                return
            }
        }
        manager.notify(notificationId, builder.build())
    }

    fun cancelTrackedDownload(id: String): Boolean {
        downloadsManager.markCanceled(id)
        return true
    }

    fun listTrackedDownloads(): String = downloadsManager.listAllJson().toString()

    fun clearTrackedDownload(id: String): Boolean {
        val rec = downloadsManager.get(id) ?: return false
        if (rec.state == DownloadState.DOWNLOADING || rec.state == DownloadState.INSTALLING) return false
        downloadsManager.remove(id)
        return true
    }

    fun clearFinishedTrackedDownloads() = downloadsManager.clearFinished()

    /** Calcula el diff contra el paquete instalado y muestra un diálogo nativo para confirmar antes de aplicar. */
    fun runUpdateFlow(targetAppId: String, newZipUri: Uri) {
        val app = appManager.getById(targetAppId) ?: return
        val updater = AppUpdater(this)
        CoroutineScope(Dispatchers.IO).launch {
            val result = updater.stage(targetAppId, appManager.appDir(targetAppId), newZipUri, app.isSystem)
            withContext(Dispatchers.Main) {
                result.onSuccess { plan -> showUpdateConfirmDialog(app, plan, updater) }
                result.onFailure { showSimpleDialog("No se pudo preparar la actualización", it.message ?: "Error") }
            }
        }
    }

    private fun showUpdateConfirmDialog(app: MiniApp, plan: UpdatePlan, updater: AppUpdater) {
        val added = plan.diffs.count { it.type == FileChangeType.ADDED }
        val removed = plan.diffs.count { it.type == FileChangeType.REMOVED }
        val modified = plan.diffs.filter { it.type == FileChangeType.MODIFIED }
        val linesAdded = modified.sumOf { it.linesAdded }
        val linesRemoved = modified.sumOf { it.linesRemoved }

        val details = buildString {
            append("Versión nueva: ${plan.newVersion}\n\n")
            append("Archivos nuevos: $added\n")
            append("Archivos eliminados: $removed\n")
            append("Archivos modificados: ${modified.size}\n")
            append("Líneas agregadas: +$linesAdded  ·  Líneas eliminadas: -$linesRemoved\n\n")
            if (modified.isNotEmpty()) {
                append("Cambiaron:\n")
                modified.take(15).forEach { append(" • ${it.path} (+${it.linesAdded}/-${it.linesRemoved})\n") }
                if (modified.size > 15) append(" ...y ${modified.size - 15} más\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Actualizar ${app.name}")
            .setMessage(details)
            .setPositiveButton("Aplicar exactamente") { _, _ ->
                updater.apply(appManager.appDir(app.id), plan)
                appManager.refreshFromManifest(app.id)
                RuntimeRegistry.destroy(app.id) // fuerza recarga limpia con el código nuevo
                showSimpleDialog("Listo", "${app.name} se actualizó a la versión ${plan.newVersion}.")
            }
            .setNegativeButton("Cancelar") { _, _ -> updater.discard(plan) }
            .setCancelable(false)
            .show()
    }

    private fun showSimpleDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() {
        if (!RuntimeRegistry.isBackgroundEnabled(appId)) {
            RuntimeRegistry.destroy(appId)
        } else {
            RuntimeRegistry.detach(appId)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        private const val DOWNLOAD_CHANNEL_ID = "mbmdroid_downloads"
        private const val DOWNLOAD_NOTIFICATION_BASE_ID = 5000
        const val EXTRA_OPENED_URI = "extra_opened_uri"
        const val EXTRA_OPENED_MIME = "extra_opened_mime"
    }
}
