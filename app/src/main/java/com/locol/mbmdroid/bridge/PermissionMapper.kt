package com.locol.mbmdroid.bridge

import android.Manifest
import android.os.Build

/**
 * Traduce los nombres "amigables" que una mini-app pide vía JS (ej. "camera")
 * a los permisos reales de Android. Solo se exponen permisos de tiempo de
 * ejecución (dangerous) que tienen sentido para apps web empaquetadas.
 *
 * Deliberadamente NO se incluyen SMS, registro de llamadas ni permisos de
 * accesibilidad: son de altísimo riesgo de abuso (stalkerware) y Play Store
 * los restringe fuertemente. Si en el futuro una mini-app concreta los
 * necesita de verdad, agrégalos aquí de forma explícita.
 */
object PermissionMapper {

    fun androidPermissionsFor(friendlyName: String): List<String> = when (friendlyName.lowercase()) {
        "camera" -> listOf(Manifest.permission.CAMERA)
        "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
        "location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        "contacts" -> listOf(Manifest.permission.READ_CONTACTS)
        "bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else emptyList()
        "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList()
        "storage" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        else -> emptyList()
    }

    /** Permisos declarados en el manifest de la mini-app que existen como permisos Android reales. */
    fun resolve(declared: List<String>): List<String> =
        declared.flatMap { androidPermissionsFor(it) }.distinct()
}
