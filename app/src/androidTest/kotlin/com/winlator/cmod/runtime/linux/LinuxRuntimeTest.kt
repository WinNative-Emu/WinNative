package com.winlator.cmod.runtime.linux

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.shared.ui.widget.EnvVarsView
import com.winlator.cmod.runtime.content.DriverPackages
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.EnvVars
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The options are put to the bundled proot rather than to a list of the ones it is believed to
     * take: an option it does not know is fatal at startup, and a list kept by hand drifts from the
     * binary that is actually shipped. The guest command is replaced so nothing starts a session.
     */
    @Test
    fun passesOnlyOptionsThisProotAccepts() {
        val guest = listOf("/usr/bin/env", "-i", "HOME=/root", "/usr/bin/id")
        val command = LinuxRuntime.command(context, ImageFs.find(context), context.cacheDir,
            null, null, emptyList(), guest)
        assertEquals(guest, command.takeLast(guest.size))
        val asked = command.dropLast(guest.size) + "/bin/true"
        val proot = ProcessBuilder(asked)
            .redirectErrorStream(true)
            .also { it.environment()["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path }
            .also { it.environment()["PROOT_TMP_DIR"] = context.cacheDir.path }
            .start()
        val said = try {
            proot.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (!proot.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proot.destroyForcibly()
        }
        assertFalse(said, said.contains("unknown option"))
        assertFalse(said, said.contains("missing value for option"))
        assertFalse(said, said.contains("expects no value"))
    }

    @Test
    fun aLinuxSessionReadsTheContainerWithoutWhatOnlyWineAndAndroidRead() {
        val kept = EnvVars(EnvVarsView.forGamescope(Container.DEFAULT_ENV_VARS))
        assertFalse(kept.has("WINE_FAST_YIELD"))
        assertFalse(kept.has("WRAPPER_MAX_IMAGE_COUNT"))
        assertEquals("lazy", kept.get("ZINK_DESCRIPTORS"))
        // Measured on device: sysmem is not a frame cost here, so the driver's flags are left alone.
        assertEquals("noconform,sysmem", kept.get("TU_DEBUG"))
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
