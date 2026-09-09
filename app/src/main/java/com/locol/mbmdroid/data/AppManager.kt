package com.locol.mbmdroid.data

import android.content.Context
import android.net.Uri
import com.locol.mbmdroid.model.AppStatus
import com.locol.mbmdroid.model.MiniApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Se lanza cuando un .zip a instalar supera el límite de tamaño permitido (anti zip-bomb / anti llenar el disco). */
class ZipTooLargeException(message: String) : Exception(message)

/** Metadatos de una app enviada a la papelera: sus archivos siguen en disco por [AppManager.TRASH_RETENTION_MS]. */
data class TrashedApp(
    val id: String,
    val name: String,
    val category: String,
    val version: String,
    val iconPath: String?,
    val entryPoint: String,
    val permissions: List<String>,
    val background: Boolean,
    val deletedAt: Long
)

/** Resultado de intentar instalar un paquete: éxito, error explicado, o nombre duplicado a resolver. */
sealed class InstallOutcome {
    data class Success(val app: MiniApp) : InstallOutcome()
    data class Failure(val message: String) : InstallOutcome()
    data class DuplicateName(val existing: MiniApp, val zipUri: Uri) : InstallOutcome()
}

/** Cómo resolver un nombre duplicado al instalar. */
enum class DuplicateResolution { ASK, REPLACE, KEEP_BOTH }

/**
 * Gestiona el ciclo de vida de las mini-apps: instalar (desde un .zip), desinstalar
 * (a una papelera recuperable), suspender/reanudar, categorizar, listar, y hacer
 * copia de seguridad / restauración de todo lo instalado.
 *
 * Cada mini-app vive en filesDir/apps/<id>/ y trae en su raíz un manifest.json:
 * { "name": "Mi App", "entry": "index.html", "icon": "icon.png", "version": "1.0",
 *   "permissions": ["camera"], "background": false, "category": "video_player" }
 *
 * El registro global vive en filesDir/registry.json (sin Room, para mantener el
 * proyecto liviano). La papelera vive en filesDir/trash/<id>/ + trash_registry.json.
 *
 * Apps de sistema (Ajustes, Tienda, Almacenamiento): se instalan una sola vez desde
 * assets/system_apps/ al primer arranque, con isSystem=true. El usuario NO puede
 * desinstalarlas (uninstall() las rechaza); solo se reemplazan vía AppUpdater.
 */
class AppManager(private val context: Context) {

    companion object {
        /** Límite de tamaño SIN COMPRIMIR por mini-app, para no dejar que un .zip llene el almacenamiento. */
        const val MAX_INSTALL_BYTES = 300L * 1024 * 1024 // 300 MB
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 días
    }

    private val appsRoot: File
        get() = File(context.filesDir, "apps").apply { mkdirs() }

    private val trashRoot: File
        get() = File(context.filesDir, "trash").apply { mkdirs() }

    private val registryFile: File
        get() = File(context.filesDir, "registry.json")

    private val trashRegistryFile: File
        get() = File(context.filesDir, "trash_registry.json")

    fun appDir(id: String): File = File(appsRoot, id)

    /** Devuelve el File del ícono de la app, o null si no declaró uno o no existe. */
    fun iconFile(app: MiniApp): File? {
        val path = app.iconPath ?: return null
        val file = File(appDir(app.id), path)
        return if (file.exists()) file else null
    }

    // ---------------------------------------------------------------------
    // Instalación
    // ---------------------------------------------------------------------

    /**
     * Instala una mini-app de terceros a partir de un .zip elegido por el usuario.
     * Valida el manifest con mensajes de error claros, respeta el límite de tamaño,
     * y si ya existe una app con el mismo nombre, devuelve [InstallOutcome.DuplicateName]
     * para que la UI decida (a menos que ya se pida [resolution] explícito).
     */
    fun install(zipUri: Uri, resolution: DuplicateResolution = DuplicateResolution.ASK): InstallOutcome {
        val id = UUID.randomUUID().toString()
        val targetDir = appDir(id).apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                extractZip(input, targetDir)
            } ?: run {
                targetDir.deleteRecursively()
                return InstallOutcome.Failure("No se pudo abrir el archivo")
            }

            val validationError = validateManifest(targetDir)
            if (validationError != null) {
                targetDir.deleteRecursively()
                return InstallOutcome.Failure(validationError)
            }

            var app = readManifestAsApp(id, targetDir, isSystem = false)
                ?: run {
                    targetDir.deleteRecursively()
                    return InstallOutcome.Failure("No se pudo leer el paquete instalado")
                }

            val isSystemConflict = loadRegistry().any { it.isSystem && it.name.equals(app.name, ignoreCase = true) }
            if (isSystemConflict) {
                targetDir.deleteRecursively()
                return InstallOutcome.Failure("El nombre de la app está reservado para una app del sistema protegida")
            }

            val existing = loadRegistry().find { !it.isSystem && it.name.equals(app.name, ignoreCase = true) }
            if (existing != null) {
                when (resolution) {
                    DuplicateResolution.ASK -> {
                        targetDir.deleteRecursively()
                        return InstallOutcome.DuplicateName(existing, zipUri)
                    }
                    DuplicateResolution.REPLACE -> {
                        appDir(existing.id).deleteRecursively()
                        writeRegistry(loadRegistry().filterNot { it.id == existing.id })
                    }
                    DuplicateResolution.KEEP_BOTH -> {
                        app = app.copy(name = "${app.name} (2)")
                    }
                }
            }

            saveToRegistry(app)
            return InstallOutcome.Success(app)
        } catch (e: ZipTooLargeException) {
            targetDir.deleteRecursively()
            return InstallOutcome.Failure(e.message ?: "El paquete es demasiado grande")
        } catch (e: SecurityException) {
            targetDir.deleteRecursively()
            return InstallOutcome.Failure(e.message ?: "Paquete inválido o inseguro")
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            return InstallOutcome.Failure(e.message ?: "Error desconocido al instalar")
        }
    }

    /** Compatibilidad simple para llamadores (descargas en 2do plano) que no manejan diálogos: nunca preguntan, renombran si hace falta. */
    fun installAutoResolving(zipUri: Uri): Result<MiniApp> =
        when (val outcome = install(zipUri, DuplicateResolution.KEEP_BOTH)) {
            is InstallOutcome.Success -> Result.success(outcome.app)
            is InstallOutcome.Failure -> Result.failure(IllegalStateException(outcome.message))
            is InstallOutcome.DuplicateName -> Result.failure(IllegalStateException("Ya existe una app con ese nombre"))
        }

    /**
     * Copia una app de sistema empaquetada en assets/system_apps/<assetSubdir>/ hacia
     * almacenamiento interno, con un id FIJO. Si ya está instalada, no hace nada.
     */
    fun installSystemAppFromAssets(assetSubdir: String, fixedId: String): Result<MiniApp> {
        if (!MiniApp.isOfficialSystemId(fixedId)) {
            return Result.failure(IllegalArgumentException("El ID $fixedId no es un identificador oficial de app del sistema"))
        }
        if (getById(fixedId) != null) return Result.success(getById(fixedId)!!)

        val targetDir = appDir(fixedId).apply { mkdirs() }
        return try {
            copyAssetDir("system_apps/$assetSubdir", targetDir)
            val app = readManifestAsApp(fixedId, targetDir, isSystem = true)
                ?: return Result.failure(IllegalStateException("system_apps/$assetSubdir sin manifest.json válido"))
            saveToRegistry(app)
            Result.success(app)
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------------
    // Desinstalación con papelera (deshacer)
    // ---------------------------------------------------------------------

    /** Envía la app a la papelera (no borra sus archivos todavía): se puede restaurar hasta 7 días. */
    fun uninstall(id: String): Result<Unit> {
        val app = getById(id) ?: return Result.failure(IllegalStateException("No existe"))
        if (app.isSystem || app.isOfficialSystemId()) return Result.failure(IllegalStateException("Las apps de sistema no se pueden desinstalar"))

        val srcDir = appDir(id)
        val trashDir = File(trashRoot, id)
        if (trashDir.exists()) trashDir.deleteRecursively()
        if (srcDir.exists()) {
            srcDir.copyRecursively(trashDir, overwrite = true)
            srcDir.deleteRecursively()
        }

        val trashed = TrashedApp(
            id = id, name = app.name, category = app.category, version = app.version,
            iconPath = app.iconPath, entryPoint = app.entryPoint, permissions = app.permissions,
            background = app.background, deletedAt = System.currentTimeMillis()
        )
        val trashList = loadTrash().toMutableList()
        trashList.removeAll { it.id == id }
        trashList.add(trashed)
        saveTrash(trashList)

        writeRegistry(loadRegistry().filterNot { it.id == id })
        return Result.success(Unit)
    }

    fun listTrash(): List<TrashedApp> {
        purgeExpiredTrash()
        return loadTrash().sortedByDescending { it.deletedAt }
    }

    /** Trae de vuelta una app borrada, con todos sus archivos tal como estaban. */
    fun restoreFromTrash(id: String): Result<MiniApp> {
        val trashed = loadTrash().find { it.id == id } ?: return Result.failure(IllegalStateException("Ya no está en la papelera"))
        val trashDir = File(trashRoot, id)
        if (!trashDir.exists()) {
            saveTrash(loadTrash().filterNot { it.id == id })
            return Result.failure(IllegalStateException("Sus archivos ya no existen"))
        }
        val destDir = appDir(id)
        if (destDir.exists()) destDir.deleteRecursively()
        trashDir.copyRecursively(destDir, overwrite = true)
        trashDir.deleteRecursively()

        val app = readManifestAsApp(id, destDir, isSystem = false)
            ?: return Result.failure(IllegalStateException("No se pudo restaurar: el manifest ya no es válido"))
        saveToRegistry(app)
        saveTrash(loadTrash().filterNot { it.id == id })
        return Result.success(app)
    }

    /** Borra definitivamente algo de la papelera antes de que expire solo. */
    fun deleteFromTrashForever(id: String) {
        File(trashRoot, id).deleteRecursively()
        saveTrash(loadTrash().filterNot { it.id == id })
    }

    fun purgeExpiredTrash() {
        val now = System.currentTimeMillis()
        val expired = loadTrash().filter { now - it.deletedAt > TRASH_RETENTION_MS }
        if (expired.isEmpty()) return
        expired.forEach { File(trashRoot, it.id).deleteRecursively() }
        saveTrash(loadTrash().filterNot { t -> expired.any { it.id == t.id } })
    }

    // ---------------------------------------------------------------------
    // Copia de seguridad / restauración
    // ---------------------------------------------------------------------

    /** Empaqueta TODAS las mini-apps de usuario (no las de sistema) + el registro en un único .zip. */
    fun exportBackup(destUri: Uri): Result<Int> {
        return try {
            var count = 0
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                ZipOutputStream(out).use { zos ->
                    zos.putNextEntry(ZipEntry("registry.json"))
                    zos.write(if (registryFile.exists()) registryFile.readBytes() else "[]".toByteArray())
                    zos.closeEntry()

                    loadRegistry().filterNot { it.isSystem }.forEach { app ->
                        val dir = appDir(app.id)
                        if (dir.exists()) {
                            count++
                            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relPath = "apps/${app.id}/${file.relativeTo(dir).path}"
                                zos.putNextEntry(ZipEntry(relPath))
                                file.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                }
            } ?: return Result.failure(IllegalStateException("No se pudo crear el archivo de respaldo"))
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Restaura mini-apps de usuario desde un .zip generado por [exportBackup]. No toca apps de sistema. */
    fun importBackup(srcUri: Uri): Result<Int> {
        val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
        return try {
            tempDir.mkdirs()
            context.contentResolver.openInputStream(srcUri)?.use { input -> extractZip(input, tempDir, enforceLimit = false) }
                ?: return Result.failure(IllegalStateException("No se pudo abrir el archivo de respaldo"))

            val registryBackupFile = File(tempDir, "registry.json")
            if (!registryBackupFile.exists()) {
                return Result.failure(IllegalStateException("Ese archivo no parece una copia de seguridad de MbMdroid"))
            }
            val backedUpApps = parseRegistryJson(registryBackupFile.readText()).filterNot { it.isSystem }
            val appsBackupRoot = File(tempDir, "apps")

            var restored = 0
            val current = loadRegistry().toMutableList()
            backedUpApps.forEach { backedApp ->
                val srcAppDir = File(appsBackupRoot, backedApp.id)
                if (srcAppDir.exists()) {
                    val destDir = appDir(backedApp.id)
                    if (destDir.exists()) destDir.deleteRecursively()
                    srcAppDir.copyRecursively(destDir, overwrite = true)
                    current.removeAll { it.id == backedApp.id }
                    current.add(backedApp)
                    restored++
                }
            }
            writeRegistry(current)
            Result.success(restored)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // Estado / listados
    // ---------------------------------------------------------------------

    fun setStatus(id: String, status: AppStatus) {
        writeRegistry(loadRegistry().map { if (it.id == id) it.copy(status = status) else it })
    }

    fun setCategory(id: String, category: String) {
        writeRegistry(loadRegistry().map { if (it.id == id) it.copy(category = category) else it })
    }

    /** Se llama tras aplicar una actualización con AppUpdater: refresca versión/permisos/etc desde el nuevo manifest. */
    fun refreshFromManifest(id: String) {
        val dir = appDir(id)
        val current = getById(id) ?: return
        val updated = readManifestAsApp(id, dir, isSystem = current.isSystem, preserveInstalledAt = current.installedAt)
            ?: return
        writeRegistry(loadRegistry().map { if (it.id == id) updated.copy(status = current.status) else it })
    }

    fun listInstalled(): List<MiniApp> = loadRegistry().filter { it.status == AppStatus.INSTALLED }
    fun listSuspended(): List<MiniApp> = loadRegistry().filter { it.status == AppStatus.SUSPENDED }
    fun listByCategory(category: String): List<MiniApp> = listInstalled().filter { it.category == category }
    fun allApps(): List<MiniApp> = loadRegistry()
    fun getById(id: String): MiniApp? = loadRegistry().find { it.id == id }

    // ---------------------------------------------------------------------
    // Validación y lectura de manifest
    // ---------------------------------------------------------------------

    /** Devuelve un mensaje de error claro si el paquete no es instalable, o null si todo está bien. */
    private fun validateManifest(dir: File): String? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return "El paquete no trae manifest.json en su raíz"
        val manifest = try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            return "manifest.json no es un JSON válido (${e.message ?: "error de formato"})"
        }
        val name = manifest.optString("name", "").trim()
        if (name.isEmpty()) return "manifest.json no tiene el campo \"name\""

        val entry = manifest.optString("entry", "index.html")
        if (entry.isBlank()) return "manifest.json tiene un \"entry\" vacío"
        if (!File(dir, entry).exists()) return "El archivo de entrada \"$entry\" declarado en manifest.json no existe dentro del paquete"

        val iconPath = manifest.optString("icon", "")
        if (iconPath.isNotBlank() && !File(dir, iconPath).exists()) {
            return "El ícono \"$iconPath\" declarado en manifest.json no existe dentro del paquete"
        }
        return null
    }

    private fun readManifestAsApp(
        id: String,
        dir: File,
        isSystem: Boolean,
        preserveInstalledAt: Long? = null
    ): MiniApp? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        val manifest = JSONObject(manifestFile.readText())
        val entry = manifest.optString("entry", "index.html")
        if (!File(dir, entry).exists()) return null

        val permissionsArr = manifest.optJSONArray("permissions")
        val permissions = permissionsArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

        val isOfficial = MiniApp.isOfficialSystemId(id)
        val computedIsSystem = isSystem && isOfficial

        return MiniApp(
            id = id,
            name = manifest.optString("name", "App sin nombre"),
            iconPath = manifest.optString("icon", null).takeIf { it.isNotBlank() },
            entryPoint = entry,
            version = manifest.optString("version", "1.0"),
            status = AppStatus.INSTALLED,
            installedAt = preserveInstalledAt ?: System.currentTimeMillis(),
            permissions = permissions,
            background = manifest.optBoolean("background", false),
            category = if (computedIsSystem) "system" else manifest.optString("category", "other").ifBlank { "other" },
            isSystem = computedIsSystem
        )
    }

    // ---------------------------------------------------------------------
    // Persistencia interna (registro)
    // ---------------------------------------------------------------------

    private fun saveToRegistry(app: MiniApp) {
        val list = loadRegistry().toMutableList()
        list.removeAll { it.id == app.id }
        list.add(app)
        writeRegistry(list)
    }

    private fun loadRegistry(): List<MiniApp> {
        if (!registryFile.exists()) return emptyList()
        return parseRegistryJson(registryFile.readText())
    }

    private fun parseRegistryJson(json: String): List<MiniApp> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val permsArr = o.optJSONArray("permissions")
            val perms = permsArr?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList()
            MiniApp(
                id = o.getString("id"),
                name = o.getString("name"),
                iconPath = o.optString("iconPath", null).takeIf { it.isNotBlank() },
                entryPoint = o.getString("entryPoint"),
                version = o.getString("version"),
                status = AppStatus.valueOf(o.getString("status")),
                installedAt = o.getLong("installedAt"),
                permissions = perms,
                background = o.optBoolean("background", false),
                category = o.optString("category", "other").ifBlank { "other" },
                isSystem = o.optBoolean("isSystem", false)
            )
        }
    }

    private fun writeRegistry(list: List<MiniApp>) {
        val arr = JSONArray()
        list.forEach { app ->
            arr.put(JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("iconPath", app.iconPath ?: "")
                put("entryPoint", app.entryPoint)
                put("version", app.version)
                put("status", app.status.name)
                put("installedAt", app.installedAt)
                put("permissions", JSONArray(app.permissions))
                put("background", app.background)
                put("category", app.category)
                put("isSystem", app.isSystem)
            })
        }
        registryFile.writeText(arr.toString())
    }

    // ---------------------------------------------------------------------
    // Persistencia interna (papelera)
    // ---------------------------------------------------------------------

    private fun loadTrash(): List<TrashedApp> {
        if (!trashRegistryFile.exists()) return emptyList()
        val arr = JSONArray(trashRegistryFile.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val permsArr = o.optJSONArray("permissions")
            val perms = permsArr?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList()
            TrashedApp(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category", "other"),
                version = o.getString("version"),
                iconPath = o.optString("iconPath", null).takeIf { it.isNotBlank() },
                entryPoint = o.getString("entryPoint"),
                permissions = perms,
                background = o.optBoolean("background", false),
                deletedAt = o.getLong("deletedAt")
            )
        }
    }

    private fun saveTrash(list: List<TrashedApp>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("category", t.category)
                put("version", t.version)
                put("iconPath", t.iconPath ?: "")
                put("entryPoint", t.entryPoint)
                put("permissions", JSONArray(t.permissions))
                put("background", t.background)
                put("deletedAt", t.deletedAt)
            })
        }
        trashRegistryFile.writeText(arr.toString())
    }

    // ---------------------------------------------------------------------
    // Extracción de zip (con límite de tamaño y protección path-traversal)
    // ---------------------------------------------------------------------

    private fun extractZip(input: InputStream, targetDir: File, enforceLimit: Boolean = true) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            var totalBytes = 0L
            val buffer = ByteArray(8 * 1024)
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Entrada de zip inválida: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                            if (enforceLimit && totalBytes > MAX_INSTALL_BYTES) {
                                throw ZipTooLargeException(
                                    "El paquete supera el límite de ${MAX_INSTALL_BYTES / (1024 * 1024)} MB permitido por mini-app"
                                )
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val am = context.assets
        targetDir.mkdirs()
        val entries = am.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = am.list(childAssetPath) ?: emptyArray()
            if (childEntries.isEmpty()) {
                am.open(childAssetPath).use { input ->
                    File(targetDir, entry).outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetDir(childAssetPath, File(targetDir, entry))
            }
        }
    }
}
