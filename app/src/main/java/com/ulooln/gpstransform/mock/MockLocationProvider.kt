package com.ulooln.gpstransform.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * 封装 Android 的模拟位置 Provider。
 *
 * 原理：向 LocationManager 注册名为 "gps"、"network"、"passive" 的测试 Provider，
 * 然后通过 setTestProviderLocation 注入我们想要的位置。
 *
 * 为了让其他 App 更难分辨，这里按真实 Provider 的习惯设置不同参数：
 * - GPS：高精度、支持海拔/速度/方向、功耗高；
 * - Network/Passive：精度较粗、不支持海拔/速度/方向、功耗低。
 *
 * 使用前提：应用必须被系统设为“模拟位置应用”（开发者选项中）。
 */
class MockLocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private data class ProviderConfig(
        val name: String,
        val supportsAltitude: Boolean,
        val supportsSpeed: Boolean,
        val supportsBearing: Boolean,
        val powerRequirement: Int,
        val accuracyRequirement: Int,
        val locationAccuracy: Float
    )

    private val providerConfigs = listOf(
        ProviderConfig(
            name = LocationManager.GPS_PROVIDER,
            supportsAltitude = true,
            supportsSpeed = true,
            supportsBearing = true,
            powerRequirement = Criteria.POWER_HIGH,
            accuracyRequirement = Criteria.ACCURACY_FINE,
            locationAccuracy = 5f
        ),
        ProviderConfig(
            name = LocationManager.NETWORK_PROVIDER,
            supportsAltitude = false,
            supportsSpeed = false,
            supportsBearing = false,
            powerRequirement = Criteria.POWER_LOW,
            accuracyRequirement = Criteria.ACCURACY_COARSE,
            locationAccuracy = 20f
        ),
        ProviderConfig(
            name = LocationManager.PASSIVE_PROVIDER,
            supportsAltitude = false,
            supportsSpeed = false,
            supportsBearing = false,
            powerRequirement = Criteria.POWER_LOW,
            accuracyRequirement = Criteria.ACCURACY_COARSE,
            locationAccuracy = 20f
        )
    )

    /** 当前已经成功注册的测试 Provider。 */
    private val enabledProviders = mutableListOf<ProviderConfig>()

    @Volatile
    var isEnabled: Boolean = false
        private set

    /**
     * 注册测试 Provider。重复调用是安全的。
     */
    fun enable() {
        if (isEnabled) return

        for (config in providerConfigs) {
            try {
                // 如果已经存在同名测试 Provider，先移除，避免重复注册冲突。
                try {
                    locationManager.removeTestProvider(config.name)
                } catch (_: IllegalArgumentException) {
                    // Provider 不存在时忽略。
                }

                locationManager.addTestProvider(
                    config.name,
                    false, // requiresNetwork
                    false, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    config.supportsAltitude,
                    config.supportsSpeed,
                    config.supportsBearing,
                    config.powerRequirement,
                    config.accuracyRequirement
                )
                locationManager.setTestProviderEnabled(config.name, true)
                enabledProviders.add(config)
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

        for (config in enabledProviders.toList()) {
            val location = Location(config.name).apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = config.locationAccuracy
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (config.supportsAltitude) {
                    altitude = 0.0
                }
                if (config.supportsSpeed) {
                    speed = 0f
                }
                if (config.supportsBearing) {
                    bearing = 0f
                }
            }
            locationManager.setTestProviderLocation(config.name, location)
        }
    }

    /**
     * 注销所有测试 Provider，恢复系统真实定位。
     */
    fun disable() {
        if (enabledProviders.isEmpty()) return

        for (config in enabledProviders.toList()) {
            try {
                locationManager.setTestProviderEnabled(config.name, false)
                locationManager.removeTestProvider(config.name)
            } catch (_: Exception) {
                // 停止时即使系统拒绝清理，也不应导致应用崩溃。
            }
        }
        enabledProviders.clear()
        isEnabled = false
    }
}
