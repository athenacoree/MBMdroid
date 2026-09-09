package com.locol.mbmdroid.bridge

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.locol.mbmdroid.alarm.AlarmScheduler
import com.locol.mbmdroid.model.MiniApp
import com.locol.mbmdroid.runtime.AppRuntimeActivity

/**
 * Se inyecta en el WebView de cada mini-app como `AndroidBridge`. Desde el
 * HTML/JS de la mini-app se usa así:
 *
 *   AndroidBridge.requestPermission("camera", "onCameraResult")
 *   function onCameraResult(granted) { ... }   // función global que tú defines
 *
 *   AndroidBridge.hasPermission("microphone")  // true/false, sin diálogo
 *
 *   AndroidBridge.enableBackground()   // "no me mates al salir" (ej. reproductor)
 *   AndroidBridge.disableBackground()
 *
 *   AndroidBridge.scheduleAlarm(1, Date.now() + 60000, "Despertador", "¡Hora de levantarse!")
 *   AndroidBridge.cancelAlarm(1)
 *
 * Solo se permite pedir permisos/alarmas/segundo-plano que la mini-app declaró
 * en su manifest.json ("permissions" y "background"), para que quede explícito
 * qué puede hacer cada mini-app antes de instalarla.
 */
class MbmJsBridge(
    private val activity: AppRuntimeActivity,
    private val app: MiniApp,
    private val webView: WebView
) {
    private val alarmScheduler = AlarmScheduler(activity.applicationContext)

    @JavascriptInterface
    fun hasPermission(name: String): Boolean = activity.hasAppPermission(name)

    @JavascriptInterface
    fun requestPermission(name: String, jsCallbackFunctionName: String) {
        if (name !in app.permissions) {
            runJs("$jsCallbackFunctionName(false)")
            return
        }
        activity.requestAppPermission(name) { granted ->
            runJs("$jsCallbackFunctionName($granted)")
        }
    }

    @JavascriptInterface
    fun enableBackground() {
        if (app.background) activity.enableBackgroundMode()
    }

    @JavascriptInterface
    fun disableBackground() {
        activity.disableBackgroundMode()
    }

    @JavascriptInterface
    fun scheduleAlarm(alarmId: Int, triggerAtMillis: Long, title: String, message: String) {
        // El id real de alarma se deriva del id de la app + el id local, para que
        // dos mini-apps distintas no se pisen las alarmas entre sí.
        alarmScheduler.schedule(uniqueAlarmId(alarmId), triggerAtMillis, title, message)
    }

    @JavascriptInterface
    fun cancelAlarm(alarmId: Int) {
        alarmScheduler.cancel(uniqueAlarmId(alarmId))
    }

    @JavascriptInterface
    fun canScheduleExactAlarms(): Boolean = alarmScheduler.canScheduleExactAlarms()

    @JavascriptInterface
    fun openExactAlarmSettings() = activity.openExactAlarmSettings()

    /**
     * Cuando MbMdroid se lanzó porque el usuario abrió un video/audio/imagen desde
     * cualquier otra app del teléfono (ver ChooserActivity) y esta mini-app fue la
     * elegida, aquí están los datos de ese archivo. Devuelve "" si no aplica.
     */
    @JavascriptInterface
    fun getOpenedFileInfo(): String {
        val info = activity.openedFileInfo() ?: return ""
        return org.json.JSONObject().apply {
            put("uri", info.uri.toString())
            put("mimeType", info.mimeType)
            put("name", info.displayName)
        }.toString()
    }

    /** Devuelve el archivo abierto como data URL base64, listo para usar en <video src="">, <img src=""> etc. */
    @JavascriptInterface
    fun readOpenedFileAsDataUrl(): String {
        val info = activity.openedFileInfo() ?: return ""
        return try {
            val bytes = activity.contentResolver.openInputStream(info.uri)?.use { it.readBytes() } ?: return ""
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:${info.mimeType};base64,$base64"
        } catch (_: Exception) {
            ""
        }
    }

    private fun uniqueAlarmId(localId: Int): Int = (app.id.hashCode() * 31) + localId

    private fun runJs(script: String) {
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }
}
