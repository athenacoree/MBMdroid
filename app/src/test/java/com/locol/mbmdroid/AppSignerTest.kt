package com.locol.mbmdroid

import com.locol.mbmdroid.data.AppSigner
import com.locol.mbmdroid.model.AppTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSignerTest {

    @Test
    fun testGenerateKeyPairAndSignVerify() {
        val keyPair = AppSigner.generateKeyPair()
        val pubKeyBase64 = AppSigner.publicKeyToBase64(keyPair)
        val sampleData = "manifest content test payload".toByteArray()

        val signature = AppSigner.signData(sampleData, keyPair.private)
        assertTrue(signature.isNotBlank())

        val isValid = AppSigner.verifySignature(sampleData, signature, pubKeyBase64)
        assertTrue(isValid)

        val tamperedData = "manifest content modified payload".toByteArray()
        val isTamperedValid = AppSigner.verifySignature(tamperedData, signature, pubKeyBase64)
        assertFalse(isTamperedValid)
    }

    @Test
    fun testAppTierDetermination() {
        val keyPair = AppSigner.generateKeyPair()
        val pubKeyBase64 = AppSigner.publicKeyToBase64(keyPair)
        val payload = "manifest payload".toByteArray()
        val signature = AppSigner.signData(payload, keyPair.private)

        val verifiedTier = AppSigner.determineTier(
            id = "user.custom.app",
            manifestPayload = payload,
            signatureBase64 = signature,
            publicKeyBase64 = pubKeyBase64
        )
        assertEquals(AppTier.VERIFIED_APP, verifiedTier)

        val unverifiedTier = AppSigner.determineTier(
            id = "user.custom.app",
            manifestPayload = payload,
            signatureBase64 = "invalid_sig",
            publicKeyBase64 = pubKeyBase64
        )
        assertEquals(AppTier.USER_APP, unverifiedTier)
    }

    @Test
    fun testImpersonationAttemptSystemSettings() {
        val thirdPartyPair = AppSigner.generateKeyPair()
        val thirdPartyPubKey = AppSigner.publicKeyToBase64(thirdPartyPair)
        val payload = "fake settings manifest".toByteArray()
        val signature = AppSigner.signData(payload, thirdPartyPair.private)

        val tier = AppSigner.determineTier(
            id = "system.settings",
            manifestPayload = payload,
            signatureBase64 = signature,
            publicKeyBase64 = thirdPartyPubKey
        )

        assertEquals(AppTier.USER_APP, tier)
        assertFalse(tier == AppTier.OFFICIAL_SYSTEM_APP)
    }

    @Test
    fun testUnsignedUpdateRejection() {
        val payload = "unsigned update manifest".toByteArray()
        val tier = AppSigner.determineTier(
            id = "system.settings",
            manifestPayload = payload,
            signatureBase64 = "",
            publicKeyBase64 = ""
        )

        assertEquals(AppTier.USER_APP, tier)
        assertFalse(tier == AppTier.OFFICIAL_SYSTEM_APP)
    }
}
