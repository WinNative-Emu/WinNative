package com.winlator.cmod.runtime.linux

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.display.environment.components.LinuxProgramLauncherComponent
import com.winlator.cmod.runtime.wine.EnvVars
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LinuxLauncherTest {
    @Test
    fun preservesArgumentsAndCapturesEarlyStderr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(context.cacheDir, "linux-launcher-test.log").apply { delete() }
        val done = CountDownLatch(1)
        val exit = AtomicInteger(-1)
        val launcher = LinuxProgramLauncherComponent(
            listOf("/system/bin/sh", "-c", "printf '<%s>\\n' \"\$@\"; echo loader-error >&2; exit 23", "test", "a b", "", "quote\"value", "apostrophe'value"),
            EnvVars(), context.cacheDir, log
        ) { status -> exit.set(status); done.countDown() }
        try {
            launcher.start()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(23, exit.get())
            val text = log.readText()
            assertTrue(text.contains("<a b>\n<>\n<quote\"value>\n<apostrophe'value>"))
            assertTrue(text.contains("loader-error"))
            assertTrue(text.contains("status 23"))
        } finally {
            launcher.stop()
            log.delete()
        }
    }

    /** The guest command carries the container's environment, where a user may have typed anything. */
    @Test
    fun theSessionLogSaysWhatWasAskedAndHowItEnded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(context.cacheDir, "linux-launcher-record.log").apply { delete() }
        val done = CountDownLatch(1)
        val launcher = LinuxProgramLauncherComponent(
            listOf("/system/bin/sh", "-c", "kill -9 \$\$", "--kill-on-exit", "-r", "/root",
                "/usr/bin/env", "STEAM_TOKEN=must-not-be-logged"),
            EnvVars(), context.cacheDir, log
        ) { done.countDown() }
        try {
            launcher.start()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            val text = log.readText()
            assertTrue(text, text.contains("WinNative " + com.winlator.cmod.BuildConfig.VERSION_NAME))
            assertTrue(text, text.contains("--kill-on-exit -r /root"))
            assertFalse(text, text.contains("must-not-be-logged"))
            assertTrue(text, text.contains("status 137 (SIGKILL"))
        } finally {
            launcher.stop()
            log.delete()
        }
    }

    @Test
    fun reportsFailureWhenProotCannotStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(context.cacheDir, "linux-launcher-missing.log").apply { delete() }
        val done = CountDownLatch(1)
        val exit = AtomicInteger(-1)
        val launcher = LinuxProgramLauncherComponent(
            listOf(File(context.cacheDir, "missing-proot-executable").path),
            EnvVars(), context.cacheDir, log
        ) { status -> exit.set(status); done.countDown() }
        try {
            launcher.start()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(127, exit.get())
            assertTrue(log.readText().contains("could not start"))
        } finally {
            launcher.stop()
            log.delete()
        }
    }
}
