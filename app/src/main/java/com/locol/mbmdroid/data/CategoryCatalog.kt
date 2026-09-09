package com.locol.mbmdroid.data

/**
 * Categorías que una mini-app puede declarar en su manifest.json ("category").
 * Sirven para dos cosas:
 * 1) Agrupar/filtrar en el launcher.
 * 2) Decidir qué mini-app se lanza cuando el USUARIO ABRE UN ARCHIVO DESDE
 *    CUALQUIER PARTE DEL TELÉFONO (otra app, gestor de archivos, etc):
 *    ChooserActivity intercepta esos Intent.ACTION_VIEW por su mimeType real
 *    de Android y los traduce a una de estas categorías.
 */
object CategoryCatalog {

    data class Category(val id: String, val label: String, val mimePatterns: List<String>)

    val ALL = listOf(
        Category("music_player", "Reproductor de música", listOf("audio/*")),
        Category("video_player", "Reproductor de video", listOf("video/*")),
        Category("image_viewer", "Visor de imágenes", listOf("image/*")),
        Category("document_viewer", "Visor de documentos", listOf("application/pdf", "text/plain")),
        Category("clock", "Reloj / alarmas", emptyList()),
        Category("game", "Juego", emptyList()),
        Category("utility", "Utilidad", emptyList()),
        Category("social", "Social", emptyList()),
        Category("system", "Sistema", emptyList()),
        Category("other", "Otros", emptyList())
    )

    fun label(id: String): String = ALL.find { it.id == id }?.label ?: "Otros"

    /** Dado un mimeType real de Android (ej. "video/mp4"), busca a qué categoría pertenece. */
    fun categoryForMimeType(mimeType: String): String? {
        val type = mimeType.substringBefore('/')
        return ALL.firstOrNull { cat ->
            cat.mimePatterns.any { pattern ->
                pattern == mimeType || pattern == "$type/*"
            }
        }?.id
    }

    /** Categorías que tiene sentido ofrecer como "predeterminada" (las que abren archivos reales). */
    fun openableCategories(): List<Category> = ALL.filter { it.mimePatterns.isNotEmpty() }
}
