package com.winlator.cmod.shared.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPlatformTest {
    // ro.product.cpu.abilist read from a live WSA.
    private val wsaAbis = arrayOf("x86_64", "arm64-v8a", "x86", "armeabi-v7a", "armeabi")
    private val phoneAbis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test
    fun x86ApkOnWsaRunsAsX86_64() {
        assertEquals("x86_64", HostPlatform.pickProcessAbi(wsaAbis, "arm64-v8a,x86_64"))
        assertEquals("x86_64", HostPlatform.pickProcessAbi(wsaAbis, "x86_64"))
    }

    @Test
    fun arm64OnlyApkOnWsaStaysArm64() {
        // The platform would run it through its ARM translation; our native libs are arm64.
        assertEquals("arm64-v8a", HostPlatform.pickProcessAbi(wsaAbis, "arm64-v8a"))
    }

    @Test
    fun universalApkOnPhoneRunsAsArm64() {
        assertEquals("arm64-v8a", HostPlatform.pickProcessAbi(phoneAbis, "arm64-v8a,x86_64"))
    }

    @Test
    fun apkWithNoCommonAbiFallsBackToDevicePreference() {
        assertEquals("arm64-v8a", HostPlatform.pickProcessAbi(phoneAbis, "x86_64"))
    }

    @Test
    fun apkAbiListIsTrimmedAndTolerant() {
        assertEquals("x86_64", HostPlatform.pickProcessAbi(wsaAbis, " arm64-v8a , x86_64 ,, "))
        assertEquals("x86_64", HostPlatform.pickProcessAbi(wsaAbis, null))
        assertEquals("x86_64", HostPlatform.pickProcessAbi(wsaAbis, "   "))
    }

    @Test
    fun deviceWithoutAbisYieldsEmpty() {
        assertEquals("", HostPlatform.pickProcessAbi(null, "arm64-v8a"))
        assertEquals("", HostPlatform.pickProcessAbi(emptyArray(), "arm64-v8a"))
    }

    @Test
    fun recognisesWsaByModel() {
        assertTrue(
            HostPlatform.looksLikeWsa(
                "Subsystem for Android(TM)", "Microsoft Corporation", "Windows", "x", "y"))
    }

    @Test
    fun recognisesWsaByDeviceOrProduct() {
        assertTrue(HostPlatform.looksLikeWsa("m", "m", "b", "windows_x86_64", "p"))
        assertTrue(HostPlatform.looksLikeWsa("m", "m", "b", "d", "Windows_arm64"))
    }

    @Test
    fun recognisesWsaByManufacturerAndBrand() {
        assertTrue(HostPlatform.looksLikeWsa("m", "Microsoft Corporation", "Windows", "d", "p"))
    }

    @Test
    fun doesNotFlagOrdinaryDevices() {
        assertFalse(HostPlatform.looksLikeWsa("Pixel 8", "Google", "google", "shiba", "shiba"))
        assertFalse(
            HostPlatform.looksLikeWsa("SM-S928B", "samsung", "samsung", "e3q", "e3qxeea"))
    }

    @Test
    fun doesNotFlagRealMicrosoftHardware() {
        // Microsoft-made phones are not WSA; the brand has to say Windows as well.
        assertFalse(HostPlatform.looksLikeWsa("Surface Duo", "Microsoft Corporation", "Microsoft", "duo", "duo"))
    }

    @Test
    fun toleratesNulls() {
        assertFalse(HostPlatform.looksLikeWsa(null, null, null, null, null))
    }

    @Test
    fun recognisesTheLiveWsaValues() {
        // getprop values captured from a real WSA install.
        assertTrue(
            HostPlatform.looksLikeWsa(
                "Subsystem for Android(TM)", "Microsoft Corporation", "", "windows_x86_64", ""))
    }

    @Test
    fun icdManifestNameFollowsTheProcessAbi() {
        assertEquals("x86_64", HostPlatform.icdCpuNameFor("x86_64"))
        assertEquals("aarch64", HostPlatform.icdCpuNameFor("arm64-v8a"))
        assertEquals("aarch64", HostPlatform.icdCpuNameFor(""))
    }
}
