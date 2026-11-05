package com.ulooln.gpstransform.util

/**
 * 坐标系转换工具。
 *
 * 高德/腾讯等国内地图瓦片使用 GCJ-02（火星坐标），而 Android 系统 Location
 * 以及绝大多数 App 使用 WGS-84。地图选点得到的是 GCJ-02 坐标，如果直接注入
 * 系统 Location，国内 App 显示时还会再做一次 WGS-84 → GCJ-02 转换，结果会
 * 偏移数百米。因此注入前必须先转成 WGS-84。
 */
object CoordinateConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** GCJ-02 → WGS-84（迭代反算，3 次迭代即可收敛到厘米级）。 */
    fun gcj02ToWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (outOfChina(latitude, longitude)) return latitude to longitude

        var wgsLat = latitude
        var wgsLon = longitude
        repeat(3) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLon)
            wgsLat -= gcj.first - latitude
            wgsLon -= gcj.second - longitude
        }
        return wgsLat to wgsLon
    }

    /** WGS-84 → GCJ-02（标准公开算法）。 */
    fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (outOfChina(latitude, longitude)) return latitude to longitude

        var dLat = transformLatitude(longitude - 105.0, latitude - 35.0)
        var dLon = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
        return (latitude + dLat) to (longitude + dLon)
    }

    /** 中国境外不做偏移。 */
    fun outOfChina(latitude: Double, longitude: Double): Boolean {
        return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
