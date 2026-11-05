package com.ulooln.gpstransform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ulooln.gpstransform.mock.MockLocationUnavailableException
import com.ulooln.gpstransform.model.RoutePoint
import com.ulooln.gpstransform.root.RootHelper
import com.ulooln.gpstransform.service.MockLocationService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * 主界面：地图 + 单点定位 / 巡航模式。
 *
 * 单点模式：点击地图立即设置模拟位置。
 * 巡航模式：长按地图依次添加起点、途经点、终点，并可设置每段通过秒数。
 * 界面提供「停止模拟」入口，用于恢复系统真实定位。
 */
/**
 * 高德地图瓦片源。
 *
 * XYTileSource 只支持 "baseUrl/z/x/y.png" 这种路径格式，
 * 而高德瓦片使用 query 参数，所以这里自定义 URL 拼装。
 */
private class AmapTileSource : OnlineTileSourceBase(
    "AMap",
    3,
    19,
    256,
    ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile",
        "https://webrd02.is.autonavi.com/appmaptile",
        "https://webrd03.is.autonavi.com/appmaptile",
        "https://webrd04.is.autonavi.com/appmaptile"
    ),
    "高德地图"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl?lang=zh_cn&size=1&scale=1&style=7&x=$x&y=$y&z=$zoom"
    }
}

class MainActivity : AppCompatActivity(), MockLocationService.CruiseListener, ServiceConnection {

    private enum class Mode { SINGLE, CRUISE }

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var durationInput: EditText
    private lateinit var singleModeButton: Button
    private lateinit var cruiseModeButton: Button
    private lateinit var startCruiseButton: Button
    private lateinit var stopCruiseButton: Button
    private lateinit var stopMockingButton: Button
    private lateinit var rootModeSwitch: Switch
    private lateinit var rootStatusText: TextView

    private var service: MockLocationService? = null
    private var serviceBound = false
    private var mode = Mode.SINGLE

    private val routePoints = mutableListOf<RoutePoint>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null
    private var currentMarker: Marker? = null
    private var isCruising = false
    private var isMocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid 需要先加载配置。
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        setContentView(R.layout.activity_main)

        bindViews()
        restoreState(savedInstanceState)
        setupMap()
        setupButtons()
        requestLocationAndStartService()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        routeInfoText = findViewById(R.id.routeInfoText)
        durationInput = findViewById(R.id.durationInput)
        singleModeButton = findViewById(R.id.singleModeButton)
        cruiseModeButton = findViewById(R.id.cruiseModeButton)
        startCruiseButton = findViewById(R.id.startCruiseButton)
        stopCruiseButton = findViewById(R.id.stopCruiseButton)
        stopMockingButton = findViewById(R.id.stopMockingButton)
        rootModeSwitch = findViewById(R.id.rootModeSwitch)
        rootStatusText = findViewById(R.id.rootStatusText)
    }

    /** 旋转/重建后恢复路线与模式，避免 UI 与服务状态不一致。 */
    private fun restoreState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        val lats = savedInstanceState.getDoubleArray(KEY_LATITUDES) ?: return
        val lons = savedInstanceState.getDoubleArray(KEY_LONGITUDES) ?: return
        val durations = savedInstanceState.getLongArray(KEY_DURATIONS) ?: return
        if (lats.size != lons.size || lats.size != durations.size) return

        routePoints.clear()
        for (i in lats.indices) {
            routePoints.add(RoutePoint(lats[i], lons[i], durations[i]))
        }
        val savedMode = savedInstanceState.getString(KEY_MODE)
        mode = if (savedMode == Mode.CRUISE.name) Mode.CRUISE else Mode.SINGLE
    }

    private fun setupMap() {
        // 使用高德瓦片源：OpenStreetMap 在国内经常无法访问，会导致只显示灰色网格。
        Configuration.getInstance().userAgentValue = packageName
        mapView.setTileSource(AmapTileSource())
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(GeoPoint(39.9042, 116.4074)) // 默认北京

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                if (point != null && mode == Mode.SINGLE) {
                    setMockLocation(point)
                    return true
                }
                return false
            }

            override fun longPressHelper(point: GeoPoint?): Boolean {
                if (point != null && mode == Mode.CRUISE) {
                    addRoutePoint(point)
                    return true
                }
                return false
            }
        }
        mapView.overlays.add(MapEventsOverlay(receiver))
    }

    private fun setupButtons() {
        singleModeButton.setOnClickListener {
            mode = Mode.SINGLE
            updateModeUi()
            statusText.text = "单点模式：点击地图立即设置位置"
        }

        cruiseModeButton.setOnClickListener {
            mode = Mode.CRUISE
            updateModeUi()
            statusText.text = "巡航模式：长按地图添加起点/途经点/终点"
        }

        startCruiseButton.setOnClickListener { startCruise() }
        stopCruiseButton.setOnClickListener { stopCruise() }
        stopMockingButton.setOnClickListener { stopMocking() }

        findViewById<Button>(R.id.clearButton).setOnClickListener { clearRoute() }

        rootModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                rootStatusText.text = "未开启"
                return@setOnCheckedChangeListener
            }
            // root 检测要启动 su 子进程，必须放到后台线程，避免主线程阻塞/ANR。
            rootModeSwitch.isEnabled = false
            rootStatusText.text = "检测 root 中…"
            Thread {
                val available = RootHelper.isRootAvailable()
                runOnUiThread {
                    rootModeSwitch.isEnabled = true
                    if (available) {
                        rootStatusText.text = "已开启，设置位置时自动配置系统权限"
                    } else {
                        rootModeSwitch.isChecked = false
                        rootStatusText.text = "未检测到 root"
                        toast("未检测到 root 权限，Root 模式不可用")
                    }
                }
            }.start()
        }

        updateModeUi()
        updateControlState()
    }

    private fun updateModeUi() {
        singleModeButton.isSelected = mode == Mode.SINGLE
        cruiseModeButton.isSelected = mode == Mode.CRUISE
    }

    /** 依据实际状态刷新按钮，旋转/重建后不会出现「巡航中但停止按钮不可用」。 */
    private fun updateControlState() {
        stopCruiseButton.isEnabled = isCruising
        startCruiseButton.isEnabled = !isCruising
        stopMockingButton.isEnabled = isMocking || isCruising
    }

    // ---------------------------------------------------------------------
    // 权限与前台服务
    // ---------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationAndStartService() {
        if (hasLocationPermission()) {
            startAndBindService()
            requestNotificationPermissionIfNeeded()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
        }
    }

    /** Android 13+ 前台服务通知需要 POST_NOTIFICATIONS；被拒绝只影响可见性。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION -> {
                // 不依赖数组顺序：直接复查实际授权结果。
                if (hasLocationPermission()) {
                    startAndBindService()
                    requestNotificationPermissionIfNeeded()
                } else {
                    statusText.text = "需要定位权限才能启动模拟定位服务"
                }
            }
            REQUEST_NOTIFICATION -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    toast(getString(R.string.permission_notification_hint))
                }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MockLocationService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            statusText.text = "启动模拟定位服务失败：${e.message}"
        }
    }

    override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
        service = (binder as MockLocationService.LocalBinder).getService()
        serviceBound = true
        service?.setCruiseListener(this)

        // 以服务状态为准恢复 UI（Activity 可能被重建过）。
        val s = service
        isCruising = s?.isCruising ?: false
        isMocking = s?.isMocking ?: false
        if (s != null && s.lastLatitude != 0.0) {
            updateCurrentMarker(GeoPoint(s.lastLatitude, s.lastLongitude))
        }
        updateControlState()
        statusText.text = when {
            isCruising -> "巡航中…"
            isMocking -> "模拟定位运行中"
            else -> "服务已连接，可以开始模拟定位"
        }
    }

    override fun onServiceDisconnected(name: android.content.ComponentName?) {
        service = null
        serviceBound = false
        statusText.text = "模拟定位服务已断开"
    }

    // ---------------------------------------------------------------------
    // 单点定位
    // ---------------------------------------------------------------------

    private fun setMockLocation(point: GeoPoint) {
        runAfterRootSetup { mockService ->
            try {
                mockService.setMockLocation(point.latitude, point.longitude)
                isMocking = true
                updateCurrentMarker(point)
                updateControlState()
                statusText.text = "已设置模拟位置（地图坐标）：${formatPoint(point)}"
            } catch (e: MockLocationUnavailableException) {
                isMocking = false
                updateControlState()
                statusText.text = e.message ?: "模拟位置不可用"
            } catch (e: SecurityException) {
                isMocking = false
                updateControlState()
                statusText.text = "没有模拟位置权限：请在开发者选项中把本应用设为模拟位置应用"
            } catch (e: Exception) {
                isMocking = false
                updateControlState()
                statusText.text = "设置模拟位置失败：${e.message}"
            }
        }
    }

    /** 若开启 Root 模式则先配置系统权限，再执行需要模拟定位服务的操作。 */
    private fun runAfterRootSetup(onReady: (MockLocationService) -> Unit) {
        val mockService = service
        if (mockService == null) {
            // 上次「停止模拟」后服务已退出，这里自动重新拉起并绑定。
            startAndBindService()
            statusText.text = "正在启动模拟定位服务，请稍后重试"
            return
        }

        if (rootModeSwitch.isChecked) {
            statusText.text = "Root 模式：正在配置系统模拟位置…"
            Thread {
                val ok = RootHelper.configureMockLocationApp(packageName)
                runOnUiThread {
                    if (!ok) {
                        statusText.text = "Root 配置失败，尝试普通模拟"
                    }
                    onReady(mockService)
                }
            }.start()
        } else {
            onReady(mockService)
        }
    }

    private fun updateCurrentMarker(point: GeoPoint) {
        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                position = point
                title = "当前模拟位置"
                mapView.overlays.add(this)
            }
        } else {
            currentMarker?.position = point
        }
        mapView.invalidate()
    }

    // ---------------------------------------------------------------------
    // 巡航路线
    // ---------------------------------------------------------------------

    private fun addRoutePoint(point: GeoPoint) {
        if (routePoints.isEmpty()) {
            routePoints.add(RoutePoint(point.latitude, point.longitude, 0L))
            refreshRouteMarkers()
            refreshRouteInfo()
            toast("起点已添加，继续长按添加途经点/终点")
            return
        }

        val defaultSeconds = durationInput.text.toString().toLongOrNull() ?: 10L
        showDurationDialog(defaultSeconds) { seconds ->
            routePoints.add(RoutePoint(point.latitude, point.longitude, seconds))
            refreshRouteMarkers()
            refreshRouteInfo()
        }
    }

    private fun showDurationDialog(defaultSeconds: Long, onConfirm: (Long) -> Unit) {
        val input = EditText(this).apply {
            setText(defaultSeconds.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("设置本段通过秒数")
            .setMessage("从上一个点移动到当前点需要多少秒？")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val seconds = input.text.toString().toLongOrNull() ?: defaultSeconds
                onConfirm(seconds.coerceAtLeast(1L))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRouteMarkers() {
        // 移除旧路线点与旧连线。
        routeMarkers.forEach { mapView.overlays.remove(it) }
        routeMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null

        // 添加新路线点标记。
        routePoints.forEachIndexed { index, point ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                title = pointLabel(index)
                mapView.overlays.add(this)
            }
            routeMarkers.add(marker)
        }

        // 添加路线连线。
        if (routePoints.size >= 2) {
            val geoPoints = routePoints.map { GeoPoint(it.latitude, it.longitude) }
            routePolyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = 0xFF1565C0.toInt()
                outlinePaint.strokeWidth = 6f
                mapView.overlays.add(this)
            }
        }

        mapView.invalidate()
    }

    private fun pointLabel(index: Int): String = when (index) {
        0 -> "起点"
        routePoints.lastIndex -> "终点"
        else -> "途经 $index"
    }

    private fun refreshRouteInfo() {
        if (routePoints.isEmpty()) {
            routeInfoText.text = getString(R.string.route_hint)
            return
        }

        val totalSeconds = routePoints.sumOf { it.durationSeconds }
        val builder = StringBuilder("路线：")
        routePoints.forEachIndexed { index, _ ->
            builder.append(pointLabel(index))
            if (index < routePoints.lastIndex) {
                builder.append(" → ${routePoints[index + 1].durationSeconds}s → ")
            }
        }
        builder.append("\n总时长：${totalSeconds} 秒")
        routeInfoText.text = builder.toString()
    }

    private fun startCruise() {
        if (routePoints.size < 2) {
            toast("请先添加至少两个巡航点")
            return
        }

        runAfterRootSetup { mockService ->
            isCruising = true
            isMocking = true
            updateControlState()
            statusText.text = "巡航中…"
            mockService.startCruise(routePoints.toList(), this)
        }
    }

    private fun stopCruise() {
        service?.stopCruise()
        isCruising = false
        updateControlState()
        statusText.text = "巡航已停止，保持最后一次模拟位置"
    }

    /** 停止模拟定位并恢复系统真实定位（同时撤销前台通知、解绑服务）。 */
    private fun stopMocking() {
        service?.stopMocking()
        isMocking = false
        isCruising = false
        if (serviceBound) {
            try {
                unbindService(this)
            } catch (_: IllegalArgumentException) {
                // 已解绑时忽略。
            }
            serviceBound = false
        }
        service = null
        updateControlState()
        statusText.text = "已停止模拟，恢复真实定位"
    }

    private fun clearRoute() {
        stopCruise()
        routePoints.clear()
        routeMarkers.forEach { mapView.overlays.remove(it) }
        routeMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        routeInfoText.text = getString(R.string.route_hint)
        mapView.invalidate()
    }

    // ---------------------------------------------------------------------
    // CruiseListener 回调
    // ---------------------------------------------------------------------

    override fun onCruiseLocation(location: GeoPoint) {
        updateCurrentMarker(location)
        statusText.text = "巡航中：${formatPoint(location)}"
    }

    override fun onCruiseFinished() {
        isCruising = false
        updateControlState()
        statusText.text = "巡航完成，保持最后一次模拟位置"
    }

    override fun onMockError(message: String) {
        isMocking = false
        isCruising = false
        updateControlState()
        statusText.text = message
        toast(message)
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    private fun formatPoint(point: GeoPoint): String {
        return String.format("%.6f, %.6f", point.latitude, point.longitude)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // 地图生命周期
    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDoubleArray(KEY_LATITUDES, routePoints.map { it.latitude }.toDoubleArray())
        outState.putDoubleArray(KEY_LONGITUDES, routePoints.map { it.longitude }.toDoubleArray())
        outState.putLongArray(KEY_DURATIONS, routePoints.map { it.durationSeconds }.toLongArray())
        outState.putString(KEY_MODE, mode.name)
    }

    override fun onDestroy() {
        if (serviceBound) {
            service?.setCruiseListener(null)
            unbindService(this)
            serviceBound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_LOCATION = 100
        private const val REQUEST_NOTIFICATION = 101
        private const val KEY_LATITUDES = "route_latitudes"
        private const val KEY_LONGITUDES = "route_longitudes"
        private const val KEY_DURATIONS = "route_durations"
        private const val KEY_MODE = "ui_mode"
    }
}
