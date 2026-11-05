package com.ulooln.gpstransform.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Bundle
import android.os.SystemClock
import com.ulooln.gpstransform.util.CoordinateConverter

/**
 * 模拟位置不可用（没有模拟位置权限、三个测试 Provider 全部注册失败、
 * 注入时全部失败等）时抛出，让上层明确提示用户，避免「假成功」。
 */
class MockLocationUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

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
 * 注意：update() 的入参是地图坐标（高德瓦片 GCJ-02），内部会先转成 WGS-84
 * 再注入系统，避免国内 App 显示时再次偏移数百米。
 *
 * 使用前提：应用必须被系统设为「模拟位置应用」（开发者选项中）。
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

    private var lastError: Throwable? = null

    /**
     * 注册测试 Provider。重复调用是安全的。
     *
     * @throws SecurityException 没有模拟位置权限
     * @throws MockLocationUnavailableException 三个 Provider 全部注册失败
     */
    fun enable() {
        if (isEnabled) return
        lastError = null

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
                setProviderStatus(config)
                enabledProviders.add(config)
            } catch (e: SecurityException) {
                // 没有模拟位置权限：让上层明确提示用户去开发者选项设置。
                disable()
                throw e
            } catch (e: Exception) {
                // 某个 Provider 在当前系统上不支持时，跳过它，尽量不影响其他 Provider。
                lastError = e
            }
        }

        isEnabled = enabledProviders.isNotEmpty()
        if (!isEnabled) {
            throw MockLocationUnavailableException(
                "没有可用的模拟位置 Provider：请确认已在开发者选项中把本应用设为模拟位置应用",
                lastError
            )
        }
    }

    /** 部分 App（依赖 Provider 状态的定位 SDK）需要 provider 处于 AVAILABLE 状态。 */
    private fun setProviderStatus(config: ProviderConfig) {
        try {
            val extras = Bundle().apply {
                if (config.name == LocationManager.GPS_PROVIDER) {
                    putInt("satellites", 10)
                    putInt("maxSatellites", 32)
                }
            }
            locationManager.setTestProviderStatus(
                config.name,
                LocationProvider.AVAILABLE,
                extras,
                SystemClock.elapsedRealtime()
            )
        } catch (_: Exception) {
            // 部分 ROM/API 上不支持，忽略即可，不影响位置注入。
        }
    }

    /**
     * 把模拟位置同时更新到所有已注册的测试 Provider。
     *
     * @param latitude 地图坐标（GCJ-02）纬度
     * @param longitude 地图坐标（GCJ-02）经度
     * @return 成功注入的 Provider 数量
     * @throws MockLocationUnavailableException 所有 Provider 注入均失败
     */
    fun update(latitude: Double, longitude: Double): Int {
        enable()

        // 地图选点是 GCJ-02，系统 Location 需要 WGS-84。
        val (wgsLatitude, wgsLongitude) = CoordinateConverter.gcj02ToWgs84(latitude, longitude)

        var updated = 0
        var failure: Throwable? = null
        for (config in enabledProviders.toList()) {
            try {
                val location = Location(config.name).apply {
                    this.latitude = wgsLatitude
                    this.longitude = wgsLongitude
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
                updated++
            } catch (e: Exception) {
                failure = e
            }
        }

        if (updated == 0) {
            throw MockLocationUnavailableException(
                "向系统注入模拟位置失败：" + (failure?.message ?: "请检查模拟位置权限"),
                failure
            )
        }
        return updated
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
