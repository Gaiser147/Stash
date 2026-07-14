package com.stash.data.download.export

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeRuntimeConstraints @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun areSatisfied(config: NavidromeExportConfig): Boolean {
        val networkMetered = if (config.wifiOnly) {
            runCatching { connectivityManager.isActiveNetworkMetered }.getOrDefault(true)
        } else {
            false
        }
        val deviceCharging = if (config.chargingOnly) {
            runCatching { batteryManager.isCharging }.getOrDefault(false)
        } else {
            true
        }
        return navidromeRuntimeConstraintsMet(
            wifiOnly = config.wifiOnly,
            chargingOnly = config.chargingOnly,
            networkMetered = networkMetered,
            deviceCharging = deviceCharging,
        )
    }
}

internal fun navidromeRuntimeConstraintsMet(
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    networkMetered: Boolean,
    deviceCharging: Boolean,
): Boolean = (!wifiOnly || !networkMetered) && (!chargingOnly || deviceCharging)
