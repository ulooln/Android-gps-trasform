package com.ulooln.gpstransform.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * 封装 Android 的模拟位置 Provider。
 *
 * 原理：向 LocationManager 注册名为 "gps"、"network" 的测试 Provider，
 * 然后通过 setTestProviderLocation 注入我们想要的位置。
 * 同时覆盖 GPS 和网络定位，能提高高德、百度等地图 App 读到模拟位置的概率。
 *
 * 使用前提：应用必须被系统设为“模拟位置应用”（开发者选项中）。
 */
class MockLocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** 需要模拟的 Provider。不同 App 可能监听 GPS、网络或被动定位，因此都注册。 */
    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    /** 当前已经成功注册的测试 Provider。 */
    private val enabledProviders = mutableListOf<String>()

    @Volatile
    var isEnabled: Boolean = false
        private set

    /**
     * 注册测试 Provider。重复调用是安全的。
     */
    fun enable() {
        if (isEnabled) return

        for (provider in providers) {
            try {
                // 如果已经存在同名测试 Provider，先移除，避免重复注册冲突。
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: IllegalArgumentException) {
                    // Provider 不存在时忽略。
                }

                locationManager.addTestProvider(
                    provider,
                    false, // requiresNetwork
                    false, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    true,  // supportsAltitude
                    true,  // supportsSpeed
                    true,  // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                enabledProviders.add(provider)
            } catch (e: SecurityException) {
                // 没有模拟位置权限：让上层明确提示用户去开发者选项设置。
                disable()
                throw e
            } catch (_: Exception) {
                // 某个 Provider 在当前系统上不支持时，跳过它，尽量不影响其他 Provider。
            }
        }

        isEnabled = enabledProviders.isNotEmpty()
    }

    /**
     * 把模拟位置同时更新到所有已注册的测试 Provider。
     */
    fun update(latitude: Double, longitude: Double) {
        enable()

        for (provider in enabledProviders.toList()) {
            val location = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = 3f
                altitude = 0.0
                speed = 0f
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras?.putInt("satellites", 10)
                extras?.putInt("maxSatellites", 32)
            }
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    /**
     * 注销所有测试 Provider，恢复系统真实定位。
     */
    fun disable() {
        if (enabledProviders.isEmpty()) return

        for (provider in enabledProviders.toList()) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
                // 停止时即使系统拒绝清理，也不应导致应用崩溃。
            }
        }
        enabledProviders.clear()
        isEnabled = false
    }
}
