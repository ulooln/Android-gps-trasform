package com.ulooln.gpstransform.root

import java.util.concurrent.TimeUnit

/**
 * Root 模式辅助工具。
 *
 * 在已 root 的设备上，应用可以主动把自身设为系统「模拟位置应用」，
 * 省去用户手动进入开发者选项设置的步骤。
 *
 * 注意：
 * - Android 6 起「模拟位置应用」由 AppOps（android:mock_location）控制，
 *   Settings.Secure 里的 allow_mock_location 已废弃；
 * - ACCESS_MOCK_LOCATION 是 signature 权限，pm grant 必然失败，因此不再执行；
 * - 普通 APK 无法移除 isFromMockProvider 标记，需要 LSPosed/Xposed 等系统级 Hook。
 */
object RootHelper {

    /** 检测当前设备是否可用的 root 权限。调用方必须放到后台线程。 */
    fun isRootAvailable(): Boolean {
        val result = runSu("id", timeoutSeconds = ROOT_CHECK_TIMEOUT_SECONDS)
        return result != null && result.output.contains("uid=0") && result.exitCode == 0
    }

    /**
     * 通过 root 自动把本应用配置为系统「模拟位置应用」。
     *
     * @return 是否成功把 AppOps 的 mock_location 设为 allow（真正的生效条件）
     */
    fun configureMockLocationApp(packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false

        val quoted = shellQuote(packageName)
        // 现代 Android 真正生效的是 AppOps；settings 仅作为旧 ROM 的兜底。
        val appOps = runSu("appops set $quoted android:mock_location allow")
        runSu("settings put secure mock_location_app $quoted")

        // 无论命令退出码如何，都以实际 AppOps 状态为准。
        val check = runSu("appops get $quoted android:mock_location")
        val effective = check != null &&
            check.exitCode == 0 &&
            check.output.contains("mock_location", ignoreCase = true) &&
            check.output.contains("allow", ignoreCase = true)

        return appOps?.exitCode == 0 || effective
    }

    /** 仅允许常规 Android 包名，避免把任意字符串拼进 shell 命令。 */
    private fun isValidPackageName(packageName: String): Boolean {
        return Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class SuResult(val exitCode: Int, val output: String)

    /** 执行 su 命令，带超时；返回 null 表示无法执行。 */
    private fun runSu(command: String, timeoutSeconds: Long = SU_TIMEOUT_SECONDS): SuResult? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            // 先读完输出再等待，避免管道缓冲区写满导致死锁。
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                SuResult(process.exitValue(), output)
            }
        } catch (_: Exception) {
            null
        }
    }

    private const val ROOT_CHECK_TIMEOUT_SECONDS = 5L
    private const val SU_TIMEOUT_SECONDS = 10L
}
