package com.ulooln.gpstransform.cruise

import android.os.SystemClock
import com.ulooln.gpstransform.mock.MockLocationProvider
import com.ulooln.gpstransform.model.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * 巡航移动引擎。
 *
 * 按路线点顺序，在每一段的通过时间内匀速插值，不断更新模拟位置。
 */
class CruiseController(
    private val mockProvider: MockLocationProvider,
    private val scope: CoroutineScope
) {

    private var job: Job? = null

    /** 每步更新间隔（毫秒），越小轨迹越平滑。 */
    private val stepMillis = 500L

    /**
     * 开始巡航。
     *
     * @param points 路线点，至少 2 个。
     * @param onTick 每次位置更新时回调当前坐标。
     * @param onFinished 全部路线走完时回调。
     */
    fun start(
        points: List<RoutePoint>,
        onTick: (GeoPoint) -> Unit,
        onFinished: () -> Unit
    ) {
        stop()
        if (points.size < 2) return

        job = scope.launch {
            try {
                mockProvider.enable()

                for (index in 0 until points.size - 1) {
                    val from = points[index]
                    val to = points[index + 1]

                    // 每段至少 1 秒，避免除零或瞬间跳变。
                    val durationMs = to.durationSeconds.coerceAtLeast(1L) * 1000L
                    val segmentStart = SystemClock.elapsedRealtime()

                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - segmentStart
                        if (elapsed >= durationMs) break

                        val fraction = elapsed.toDouble() / durationMs.toDouble()
                        val latitude = from.latitude + (to.latitude - from.latitude) * fraction
                        val longitude = from.longitude + (to.longitude - from.longitude) * fraction

                        mockProvider.update(latitude, longitude)
                        onTick(GeoPoint(latitude, longitude))
                        delay(stepMillis)
                    }
                }

                // 确保最终停在终点。
                val last = points.last()
                mockProvider.update(last.latitude, last.longitude)
                onTick(GeoPoint(last.latitude, last.longitude))
                onFinished()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 权限不足或 Provider 被系统移除时，停止巡航并让上层提示。
                onFinished()
            }
        }
    }

    /** 停止当前巡航。 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
