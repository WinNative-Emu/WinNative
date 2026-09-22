package com.winlator.cmod.runtime.linux

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.content.DriverPackages
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.EnvVars
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emulatesIdentityWithoutPretendingToBeRoot() {
        val command = LinuxRuntime.command(context, ImageFs.find(context), context.cacheDir,
            null, null, emptyList(), listOf("/usr/bin/env", "-i", "HOME=/root", "/usr/bin/id"))
        val flag = command.indexOf("-i")
        assertTrue(flag > 0 && flag < command.indexOf("-r"))
        assertEquals("${Process.myUid()}:${Process.myUid()}", command[flag + 1])
        assertEquals(listOf("/usr/bin/env", "-i", "HOME=/root", "/usr/bin/id"), command.takeLast(4))
    }

    @Test
    fun seccompFallbackIsHostOnlyAndOptIn() {
        val guest = EnvVars()
        for (disabled in listOf("", "0", "false", "off")) {
            guest.put("PROOT_NO_SECCOMP", disabled)
            assertFalse(LinuxRuntime.hostEnvironment(context, guest).has("PROOT_NO_SECCOMP"))
        }
        for (enabled in listOf("1", "true", "on")) {
            guest.put("PROOT_NO_SECCOMP", enabled)
            val host = LinuxRuntime.hostEnvironment(context, guest)
            assertEquals("1", host.get("PROOT_NO_SECCOMP"))
            assertEquals(LinuxRuntime.prootLoader(context).path, host.get("PROOT_LOADER"))
        }
        guest.put("PROTON_USE_XALIA", "0")
        assertFalse(LinuxRuntime.hostEnvironment(context, guest).has("PROTON_USE_XALIA"))
    }

    @Test
    fun missingImportedLibraryFallsBackWithoutChangingSavedChoice() {
        val id = "linux-runtime-test-driver"
        val directory = File(DriverPackages.linuxDirectory(context), id)
        assertFalse(directory.exists())
        directory.mkdirs()
        val library = File(directory, "test.so")
        val manifest = File(directory, LinuxRuntime.DRIVER_ICD)
        try {
            File(directory, "meta.json").writeText(JSONObject().put("name", id).toString())
            library.writeBytes(byteArrayOf(1))
            manifest.writeText(JSONObject().put("ICD", JSONObject().put("library_path", library.path)).toString())
            assertEquals(manifest, DriverPackages.selectedLinuxIcd(context, id))
            library.delete()
            assertNull(DriverPackages.selectedLinuxIcd(context, id))
            assertTrue(manifest.isFile)
            library.writeBytes(byteArrayOf(1))
            manifest.writeText(JSONObject().put("ICD", JSONObject().put("library_path", library.name)).toString())
            assertEquals(manifest, DriverPackages.selectedLinuxIcd(context, id))
            manifest.writeText("invalid json")
            assertNull(DriverPackages.selectedLinuxIcd(context, id))
            assertNull(DriverPackages.selectedLinuxIcd(context, "removed-driver"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
