package com.locol.mbmdroid.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lista las apps REALES instaladas en el teléfono (las que tienen ícono de
 * lanzador — igual que cualquier launcher de Android) y permite abrirlas desde
 * MbMdroid. Requiere QUERY_ALL_PACKAGES (declarado en el manifest) para verlas
 * todas en Android 11+.
 */
class PhoneAppsManager(private val context: Context) {

    fun listAppsJson(): JSONArray {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val arr = JSONArray()
        val seen = mutableSetOf<String>()
        resolved.forEach { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) return@forEach
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val apkFile = File(appInfo.sourceDir)
                arr.put(JSONObject().apply {
                    put("packageName", pkg)
                    put("label", label)
                    put("sizeBytes", if (apkFile.exists()) apkFile.length() else -1L)
                    put("iconDataUrl", drawableToDataUrl(pm.getApplicationIcon(appInfo)) ?: "")
                    put("isSystemApp", (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                })
            } catch (e: Exception) {
                // La app se desinstaló justo en este instante, o no se pudo leer: se ignora.
            }
        }
        return arr
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun drawableToDataUrl(drawable: Drawable): String? {
        return try {
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
