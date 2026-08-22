package com.ulooln.gpstransform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid 需要先加载配置。
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        setContentView(R.layout.activity_main)

        bindViews()
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
        rootModeSwitch = findViewById(R.id.rootModeSwitch)
        rootStatusText = findViewById(R.id.rootStatusText)
    }


    /** 创建国内可访问的高德地图瓦片源。 */
    private fun createChinaTileSource(): OnlineTileSourceBase {
        return AmapTileSource()
    }

    private fun setupMap() {
        // 使用高德瓦片源：OpenStreetMap 在国内经常无法访问，会导致只显示灰色网格。
        Configuration.getInstance().userAgentValue = packageName ?: "osmdroid"
        mapView.setTileSource(createChinaTileSource())
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
        stopCruiseButton.isEnabled = false

        findViewById<Button>(R.id.clearButton).setOnClickListener { clearRoute() }

        rootModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!RootHelper.isRootAvailable()) {
                    toast("未检测到 root 权限，Root 模式不可用")
                    rootModeSwitch.isChecked = false
                    rootStatusText.text = "未检测到 root"
                } else {
                    rootStatusText.text = "已开启，设置位置时自动配置系统权限"
                }
            } else {
                rootStatusText.text = "未开启"
            }
        }

        updateModeUi()
    }

    private fun updateModeUi() {
        singleModeButton.isSelected = mode == Mode.SINGLE
        cruiseModeButton.isSelected = mode == Mode.CRUISE
    }

    // ---------------------------------------------------------------------
    // 权限与前台服务
    // ---------------------------------------------------------------------

    private fun requestLocationAndStartService() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
        } else {
            startAndBindService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAndBindService()
            } else {
                statusText.text = "需要定位权限才能启动模拟定位服务"
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MockLocationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
        service = (binder as MockLocationService.LocalBinder).getService()
        serviceBound = true
        service?.setCruiseListener(this)
        if (isCruising) {
            statusText.text = "巡航中…"
        } else {
            statusText.text = "服务已连接，可以开始模拟定位"
        }
    }

    override fun onServiceDisconnected(name: android.content.ComponentName?) {
        service = null
        serviceBound = false
    }

    // ---------------------------------------------------------------------
    // 单点定位
    // ---------------------------------------------------------------------

    private fun setMockLocation(point: GeoPoint) {
        val mockService = service
        if (mockService == null) {
            statusText.text = "模拟定位服务尚未连接，请稍候"
            return
        }

        if (rootModeSwitch.isChecked) {
            statusText.text = "Root 模式：正在配置系统模拟位置…"
            Thread {
                val ok = RootHelper.configureMockLocationApp(packageName ?: "com.ulooln.gpstransform")
                runOnUiThread {
                    if (!ok) {
                        statusText.text = "Root 配置失败，尝试普通模拟"
                    }
                    performSetMockLocation(point, mockService)
                }
            }.start()
        } else {
            performSetMockLocation(point, mockService)
        }
    }

    private fun performSetMockLocation(point: GeoPoint, mockService: MockLocationService) {
        try {
            mockService.setMockLocation(point.latitude, point.longitude)
            updateCurrentMarker(point)
            statusText.text = "已设置模拟位置：${formatPoint(point)}"
        } catch (e: SecurityException) {
            statusText.text = "没有模拟位置权限：请在开发者选项中把本应用设为模拟位置应用"
        } catch (e: Exception) {
            statusText.text = "设置模拟位置失败：${e.message}"
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
            val title = when (index) {
                0 -> "起点"
                routePoints.lastIndex -> "终点"
                else -> "途经 $index"
            }
            val marker = Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                this.title = title
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

    private fun refreshRouteInfo() {
        if (routePoints.isEmpty()) {
            routeInfoText.text = getString(R.string.route_hint)
            return
        }

        val totalSeconds = routePoints.sumOf { it.durationSeconds }
        val builder = StringBuilder("路线：")
        routePoints.forEachIndexed { index, _ ->
            val label = when (index) {
                0 -> "起点"
                routePoints.lastIndex -> "终点"
                else -> "途经${index}"
            }
            builder.append(label)
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

        val mockService = service
        if (mockService == null) {
            statusText.text = "模拟定位服务尚未连接，请稍候"
            return
        }

        if (rootModeSwitch.isChecked) {
            statusText.text = "Root 模式：正在配置系统模拟位置…"
            Thread {
                val ok = RootHelper.configureMockLocationApp(packageName ?: "com.ulooln.gpstransform")
                runOnUiThread {
                    if (!ok) {
                        statusText.text = "Root 配置失败，尝试普通巡航"
                    }
                    performStartCruise(mockService)
                }
            }.start()
        } else {
            performStartCruise(mockService)
        }
    }

    private fun performStartCruise(mockService: MockLocationService) {
        isCruising = true
        startCruiseButton.isEnabled = false
        stopCruiseButton.isEnabled = true
        statusText.text = "巡航中…"

        mockService.startCruise(routePoints.toList(), this)
    }

    private fun stopCruise() {
        service?.stopCruise()
        isCruising = false
        startCruiseButton.isEnabled = true
        stopCruiseButton.isEnabled = false
        statusText.text = "巡航已停止"
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
        startCruiseButton.isEnabled = true
        stopCruiseButton.isEnabled = false
        statusText.text = "巡航完成"
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
    }
}
