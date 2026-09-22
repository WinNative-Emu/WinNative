package com.winlator.cmod.runtime.system

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.linux.LinuxRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LogShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A token of the shape Steam's connection log prints, with nothing real in it. */
    private val token =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFZERTQSJ9.eyJpc3MiOiJ0ZXN0Iiwic3ViIjoiMCJ9.bm90YXJlYWxzaWduYXR1cmU"

    @Test
    fun theSteamClientsSessionTokenIsNotWhatGetsShared() {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        val made = !logs.isDirectory
        assertTrue(logs.isDirectory || logs.mkdirs())
        val log = File(logs, "connection_log.txt")
        try {
            log.writeText("Logon successful\nUsing JWT: $token\nConnected\n")
            val shared = ByteArrayOutputStream()
            LogManager.copyShareable(context, log, shared)
            val text = shared.toString("UTF-8")
            assertFalse(text.contains(token))
            assertFalse(text.contains("eyJ0eXAi"))
            assertTrue(text.contains("Logon successful"))
            assertTrue(text.contains("Connected"))
        } finally {
            log.delete()
            if (made) logs.deleteRecursively()
        }
    }

    @Test
    fun theAppsOwnLogsGoOutAsTheyAre() {
        val log = File(LogManager.getLogsDir(context, false), "log-share-test.txt")
        try {
            log.parentFile?.mkdirs()
            log.writeText("kept $token\n")
            val shared = ByteArrayOutputStream()
            LogManager.copyShareable(context, log, shared)
            assertEquals("kept $token\n", shared.toString("UTF-8"))
        } finally {
            log.delete()
        }
    }
}
