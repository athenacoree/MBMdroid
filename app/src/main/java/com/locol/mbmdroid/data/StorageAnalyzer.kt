package com.locol.mbmdroid.data

import android.content.Context
import android.os.StatFs
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Calcula tamaños REALES para la app de Almacenamiento — nada simulado:
 * - Espacio del teléfono vía StatFs sobre el almacenamiento interno real.
 * - Peso real de cada mini-app sumando los bytes de todos sus archivos en
 *   filesDir/apps/<id>/.
 */
class StorageAnalyzer(private val context: Context, private val appManager: AppManager) {

    fun summaryJson(): JSONObject {
        val statFs = StatFs(context.filesDir.path)
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val usedByMiniApps = appManager.allApps().sumOf { folderSize(appManager.appDir(it.id)) }
        return JSONObject().apply {
            put("totalBytes", totalBytes)
            put("freeBytes", freeBytes)
            put("usedBytes", totalBytes - freeBytes)
            put("usedByMiniAppsBytes", usedByMiniApps)
        }
    }

    fun appsJson(): JSONArray {
        val arr = JSONArray()
        appManager.allApps().forEach { app ->
            arr.put(JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("category", app.category)
                put("isSystem", app.isSystem)
                put("status", app.status.name)
                put("version", app.version)
                put("sizeBytes", folderSize(appManager.appDir(app.id)))
                put("fileCount", fileCount(appManager.appDir(app.id)))
                put("iconDataUrl", iconDataUrl(app.id) ?: "")
            })
        }
        return arr
    }

    fun iconDataUrl(appId: String): String? {
        val app = appManager.getById(appId) ?: return null
        val iconFile = appManager.iconFile(app) ?: return null
        return try {
            val bytes = iconFile.readBytes()
            val mime = when (iconFile.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun fileCount(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }
}
