package com.locol.mbmdroid.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DownloadState { DOWNLOADING, INSTALLING, COMPLETED, FAILED, CANCELED }

data class DownloadRecord(
    val id: String,
    val appName: String,
    val url: String,
    val category: String,
    var totalBytes: Long,
    var downloadedBytes: Long,
    var state: DownloadState,
    val startedAt: Long,
    var updatedAt: Long,
    var speedBps: Double = 0.0,
    var errorMessage: String? = null,
    var installedAppId: String? = null,
    var canceled: Boolean = false,
    val expectedSha256: String = ""
)

/**
 * Registro persistente de todas las descargas hechas desde la Tienda, con progreso
 * REAL (bytes descargados, velocidad medida, fase actual). Vive en
 * filesDir/downloads.json. El HTML de "Descargas" lo consulta por polling
 * (SystemBridge.listDownloads) mientras haya algo en curso — no hay datos
 * simulados: si el servidor no informa el tamaño, se muestra como desconocido.
 */
class DownloadsManager(private val context: Context) {

    private val file: File get() = File(context.filesDir, "downloads.json")

    @Synchronized
    fun create(appName: String, url: String, category: String, expectedSha256: String = ""): DownloadRecord {
        val now = System.currentTimeMillis()
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(),
            appName = appName,
            url = url,
            category = category,
            totalBytes = -1L,
            downloadedBytes = 0L,
            state = DownloadState.DOWNLOADING,
            startedAt = now,
            updatedAt = now,
            expectedSha256 = expectedSha256
        )
        val list = loadAll().toMutableList()
        list.add(0, record)
        save(list)
        return record
    }

    @Synchronized
    fun update(record: DownloadRecord) {
        record.updatedAt = System.currentTimeMillis()
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) list[idx] = record else list.add(0, record)
        save(list)
    }

    @Synchronized
    fun get(id: String): DownloadRecord? = loadAll().find { it.id == id }

    @Synchronized
    fun listAll(): List<DownloadRecord> = loadAll().sortedByDescending { it.startedAt }

    @Synchronized
    fun remove(id: String) {
        save(loadAll().filterNot { it.id == id })
    }

    @Synchronized
    fun clearFinished() {
        save(loadAll().filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.INSTALLING })
    }

    @Synchronized
    fun markCanceled(id: String) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(canceled = true, updatedAt = System.currentTimeMillis())
            save(list)
        }
    }

    fun isCanceled(id: String): Boolean = get(id)?.canceled == true

    fun listAllJson(): JSONArray {
        val arr = JSONArray()
        listAll().forEach { arr.put(toJson(it)) }
        return arr
    }

    private fun toJson(r: DownloadRecord): JSONObject {
        val percent = if (r.totalBytes > 0) ((r.downloadedBytes * 100.0) / r.totalBytes) else -1.0
        val etaSeconds = if (r.speedBps > 0 && r.totalBytes > 0) {
            ((r.totalBytes - r.downloadedBytes) / r.speedBps).toLong().coerceAtLeast(0)
        } else -1L
        return JSONObject().apply {
            put("id", r.id)
            put("appName", r.appName)
            put("url", r.url)
            put("category", r.category)
            put("totalBytes", r.totalBytes)
            put("downloadedBytes", r.downloadedBytes)
            put("progressPercent", percent)
            put("state", r.state.name.lowercase())
            put("startedAt", r.startedAt)
            put("updatedAt", r.updatedAt)
            put("speedBps", r.speedBps)
            put("etaSeconds", etaSeconds)
            put("errorMessage", r.errorMessage ?: "")
            put("installedAppId", r.installedAppId ?: "")
            put("expectedSha256", r.expectedSha256)
        }
    }

    private fun loadAll(): List<DownloadRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadRecord(
                    id = o.getString("id"),
                    appName = o.getString("appName"),
                    url = o.getString("url"),
                    category = o.optString("category", "other"),
                    totalBytes = o.optLong("totalBytes", -1L),
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    state = DownloadState.valueOf(o.getString("state")),
                    startedAt = o.getLong("startedAt"),
                    updatedAt = o.optLong("updatedAt", o.getLong("startedAt")),
                    speedBps = o.optDouble("speedBps", 0.0),
                    errorMessage = o.optString("errorMessage", null).takeIf { !it.isNullOrBlank() },
                    installedAppId = o.optString("installedAppId", null).takeIf { !it.isNullOrBlank() },
                    canceled = o.optBoolean("canceled", false),
                    expectedSha256 = o.optString("expectedSha256", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("appName", r.appName)
                put("url", r.url)
                put("category", r.category)
                put("totalBytes", r.totalBytes)
                put("downloadedBytes", r.downloadedBytes)
                put("state", r.state.name)
                put("startedAt", r.startedAt)
                put("updatedAt", r.updatedAt)
                put("speedBps", r.speedBps)
                put("errorMessage", r.errorMessage ?: "")
                put("installedAppId", r.installedAppId ?: "")
                put("canceled", r.canceled)
                put("expectedSha256", r.expectedSha256)
            })
        }
        file.writeText(arr.toString())
    }
}
