package com.locol.mbmdroid

import com.locol.mbmdroid.model.MiniApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun testOfficialSystemIdCheck() {
        assertTrue(MiniApp.isOfficialSystemId("system.settings"))
        assertTrue(MiniApp.isOfficialSystemId("system.store"))
        assertTrue(MiniApp.isOfficialSystemId("system.storage"))
        assertTrue(MiniApp.isOfficialSystemId("com.mbmdroid.system.custom"))
        assertFalse(MiniApp.isOfficialSystemId("user.app.123"))
    }

    @Test
    fun testMiniAppModelProperties() {
        val app = MiniApp(
            id = "system.settings",
            name = "Ajustes",
            iconPath = null,
            entryPoint = "index.html",
            version = "1.0",
            status = com.locol.mbmdroid.model.AppStatus.INSTALLED,
            installedAt = 1000L,
            isSystem = true
        )
        assertTrue(app.isOfficialSystemId())
        assertTrue(app.isSystem)
        assertEquals("system.settings", app.id)
    }
}
