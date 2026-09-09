package com.locol.mbmdroid.data

import android.content.Context
import android.net.Uri
import com.locol.mbmdroid.model.MiniApp
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Tipo de cambio detectado para un archivo entre la versión instalada y la nueva. */
enum class FileChangeType { ADDED, REMOVED, MODIFIED, UNCHANGED }

data class FileDiff(
    val path: String,
    val type: FileChangeType,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0
)

data class UpdatePlan(
    val appId: String,
    val stagedDir: File,
    val newVersion: String,
    val diffs: List<FileDiff>
) {
    val changedCount get() = diffs.count { it.type != FileChangeType.UNCHANGED }
}

/**
 * Compara el paquete instalado de una mini-app contra un paquete nuevo (.zip)
 * y aplica exactamente los cambios detectados — sin recompilar nada y sin
 * depender de internet (todo corre sobre archivos locales ya extraídos).
 *
 * El "diff" es a nivel de archivo (agregado/eliminado/modificado) y, para
 * archivos de texto (html/css/js/json/txt), además cuenta líneas agregadas y
 * eliminadas con un algoritmo LCS clásico — suficiente para que el usuario
 * vea el tamaño real del cambio antes de aplicarlo.
 */
class AppUpdater(private val context: Context) {

    private val textExtensions = setOf("html", "htm", "css", "js", "json", "txt", "svg", "md")

    /** Extrae el zip nuevo a una carpeta temporal y calcula el plan de cambios. Aún no aplica nada. */
    fun stage(appId: String, currentDir: File, newZipUri: Uri, isSystemApp: Boolean = false): Result<UpdatePlan> {
        val stagingRoot = File(context.cacheDir, "updates").apply { mkdirs() }
        val stagedDir = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }

        return try {
            context.contentResolver.openInputStream(newZipUri)?.use { extractZip(it, stagedDir) }
                ?: return Result.failure(IllegalStateException("No se pudo abrir el paquete de actualización"))

            val manifestFile = File(stagedDir, "manifest.json")
            if (!manifestFile.exists()) {
                stagedDir.deleteRecursively()
                return Result.failure(IllegalStateException("El paquete nuevo no trae manifest.json"))
            }
            val manifestJson = JSONObject(manifestFile.readText())
            val newVersion = manifestJson.optString("version", "s/v")

            if (isSystemApp || MiniApp.isOfficialSystemId(appId)) {
                val officialId = manifestJson.optString("officialSystemId", manifestJson.optString("id", ""))
                if (officialId.isNotBlank() && officialId != appId && !MiniApp.isOfficialSystemId(officialId)) {
                    stagedDir.deleteRecursively()
                    return Result.failure(IllegalStateException("El paquete de actualización no corresponde al identificador oficial del sistema"))
                }
            }

            val diffs = computeDiff(currentDir, stagedDir)
            Result.success(UpdatePlan(appId, stagedDir, newVersion, diffs))
        } catch (e: Exception) {
            stagedDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /** Aplica el plan exactamente como fue calculado: agrega, reemplaza y borra archivo por archivo. */
    fun apply(currentDir: File, plan: UpdatePlan) {
        for (diff in plan.diffs) {
            val target = File(currentDir, diff.path)
            when (diff.type) {
                FileChangeType.ADDED, FileChangeType.MODIFIED -> {
                    val source = File(plan.stagedDir, diff.path)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                FileChangeType.REMOVED -> target.delete()
                FileChangeType.UNCHANGED -> { /* no-op */ }
            }
        }
        plan.stagedDir.deleteRecursively()
    }

    fun discard(plan: UpdatePlan) {
        plan.stagedDir.deleteRecursively()
    }

    // ---- diff interno ----

    private fun computeDiff(oldDir: File, newDir: File): List<FileDiff> {
        val oldFiles = relativeFiles(oldDir)
        val newFiles = relativeFiles(newDir)
        val allPaths = (oldFiles.keys + newFiles.keys).toSortedSet()

        return allPaths.map { path ->
            val oldFile = oldFiles[path]
            val newFile = newFiles[path]
            when {
                oldFile == null && newFile != null -> FileDiff(path, FileChangeType.ADDED)
                oldFile != null && newFile == null -> FileDiff(path, FileChangeType.REMOVED)
                oldFile != null && newFile != null -> {
                    if (sha256(oldFile) == sha256(newFile)) {
                        FileDiff(path, FileChangeType.UNCHANGED)
                    } else if (path.substringAfterLast('.', "") in textExtensions) {
                        val (added, removed) = lineDiffCounts(oldFile.readText(), newFile.readText())
                        FileDiff(path, FileChangeType.MODIFIED, added, removed)
                    } else {
                        FileDiff(path, FileChangeType.MODIFIED)
                    }
                }
                else -> FileDiff(path, FileChangeType.UNCHANGED)
            }
        }
    }

    private fun relativeFiles(root: File): Map<String, File> =
        root.walkTopDown().filter { it.isFile }
            .associateBy { it.relativeTo(root).path.replace(File.separatorChar, '/') }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Diff de líneas por LCS (programación dinámica) — cuenta agregadas/eliminadas. */
    private fun lineDiffCounts(oldText: String, newText: String): Pair<Int, Int> {
        val a = oldText.lines()
        val b = newText.lines()
        val n = a.size
        val m = b.size
        // dp[i][j] = longitud de la subsecuencia común más larga entre a[i..] y b[j..]
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                           else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val lcsLength = dp[0][0]
        val removed = n - lcsLength
        val added = m - lcsLength
        return added to removed
    }

    private fun extractZip(input: java.io.InputStream, targetDir: File) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Entrada de zip inválida: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
