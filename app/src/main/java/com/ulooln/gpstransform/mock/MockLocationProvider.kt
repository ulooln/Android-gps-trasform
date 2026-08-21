package com.ulooln.gpstransform.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * 封装 Android 的模拟位置 Provider。
 *
 * 原理：向 LocationManager 注册一个名为 "gps" 的测试 Provider，
 * 然后通过 setTestProviderLocation 注入我们想要的位置。
 *
 * 使用前提：应用必须被系统设为“模拟位置应用”（开发者选项中）。
 */
class MockLocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val providerName = LocationManager.GPS_PROVIDER

    @Volatile
    var isEnabled: Boolean = false
        private set

    /**
     * 注册测试 Provider。重复调用是安全的。
     */
    fun enable() {
        if (isEnabled) return

        // 如果已经存在同名测试 Provider，先移除，避免重复注册冲突。
        try {
            locationManager.removeTestProvider(providerName)
        } catch (_: IllegalArgumentException) {
            // Provider 不存在时忽略。
        } catch (_: SecurityException) {
            // 没有模拟位置权限时，下面 addTestProvider 会再次抛出更明确的异常。
        }

        locationManager.addTestProvider(
            providerName,
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
        locationManager.setTestProviderEnabled(providerName, true)
        isEnabled = true
    }

    /**
     * 把模拟位置更新到指定经纬度。
     */
    fun update(latitude: Double, longitude: Double) {
        enable()

        val location = Location(providerName).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locationManager.setTestProviderLocation(providerName, location)
    }

    /**
     * 注销测试 Provider，恢复系统真实定位。
     */
    fun disable() {
        if (!isEnabled) return
        try {
            locationManager.setTestProviderEnabled(providerName, false)
            locationManager.removeTestProvider(providerName)
        } catch (_: Exception) {
            // 停止时即使系统拒绝清理，也不应导致应用崩溃。
        } finally {
            isEnabled = false
        }
    }
}
