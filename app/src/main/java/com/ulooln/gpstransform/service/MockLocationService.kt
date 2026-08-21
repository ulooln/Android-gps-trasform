package com.ulooln.gpstransform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ulooln.gpstransform.MainActivity
import com.ulooln.gpstransform.R
import com.ulooln.gpstransform.cruise.CruiseController
import com.ulooln.gpstransform.mock.MockLocationProvider
import com.ulooln.gpstransform.model.RoutePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * 前台服务：承载模拟位置 Provider 与巡航引擎。
 *
 * Activity 通过绑定服务调用 setMockLocation / startCruise，
 * 即使应用退到后台，服务仍能继续向系统注入模拟位置。
 */
class MockLocationService : Service() {

    /** 巡航进度回调，由绑定的 Activity 实现。 */
    interface CruiseListener {
        fun onCruiseLocation(location: GeoPoint)
        fun onCruiseFinished()
    }

    private val binder = LocalBinder()

    private lateinit var mockProvider: MockLocationProvider
    private var cruiseController: CruiseController? = null
    private var cruiseListener: CruiseListener? = null
    private var pulseJob: Job? = null
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        mockProvider = MockLocationProvider(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被系统杀死后尽量重建。
        return START_STICKY
    }

    /**
     * 立即把模拟位置设置为指定经纬度。
     * 权限不足或未开启模拟位置时可能抛出异常，由调用方提示用户。
     */
    fun setMockLocation(latitude: Double, longitude: Double) {
        mockProvider.update(latitude, longitude)
        currentLatitude = latitude
        currentLongitude = longitude
        startMockPulse()
    }

    /** 设置巡航进度回调（例如 Activity 重建后重新绑定）。 */
    fun setCruiseListener(listener: CruiseListener?) {
        cruiseListener = listener
    }

    /** 开始巡航。 */
    fun startCruise(points: List<RoutePoint>, listener: CruiseListener) {
        stopMockPulse()
        stopCruise()
        cruiseListener = listener
        cruiseController = CruiseController(mockProvider, serviceScope)
        cruiseController?.start(
            points = points,
            onTick = { location ->
                cruiseListener?.onCruiseLocation(location)
            },
            onFinished = {
                cruiseListener?.onCruiseFinished()
                cruiseController = null
            }
        )
    }

    /** 停止巡航，保留模拟 Provider。 */
    fun stopCruise() {
        cruiseController?.stop()
        cruiseController = null
    }

    /** 停止巡航并注销模拟 Provider。 */
    fun shutdownMocking() {
        stopMockPulse()
        stopCruise()
        mockProvider.disable()
    }

    /**
     * 持续以 1Hz 重复发送同一个模拟位置。
     * 很多地图/打车 App 只响应持续的定位更新，单次 setTestProviderLocation 可能不够。
     */
    private fun startMockPulse() {
        stopMockPulse()
        pulseJob = serviceScope.launch {
            while (isActive) {
                mockProvider.update(currentLatitude, currentLongitude)
                delay(1_000L)
            }
        }
    }

    private fun stopMockPulse() {
        pulseJob?.cancel()
        pulseJob = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mockProvider.disable()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("模拟定位服务运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模拟定位服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
    }
}
