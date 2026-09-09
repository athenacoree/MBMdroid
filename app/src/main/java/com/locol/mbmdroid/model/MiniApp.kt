package com.locol.mbmdroid.model

enum class AppStatus { INSTALLED, SUSPENDED }

enum class AppTier { USER_APP, VERIFIED_APP, OFFICIAL_SYSTEM_APP }

/**
 * Representa una "mini app" (paquete HTML/CSS/JS) instalada dentro de MbMdroid.
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
    val isSystem: Boolean = false,
    val publisher: String = "Desconocido",
    val signature: String = "",
    val publicKey: String = "",
    val appTier: AppTier = AppTier.USER_APP
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
