package com.locol.mbmdroid.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Guarda qué mini-app es la predeterminada para cada categoría (ej.
 * "video_player" -> id del reproductor elegido). Solo existen defaults
 * "de siempre" — la opción "solo esta vez" del diálogo de ChooserActivity
 * simplemente NO llama a setDefault(), así que no persiste nada.
 */
class DefaultAppsManager(context: Context) {

    private val file = File(context.filesDir, "defaults.json")

    fun getDefault(category: String): String? {
        if (!file.exists()) return null
        val obj = JSONObject(file.readText())
        return obj.optString(category, null.toString()).takeIf { it.isNotBlank() && it != "null" }
    }

    fun setDefault(category: String, appId: String) {
        val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        obj.put(category, appId)
        file.writeText(obj.toString())
    }

    fun clearDefault(category: String) {
        if (!file.exists()) return
        val obj = JSONObject(file.readText())
        obj.remove(category)
        file.writeText(obj.toString())
    }

    fun allDefaults(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val obj = JSONObject(file.readText())
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }
}
