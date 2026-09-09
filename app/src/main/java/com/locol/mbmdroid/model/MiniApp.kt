package com.locol.mbmdroid.model

enum class AppStatus { INSTALLED, SUSPENDED }

/**
 * Representa una "mini app" (paquete HTML/CSS/JS) instalada dentro de MbMdroid.
 *
 * @param id identificador único (carpeta física en filesDir/apps/<id>)
 * @param name nombre visible
 * @param iconPath ruta relativa dentro de la carpeta de la app hacia su ícono (puede ser null)
 * @param entryPoint archivo HTML de entrada (ej. "index.html")
 * @param version versión declarada por el manifest.json de la app
 * @param status INSTALLED o SUSPENDED (no aparece en el grid principal si está suspendida)
 * @param installedAt timestamp de instalación
 * @param permissions permisos de Android que esta mini-app puede solicitar en tiempo de
 *        ejecución vía el puente JS (ej. "camera", "microphone", "location", "storage")
 * @param background si true, la mini-app puede pedir seguir corriendo en segundo plano
 *        (ej. un reproductor de música) mediante AndroidBridge.enableBackground()
 * @param category categoría declarada (ver [com.locol.mbmdroid.data.CategoryCatalog]),
 *        usada para agrupar en el launcher y para el sistema de apps predeterminadas
 * @param isSystem true para las apps de sistema (Ajustes, Tienda): el usuario no puede
 *        desinstalarlas ni reemplazarlas por una app externa; solo se actualizan con
 *        un paquete nuevo del propio creador
 */
data class MiniApp(
    val id: String,
    val name: String,
    val iconPath: String?,
    val entryPoint: String,
    val version: String,
    val status: AppStatus,
    val installedAt: Long,
    val permissions: List<String> = emptyList(),
    val background: Boolean = false,
    val category: String = "other",
    val isSystem: Boolean = false
) {
    /**
     * Verifica si el ID corresponde a un identificador oficial de app de sistema.
     */
    fun isOfficialSystemId(): Boolean = isOfficialSystemId(id)

    companion object {
        const val SYSTEM_ID_PREFIX = "system."
        const val OFFICIAL_SYSTEM_NAMESPACE = "com.mbmdroid.system."

        fun isOfficialSystemId(id: String): Boolean {
            return id.startsWith(SYSTEM_ID_PREFIX) || id.startsWith(OFFICIAL_SYSTEM_NAMESPACE)
        }
    }
}
