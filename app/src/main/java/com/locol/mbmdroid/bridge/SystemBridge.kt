package com.locol.mbmdroid.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.locol.mbmdroid.data.AppManager
import com.locol.mbmdroid.data.CategoryCatalog
import com.locol.mbmdroid.data.DefaultAppsManager
import com.locol.mbmdroid.data.PhoneAppsManager
import com.locol.mbmdroid.data.StorageAnalyzer
import com.locol.mbmdroid.model.AppStatus
import com.locol.mbmdroid.runtime.AppRuntimeActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Puente exclusivo de las apps de sistema (isSystem == true). Le da a Ajustes
 * y a la Tienda control total sobre el catálogo de mini-apps: listar, ver
 * categorías, gestionar predeterminadas, instalar, actualizar, suspender.
 *
 * Una mini-app normal (de terceros) NUNCA recibe este puente — solo el
 * `AndroidBridge` normal (MbmJsBridge), mucho más restringido.
 */
class SystemBridge(
    private val activity: AppRuntimeActivity,
    private val app: com.locol.mbmdroid.model.MiniApp,
    private val webView: WebView
) {
    init {
        require(app.isSystem && app.isOfficialSystemId()) {
            "SystemBridge solo puede ser instanciado por apps del sistema con identificadores oficiales"
        }
    }
    private val appManager = AppManager(activity.applicationContext)
    private val defaultsManager = DefaultAppsManager(activity.applicationContext)
    private val storageAnalyzer = StorageAnalyzer(activity.applicationContext, appManager)
    private val phoneAppsManager = PhoneAppsManager(activity.applicationContext)

    @JavascriptInterface
    fun listApps(): String {
        val arr = JSONArray()
        appManager.allApps().forEach { app ->
            arr.put(JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("version", app.version)
                put("category", app.category)
                put("categoryLabel", CategoryCatalog.label(app.category))
                put("status", app.status.name)
                put("isSystem", app.isSystem)
                put("background", app.background)
                put("permissions", JSONArray(app.permissions))
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun listCategories(): String {
        val arr = JSONArray()
        CategoryCatalog.openableCategories().forEach { cat ->
            arr.put(JSONObject().apply {
                put("id", cat.id)
                put("label", cat.label)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun getDefaultApps(): String {
        val obj = JSONObject()
        defaultsManager.allDefaults().forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @JavascriptInterface
    fun setDefaultApp(category: String, appId: String) = defaultsManager.setDefault(category, appId)

    @JavascriptInterface
    fun clearDefaultApp(category: String) = defaultsManager.clearDefault(category)

    @JavascriptInterface
    fun suspendApp(appId: String) {
        val app = appManager.getById(appId) ?: return
        if (app.isSystem) return
        appManager.setStatus(appId, AppStatus.SUSPENDED)
    }

    @JavascriptInterface
    fun resumeApp(appId: String) = appManager.setStatus(appId, AppStatus.INSTALLED)

    @JavascriptInterface
    fun uninstallApp(appId: String): Boolean = appManager.uninstall(appId).isSuccess

    @JavascriptInterface
    fun launchApp(appId: String) = activity.launchOtherApp(appId)

    /** Abre el selector de archivos para instalar una app nueva desde un .zip local. */
    @JavascriptInterface
    fun pickAndInstallFromZip(jsCallbackFunctionName: String) {
        activity.launchInstallPicker { success, message ->
            runJs("$jsCallbackFunctionName($success, ${JSONObject.quote(message)})")
        }
    }

    /** Consulta el peso real de un .zip remoto ANTES de descargarlo, sin bajarlo. */
    @JavascriptInterface
    fun getUrlSizeInfo(url: String, jsCallbackFunctionName: String) {
        activity.fetchUrlSize(url) { sizeBytes, error ->
            runJs("$jsCallbackFunctionName($sizeBytes, ${JSONObject.quote(error ?: "")})")
        }
    }

    /**
     * Inicia una descarga con progreso real (bytes, velocidad, tiempo estimado) e instalación
     * automática al terminar. Devuelve el id de la descarga de inmediato — la pantalla
     * "Descargas" debe consultar [listDownloads] periódicamente para ver su avance.
     */
    @JavascriptInterface
    fun startDownload(url: String, appName: String, category: String, expectedSha256: String = ""): String =
        activity.startTrackedDownload(url, appName, category, expectedSha256)

    /** Lista TODAS las descargas (de cualquier app), más recientes primero, con su progreso actual. */
    @JavascriptInterface
    fun listDownloads(): String = activity.listTrackedDownloads()

    @JavascriptInterface
    fun cancelDownload(id: String): Boolean = activity.cancelTrackedDownload(id)

    /** Reintenta una descarga fallida/cancelada: borra el registro viejo y arranca una nueva. */
    @JavascriptInterface
    fun retryDownload(id: String, url: String, appName: String, category: String): String {
        activity.clearTrackedDownload(id)
        return activity.startTrackedDownload(url, appName, category)
    }

    @JavascriptInterface
    fun clearDownload(id: String): Boolean = activity.clearTrackedDownload(id)

    @JavascriptInterface
    fun clearFinishedDownloads() = activity.clearFinishedTrackedDownloads()

    /** Espacio real del teléfono (StatFs) + espacio real ocupado por las mini-apps instaladas. */
    @JavascriptInterface
    fun getStorageSummary(): String = storageAnalyzer.summaryJson().toString()

    /** Peso real (recursivo, en disco) de cada mini-app instalada, para el gestor de archivos. */
    @JavascriptInterface
    fun listAppStorage(): String = storageAnalyzer.appsJson().toString()

    /** Apps REALES instaladas en el teléfono (fuera de MbMdroid), con ícono y peso de APK reales. */
    @JavascriptInterface
    fun listPhoneApps(): String = phoneAppsManager.listAppsJson().toString()

    @JavascriptInterface
    fun launchPhoneApp(packageName: String): Boolean = phoneAppsManager.launch(packageName)

    /** Abre el selector de archivos para actualizar una app existente; delega el diff a la UI nativa. */
    @JavascriptInterface
    fun pickAndUpdateApp(appId: String) = activity.launchUpdateFlow(appId)

    @JavascriptInterface
    fun openCodeViewer(appId: String) = activity.openCodeViewer(appId)

    private fun runJs(script: String) {
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }
}
