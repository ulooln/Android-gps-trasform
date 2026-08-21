package com.ulooln.gpstransform.model

/**
 * 巡航路线中的一个点。
 *
 * @param latitude 纬度
 * @param longitude 经度
 * @param durationSeconds 从上一个点移动到当前点所需秒数；起点为 0
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val durationSeconds: Long = 0L
)
