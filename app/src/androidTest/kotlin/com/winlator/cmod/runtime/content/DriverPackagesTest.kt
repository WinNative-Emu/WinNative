package com.winlator.cmod.runtime.content

import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.linux.LinuxRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DriverPackagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = File(context.filesDir, "driver-tests")
    private val installed = mutableListOf<DriverPackages.Installed>()

    private fun install(file: File, required: DriverPackages.Platform? = null): DriverPackages.Installed =
        DriverPackages.install(context, Uri.fromFile(file), file.name, required).also { installed += it }

    @After
    fun clean() {
        installed.forEach {
            if (it.platform == DriverPackages.Platform.LINUX) DriverPackages.removeLinux(context, it.id)
            else File(context.filesDir, "contents/adrenotools/${it.id}").deleteRecursively()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("linux_driver_selected").commit()
    }

    @Test
    fun realPackagesInstallIntoSeparateStoresAndLinuxSelectionControlsRuntime() {
        val android = install(File(fixtures, "android.zip"))
        val balanced = install(File(fixtures, "linux-b.zip"))
        val performance = install(File(fixtures, "linux-p.zip"))
        assertEquals(DriverPackages.Platform.ANDROID, android.platform)
        assertEquals(DriverPackages.Platform.LINUX, balanced.platform)
        assertEquals(DriverPackages.Platform.LINUX, performance.platform)
        assertTrue(AdrenotoolsManager(context).enumarateInstalledDrivers().contains(android.id))
        assertFalse(AdrenotoolsManager(context).enumarateInstalledDrivers().contains(balanced.id))
        assertEquals(performance.id, DriverPackages.selectedLinux(context))
        DriverPackages.selectLinux(context, balanced.id)
        assertEquals(balanced.id, LinuxRuntime.driverName(context))
        val icd = LinuxRuntime.vulkanIcd(context)!!
        val library = JSONObject(icd.readText()).getJSONObject("ICD").getString("library_path")
        assertTrue(File(library).isFile)
        assertTrue(library.startsWith(DriverPackages.linuxDirectory(context).path))
        assertTrue(runCatching { install(File(fixtures, "linux-b.zip")) }.isFailure)
        DriverPackages.removeLinux(context, balanced.id)
        assertEquals(DriverPackages.BUNDLED, DriverPackages.selectedLinux(context))
        assertEquals(LinuxRuntime.TURNIP_VERSION, LinuxRuntime.driverName(context))
        assertTrue(File(context.filesDir, "contents/adrenotools/${android.id}/meta.json").isFile)
    }

    @Test
    fun androidOnlyEntryPointRejectsLinuxAndWrongAbiMetadata() {
        assertEquals("", AdrenotoolsManager(context).installDriver(Uri.fromFile(File(fixtures, "linux-b.zip"))))
        assertTrue(runCatching { install(File(fixtures, "android.zip"), DriverPackages.Platform.LINUX) }.isFailure)
        val invalid = rewrite("linux-b.zip") { name, bytes ->
            if (name == "meta.json") JSONObject(String(bytes)).put("platform", "android").toString().toByteArray() else bytes
        }
        assertTrue(runCatching { install(invalid) }.isFailure)
    }

    @Test
    fun unsafePathsAndInvalidElfNeverInstallOrEscapeStaging() {
        val invalid = rewrite("linux-b.zip") { name, bytes -> if (name.endsWith(".so")) byteArrayOf(1, 2, 3) else bytes }
        assertTrue(runCatching { install(invalid) }.isFailure)
        val archive = File(context.cacheDir, "traversal.zip")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("../escaped-driver"))
            it.write(byteArrayOf(1))
            it.closeEntry()
        }
        assertTrue(runCatching { install(archive) }.isFailure)
        assertFalse(File(context.cacheDir, "escaped-driver").exists())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("driver-") })
    }

    private fun rewrite(source: String, change: (String, ByteArray) -> ByteArray): File {
        val output = File(context.cacheDir, "invalid-driver.zip")
        ZipFile(File(fixtures, source)).use { input ->
            ZipOutputStream(output.outputStream()).use { zip ->
                input.entries().asSequence().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(change(entry.name, input.getInputStream(entry).use { it.readBytes() }))
                    zip.closeEntry()
                }
            }
        }
        return output
    }
}
