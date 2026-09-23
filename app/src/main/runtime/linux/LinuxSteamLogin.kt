package com.winlator.cmod.runtime.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.feature.stores.steam.utils.KeyValue
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.runtime.linux.LinuxSteamVdf.STEAM_KEY
import com.winlator.cmod.runtime.linux.LinuxSteamVdf.load
import com.winlator.cmod.runtime.linux.LinuxSteamVdf.save
import com.winlator.cmod.runtime.linux.LinuxSteamVdf.section
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps the Linux Steam client signed in as the account the Steam store is signed in with.
 *
 * The client remembers an account in three files: `local.vdf` holds the refresh token under
 * `ConnectCache`, `config/loginusers.vdf` lists the account and that it signs in by itself, and
 * `registry.vdf` names it as the one to sign in at start. The token is stored the way the client
 * stores it on Linux: AES-256 keyed with the SHA-256 of the account name, in CBC with the IV sent
 * ahead as one ECB block, under the CRC-32 of the account name followed by "1".
 *
 * The token never leaves the app's files: it goes from the app's own encrypted preferences into
 * the runtime's home directory and nowhere else - not an argument, not the environment, not a log.
 */
object LinuxSteamLogin {
    private const val TAG = "LinuxSteamLogin"
    private fun steamRoot(context: Context) = LinuxSteamVdf.steamRoot(LinuxRuntime.rootDir(context))

    private fun registry(context: Context) = File(LinuxRuntime.rootDir(context), "root/.steam/registry.vdf")

    /**
     * Worker thread, with no client running. Signs the client in as the store's account unless it
     * already remembers that account, whose own token is then the newer one. Does nothing while the
     * store is signed out: an account the user signed the client into by hand stays.
     */
    @JvmStatic
    fun seed(context: Context) {
        val account = PrefManager.username
        val token = PrefManager.refreshToken
        val steamId = PrefManager.steamUserSteamId64
        if (account.isBlank() || token.isBlank() || steamId == 0L) return
        try {
            val root = steamRoot(context)
            val local = File(root, "local.vdf")
            val machine = load(local, "MachineUserConfigStore")
            val cache = section(machine, STEAM_KEY + "ConnectCache")
            val users = File(root, "config/loginusers.vdf")
            val known = load(users, "users")
            val remembered =
                cache.children.any { it.name == cacheKey(account) } && known[steamId.toString()] !== KeyValue.INVALID
            if (!remembered) {
                cache.children.removeAll { it.name == cacheKey(account) }
                cache.children += KeyValue(cacheKey(account), encrypt(token, account))
                save(local, machine)
                known.children.removeAll { it.name == steamId.toString() }
                known.children += loginUser(steamId, account)
                save(users, known)
                Log.i(TAG, "signed the Linux client in as the store's account")
            }
            val registryFile = registry(context)
            val registryRoot = load(registryFile, "Registry")
            val steam = section(registryRoot, listOf("HKCU") + STEAM_KEY)
            if (steam["AutoLoginUser"].asString() != account) {
                steam.children.removeAll { it.name.equals("AutoLoginUser", ignoreCase = true) }
                steam.children += KeyValue("AutoLoginUser", account)
                save(registryFile, registryRoot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not sign the Linux client in: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Worker thread. The store was signed out of [account]: the client forgets it too. Other
     * accounts the client remembers are not the store's to remove.
     */
    @JvmStatic
    fun clear(context: Context, account: String, steamId: Long) {
        if (account.isBlank() || !LinuxRuntime.isInstalled(context)) return
        try {
            val root = steamRoot(context)
            val local = File(root, "local.vdf")
            if (local.isFile) {
                val tree = load(local, "MachineUserConfigStore")
                val cache = section(tree, STEAM_KEY + "ConnectCache")
                if (cache.children.removeAll { it.name == cacheKey(account) }) save(local, tree)
            }
            val users = File(root, "config/loginusers.vdf")
            if (users.isFile) {
                val known = load(users, "users")
                if (known.children.removeAll { it.name == steamId.toString() }) save(users, known)
            }
            val registryFile = registry(context)
            if (registryFile.isFile) {
                val registryRoot = load(registryFile, "Registry")
                val steam = section(registryRoot, listOf("HKCU") + STEAM_KEY)
                if (steam["AutoLoginUser"].asString() == account) {
                    steam.children.removeAll { it.name.equals("AutoLoginUser", ignoreCase = true) }
                    save(registryFile, registryRoot)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not sign the Linux client out: ${e.javaClass.simpleName}")
        }
    }

    private fun loginUser(steamId: Long, account: String): KeyValue =
        KeyValue(steamId.toString()).apply {
            isSection = true
            children += KeyValue("AccountName", account)
            children += KeyValue("PersonaName", account)
            children += KeyValue("RememberPassword", "1")
            children += KeyValue("WantsOfflineMode", "0")
            children += KeyValue("SkipOfflineModeWarning", "0")
            children += KeyValue("AutoLogin", "1")
            children += KeyValue("timestamp", (System.currentTimeMillis() / 1000).toString())
        }

    private fun cacheKey(account: String): String {
        val crc = CRC32().apply { update(account.toByteArray()) }.value
        return "%08x1".format(crc)
    }

    private fun encrypt(token: String, account: String): String {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(account.toByteArray()), "AES")
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wrappedIv = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(iv)
        }
        val body = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            doFinal(token.toByteArray())
        }
        return (wrappedIv + body).joinToString("") { "%02x".format(it) }
    }
}
