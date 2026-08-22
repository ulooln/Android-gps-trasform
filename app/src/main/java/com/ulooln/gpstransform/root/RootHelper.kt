package com.ulooln.gpstransform.root

/**
 * Root 模式辅助工具。
 *
 * 在已 root 的设备上，应用可以主动把自身设为系统“模拟位置应用”，
 * 省去用户手动进入开发者选项设置的步骤。
 *
 * 注意：普通 APK 无法通过 root 直接移除系统标记的 isFromMockProvider；
 * 如果需要彻底“无破绽”，仍需配合 LSPosed/Xposed 等系统级 Hook。
 */
object RootHelper {

    /** 检测当前设备是否可用的 root 权限。 */
    fun isRootAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过 root 自动把本应用配置为系统“模拟位置应用”。
     * 返回是否成功完成关键配置。
     */
    fun configureMockLocationApp(packageName: String): Boolean {
        val essentialCommands = listOf(
            "settings put secure mock_location_app $packageName",
            "settings put secure allow_mock_location 1"
        )

        // 附加配置，失败不致命，尽量执行。
        val optionalCommands = listOf(
            "appops set $packageName android:mock_location allow",
            "pm grant $packageName android.permission.ACCESS_MOCK_LOCATION"
        )

        val essentialOk = essentialCommands.all { runSu(it) }
        optionalCommands.forEach { runSu(it) }
        return essentialOk
    }

    private fun runSu(command: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
