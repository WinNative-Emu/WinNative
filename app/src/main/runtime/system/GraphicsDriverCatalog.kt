package com.winlator.cmod.runtime.system

import android.content.Context
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import java.util.concurrent.ConcurrentHashMap

object GraphicsDriverCatalog {
    private const val TAG = "GraphicsDriverCatalog"
    private val supportCache = ConcurrentHashMap<String, Boolean>()
    private val extensionCache = ConcurrentHashMap<String, List<String>>()

    @JvmStatic
    fun supportedVersions(context: Context): List<String> {
        val versions = mutableListOf<String>()
        try {
            for (ver in context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries)) {
                val supported =
                    supportCache[ver] ?: try {
                        GPUInformation.isDriverSupported(ver, context)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Driver support check failed: $ver", e)
                        false
                    }.also { supportCache[ver] = it }
                if (supported) versions.add(ver)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading wrapper versions", e)
        }
        try {
            AdrenotoolsManager(context).enumarateInstalledDrivers()?.let { versions.addAll(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Error loading Adrenotools drivers", e)
        }
        if (versions.isEmpty()) versions.add("System")
        return versions
    }

    @JvmStatic
    fun extensions(
        context: Context,
        version: String,
    ): List<String> {
        extensionCache[version]?.let { return it }
        val extensions =
            try {
                GPUInformation.enumerateExtensions(version, context)?.toList() ?: emptyList()
            } catch (e: Throwable) {
                Log.e(TAG, "Error loading extensions for $version", e)
                emptyList()
            }
        if (extensions.isNotEmpty()) extensionCache[version] = extensions
        return extensions
    }

    @JvmStatic
    fun invalidate() {
        supportCache.clear()
        extensionCache.clear()
    }
}
