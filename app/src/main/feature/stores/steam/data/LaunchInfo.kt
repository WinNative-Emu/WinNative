package com.winlator.cmod.feature.stores.steam.data
import com.winlator.cmod.feature.stores.steam.db.serializers.OsEnumSetSerializer
import com.winlator.cmod.feature.stores.steam.enums.OS
import com.winlator.cmod.feature.stores.steam.enums.OSArch
import kotlinx.serialization.Serializable
import java.util.EnumSet

@Serializable
data class LaunchInfo(
    val executable: String,
    val workingDir: String,
    val description: String,
    val type: String,
    @Serializable(with = OsEnumSetSerializer::class)
    val configOS: java.util.EnumSet<OS>,
    val configArch: OSArch,
    val launchId: Int = -1,
)
