package com.winlator.cmod.runtime.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent
import com.winlator.cmod.runtime.display.environment.components.NetworkingSettings
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Tells the Linux runtime which network link the device is on. The sandbox hides interfaces,
 * addresses, routes and the MAC from the runtime's processes, so its preload answers those
 * queries from the file written here (tools/linuxfs/preload/netif.c).
 */
class LinuxNetworkLinkComponent(
    context: Context,
    private val rootDir: File,
    private val driver: String,
    private val fixedMac: String,
) : EnvironmentComponent() {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Called before the session starts, so its first process already sees the link. */
    fun publish() = write(connectivity.activeNetwork?.let(connectivity::getLinkProperties))

    override fun start() {
        if (driver == NetworkingSettings.DRIVER_NONE) return
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = write(properties)

            override fun onLost(network: Network) = write(null)
        }
        synchronized(lock) { callback = registered }
        connectivity.registerDefaultNetworkCallback(registered)
    }

    override fun stop() {
        val registered = synchronized(lock) { callback.also { callback = null } } ?: return
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was already gone", e)
        }
    }

    private fun write(properties: LinkProperties?) {
        val file = File(rootDir, LINK_FILE)
        synchronized(lock) {
            try {
                if (driver == NetworkingSettings.DRIVER_NONE) {
                    file.delete()
                    return
                }
                val staged = File(file.path + ".staged")
                staged.writeText(describe(properties))
                if (!staged.renameTo(file)) throw IOException("Could not replace $file")
            } catch (e: IOException) {
                Log.w(TAG, "Could not publish the network link", e)
            }
        }
    }

    private fun describe(properties: LinkProperties?): String {
        val addresses = properties?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address || it.address is Inet6Address }
        val name = properties?.interfaceName?.takeIf { addresses.isNotEmpty() } ?: OFFLINE_NAME
        val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) properties?.mtu ?: 0 else 0
        val mac = NetworkingSettings.normalizeMac(fixedMac)
            .ifEmpty { NetworkingSettings.automaticMac(appContext, name) }
        return buildString {
            append("if $name $LINK_INDEX ${if (mtu > 0) mtu else DEFAULT_MTU}\n")
            append("mac $mac\n")
            if (addresses.isEmpty()) {
                append("addr $OFFLINE_ADDRESS\n")
                return@buildString
            }
            for (address in addresses) {
                append("addr ${address.address.hostAddress?.substringBefore('%')} ${address.prefixLength}\n")
            }
            for (family in listOf(Inet4Address::class.java, Inet6Address::class.java)) {
                val gateway = properties?.routes.orEmpty()
                    .firstOrNull { it.isDefaultRoute && family.isInstance(it.gateway) && !it.gateway!!.isAnyLocalAddress }
                    ?.gateway ?: continue
                append("gw ${gateway.hostAddress?.substringBefore('%')}\n")
            }
        }
    }

    companion object {
        private const val TAG = "LinuxNetworkLink"
        private const val LINK_FILE = "etc/winnative-net"
        private const val LINK_INDEX = 2
        private const val DEFAULT_MTU = 1500
        private const val OFFLINE_NAME = "eth0"
        private const val OFFLINE_ADDRESS = "10.0.0.2 24"
    }
}
