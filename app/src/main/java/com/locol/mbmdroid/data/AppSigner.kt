package com.locol.mbmdroid.data

import com.locol.mbmdroid.model.AppTier
import com.locol.mbmdroid.model.MiniApp
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Gestiona la identidad, firmas digitales y verificación criptográfica de mini-apps.
 * Compatible con Android API 24+.
 */
object AppSigner {

    // Clave pública oficial fija de MbMdroid en formato X.509 Base64
    const val OFFICIAL_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBvPUlw5K0dEtLkuuig0UZNHxak1csmxwz/WjTX/CToCf98UMLLxinztlTVYEXI8sN9d2u40ezJq8sxrc73lNkQ=="

    /** Genera un par de claves ECDSA para firmas de prueba / desarrolladores en tests. */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        return kpg.generateKeyPair()
    }

    /** Convierte una clave pública en cadena Base64 limpia. */
    fun publicKeyToBase64(pair: KeyPair): String {
        return encodeBase64(pair.public.encoded)
    }

    /** Firma el contenido con la clave privada dada. */
    fun signData(data: ByteArray, privateKey: java.security.PrivateKey): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return encodeBase64(sig.sign())
    }

    /** Devuelve el payload del manifest sin la clave "signature" para verificación de firma. */
    fun cleanManifestPayload(manifestJsonText: String): ByteArray {
        return try {
            val obj = JSONObject(manifestJsonText)
            obj.remove("signature")
            obj.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            manifestJsonText.toByteArray(Charsets.UTF_8)
        }
    }

    /** Verifica la firma digital contra el contenido y la clave pública en Base64. */
    fun verifySignature(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean {
        if (signatureBase64.isBlank() || publicKeyBase64.isBlank()) return false
        return try {
            val keyBytes = decodeBase64(publicKeyBase64)
            val sigBytes = decodeBase64(signatureBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)

            val keyFactory = try {
                KeyFactory.getInstance("EC")
            } catch (_: Exception) {
                KeyFactory.getInstance("RSA")
            }

            val pubKey = keyFactory.generatePublic(keySpec)
            val sigAlg = if (pubKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"

            val sig = Signature.getInstance(sigAlg)
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Determina el [AppTier] real basándose estrictamente en la firma criptográfica.
     * NINGUNA descarga o actualización remota puede ser [AppTier.OFFICIAL_SYSTEM_APP] sin firma válida.
     */
    fun determineTier(
        id: String,
        manifestPayload: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String,
        isAssetBootstrap: Boolean = false
    ): AppTier {
        if (MiniApp.isOfficialSystemId(id)) {
            val isOfficialVerified = verifySignature(manifestPayload, signatureBase64, OFFICIAL_PUBLIC_KEY_BASE64)
            if (isOfficialVerified) {
                return AppTier.OFFICIAL_SYSTEM_APP
            }
            if (isAssetBootstrap && signatureBase64.isBlank()) {
                return AppTier.OFFICIAL_SYSTEM_APP
            }
            return AppTier.USER_APP
        }

        if (signatureBase64.isNotBlank() && publicKeyBase64.isNotBlank()) {
            val isVerified = verifySignature(manifestPayload, signatureBase64, publicKeyBase64)
            if (isVerified) {
                return AppTier.VERIFIED_APP
            }
        }

        return AppTier.USER_APP
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun decodeBase64(str: String): ByteArray {
        val clean = str.trim().replace("\n", "").replace("\r", "")
        return try {
            Base64.decode(clean, Base64.NO_WRAP)
        } catch (_: Exception) {
            java.util.Base64.getDecoder().decode(clean)
        }
    }
}
