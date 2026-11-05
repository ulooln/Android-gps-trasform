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
import kotlinx.coroutines.CancellationException
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
 * Activity 通过绑定服务调用 setMockLocation / startCruise；
 * [stopMocking] 用于恢复系统真实定位（通知栏「停止模拟」按钮也会调用）。
 */
class MockLocationService : Service() {

    /** 巡航进度回调，由绑定的 Activity 实现。 */
    interface CruiseListener {
        fun onCruiseLocation(location: GeoPoint)
        fun onCruiseFinished()

        /** 模拟位置失效（权限被撤销、Provider 被系统移除等）。 */
        fun onMockError(message: String) {}
    }

    private val binder = LocalBinder()

    private lateinit var mockProvider: MockLocationProvider
    private var cruiseController: CruiseController? = null
    private var cruiseListener: CruiseListener? = null
    private var pulseJob: Job? = null
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0

    @Volatile
    var isMocking: Boolean = false
        private set

    @Volatile
    var isCruising: Boolean = false
        private set

    /** 最近一次注入的地图坐标（GCJ-02），供 Activity 重建后恢复 UI。 */
    @Volatile
    var lastLatitude: Double = 0.0
        private set

    @Volatile
    var lastLongitude: Double = 0.0
        private set

    private var isForeground = false

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
        if (intent?.action == ACTION_STOP) {
            stopMocking()
            return START_NOT_STICKY
        }
        // 不再粘性重启：避免用户以为已停止、实际仍在注入。
        return START_NOT_STICKY
    }

    /**
     * 立即把模拟位置设置为指定经纬度（地图坐标 GCJ-02）。
     * 权限不足或未开启模拟位置时抛出异常，由调用方提示用户。
     */
    fun setMockLocation(latitude: Double, longitude: Double) {
        ensureForeground()
        mockProvider.update(latitude, longitude)
        currentLatitude = latitude
        currentLongitude = longitude
        lastLatitude = latitude
        lastLongitude = longitude
        isMocking = true
        startMockPulse()
        refreshNotification()
    }

    /** 设置巡航进度回调（例如 Activity 重建后重新绑定）。 */
    fun setCruiseListener(listener: CruiseListener?) {
        cruiseListener = listener
    }

    /** 开始巡航。 */
    fun startCruise(points: List<RoutePoint>, listener: CruiseListener) {
        ensureForeground()
        stopMockPulse()
        // 直接停旧任务，不能走 stopCruise()（它会因为 isMocking 而重启脉冲，和巡航 tick 打架）。
        cruiseController?.stop()
        cruiseController = null
        cruiseListener = listener
        isCruising = true
        isMocking = true
        cruiseController = CruiseController(mockProvider, serviceScope)
        cruiseController?.start(
            points = points,
            onTick = { location ->
                lastLatitude = location.latitude
                lastLongitude = location.longitude
                // 巡航位置同步为脉冲位置，巡航结束后 1Hz 脉冲继续停在终点。
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                isMocking = true
                cruiseListener?.onCruiseLocation(location)
            },
            onFinished = {
                isCruising = false
                cruiseController = null
                // 巡航结束后保持 1Hz 脉冲，避免目标 App 因长时间无更新而回退真实位置。
                startMockPulse()
                refreshNotification()
                cruiseListener?.onCruiseFinished()
            },
            onError = { throwable ->
                isCruising = false
                cruiseController = null
                refreshNotification()
                cruiseListener?.onMockError(
                    "巡航中断：" + (throwable.message ?: "模拟位置不可用")
                )
            }
        )
        refreshNotification()
    }

    /** 停止巡航；保留模拟 Provider，恢复对最后一次位置的 1Hz 脉冲。 */
    fun stopCruise() {
        cruiseController?.stop()
        cruiseController = null
        isCruising = false
        if (isMocking) {
            startMockPulse()
        }
        refreshNotification()
    }

    /** 停止模拟定位、注销测试 Provider 并撤下前台通知（恢复系统真实定位）。 */
    fun stopMocking() {
        stopMockPulse()
        cruiseController?.stop()
        cruiseController = null
        isCruising = false
        isMocking = false
        try {
            mockProvider.disable()
        } catch (_: Exception) {
            // 清理失败不阻塞退出。
        }
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        stopSelf()
    }

    private fun ensureForeground() {
        if (!isForeground) {
            startAsForeground()
        }
    }

    /**
     * 持续以 1Hz 重复发送同一个模拟位置。
     * 很多地图/打车 App 只响应持续的定位更新，单次 setTestProviderLocation 可能不够。
     */
    private fun startMockPulse() {
        stopMockPulse()
        pulseJob = serviceScope.launch {
            try {
                while (isActive) {
                    mockProvider.update(currentLatitude, currentLongitude)
                    delay(PULSE_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 权限被撤销 / Provider 被系统移除：停止脉冲并通知 UI，避免进程崩溃。
                isMocking = false
                stopMockPulse()
                cruiseListener?.onMockError(
                    "模拟位置已中断：" + (e.message ?: "请重新在开发者选项中授权")
                )
            }
        }
    }

    private fun stopMockPulse() {
        pulseJob?.cancel()
        pulseJob = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            mockProvider.disable()
        } catch (_: Exception) {
            // 忽略清理异常。
        }
        isMocking = false
        isCruising = false
        isForeground = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun refreshNotification() {
        if (!isForeground) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // 通知被禁用（Android 13+ 未授予 POST_NOTIFICATIONS）时忽略。
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (isCruising) {
            "巡航中：" + formatPoint(lastLatitude, lastLongitude)
        } else {
            "模拟定位运行中：" + formatPoint(lastLatitude, lastLongitude)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mock_location)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatPoint(latitude: Double, longitude: Double): String {
        if (latitude == 0.0 && longitude == 0.0) return getString(R.string.notification_idle)
        return String.format("%.6f, %.6f", latitude, longitude)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "com.ulooln.gpstransform.action.STOP"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        private const val PULSE_INTERVAL_MS = 1_000L
    }
}
