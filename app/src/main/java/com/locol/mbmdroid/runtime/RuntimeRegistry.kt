package com.locol.mbmdroid.runtime

import android.view.ViewGroup
import android.webkit.WebView

/**
 * Mantiene con vida las instancias de WebView de las mini-apps que activaron
 * el modo segundo plano (AndroidBridge.enableBackground()), incluso después
 * de que su AppRuntimeActivity se destruya (usuario sale, abre WhatsApp, etc).
 *
 * Esto es lo que permite que un reproductor de música siga sonando al salir
 * de la app: el WebView (y el <audio>/<video> corriendo dentro) no se destruye,
 * solo se "desconecta" de la pantalla. Un MiniAppForegroundService mantiene
 * vivo el proceso con una notificación mientras haya WebViews registrados.
 */
object RuntimeRegistry {

    private val liveWebViews = mutableMapOf<String, WebView>()
    private val backgroundEnabled = mutableSetOf<String>()

    fun get(appId: String): WebView? = liveWebViews[appId]

    fun put(appId: String, webView: WebView) {
        liveWebViews[appId] = webView
    }

    /** Desconecta el WebView de su contenedor visual actual sin destruirlo. */
    fun detach(appId: String) {
        liveWebViews[appId]?.let { wv -> (wv.parent as? ViewGroup)?.removeView(wv) }
    }

    /** Destruye de verdad el WebView (cuando la app NO tiene segundo plano habilitado). */
    fun destroy(appId: String) {
        liveWebViews.remove(appId)?.destroy()
        backgroundEnabled.remove(appId)
    }

    fun enableBackground(appId: String) {
        backgroundEnabled.add(appId)
    }

    fun disableBackground(appId: String) {
        backgroundEnabled.remove(appId)
    }

    fun isBackgroundEnabled(appId: String): Boolean = appId in backgroundEnabled

    fun hasAnyBackgroundApp(): Boolean = backgroundEnabled.isNotEmpty()

    fun backgroundAppIds(): Set<String> = backgroundEnabled.toSet()
}
