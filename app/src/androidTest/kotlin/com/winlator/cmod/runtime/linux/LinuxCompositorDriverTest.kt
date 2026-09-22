package com.winlator.cmod.runtime.linux

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinuxCompositorDriverTest {
    @Test
    fun requiresInstalledTurnipInsteadOfAnyAndroidDriver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "compositor-driver-test"
        val directory = File(context.filesDir, "contents/adrenotools/$id")
        assertFalse(directory.exists())
        directory.mkdirs()
        val drivers = AdrenotoolsManager(context)
        val library = File(directory, "vulkan.adreno.so")
        val metadata = File(directory, "meta.json")
        try {
            library.writeBytes(byteArrayOf(1))
            metadata.writeText(JSONObject().put("name", "Qualcomm driver").put("libraryName", library.name).toString())
            assertFalse(drivers.isTurnipDriver(id))
            metadata.writeText(JSONObject().put("name", "WN Turnip 1.16-p").put("libraryName", library.name).toString())
            assertTrue(drivers.isTurnipDriver(id))
            library.delete()
            assertFalse(drivers.isTurnipDriver(id))
            assertFalse(drivers.isTurnipDriver("26.2.2"))
            assertFalse(drivers.isTurnipDriver("System"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
