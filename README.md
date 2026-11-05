# Android GPS Transform

一个用 Kotlin 编写的 Android 模拟定位应用。打开后直接显示地图，可以在地图上点选位置立即修改本机模拟定位；也可以进入巡航模式，设置起点、终点、多个途经点以及每段通过时间，让模拟定位按路线移动。

> 模拟定位需要手机开启「开发者选项」中的模拟位置应用权限，并把本应用设为模拟位置应用。部分系统还需要关闭「仅使用真实位置」等限制。
> 应用会同时模拟 GPS、Network、Passive 定位源，并以 1Hz 持续发送模拟位置，尽量让高德、百度、滴滴等 App 读到模拟位置。
> 已内置 Root 模式：在已 root 设备上可自动把本应用设为系统“模拟位置应用”，省去手动开发者选项配置。
> **坐标系**：地图使用高德瓦片（GCJ-02），选点后会自动转换为 WGS-84 再注入系统，避免国内 App 显示偏移。
> **停止模拟**：界面底部「停止模拟（恢复真实定位）」或通知栏的「停止模拟」按钮会注销测试 Provider 并退出前台服务。

## 功能

- 地图展示：基于 osmdroid + 高德瓦片源（国内可直接访问），无需 API Key。
- 单点定位：单点模式下点击地图，立即把模拟位置设置为点击处（GCJ-02 → WGS-84 自动转换）。
- 巡航模式：长按地图依次添加起点、途经点、终点；每段可单独设置通过秒数。
- 巡航播放：按路线匀速移动模拟位置，支持开始/停止/清除；巡航结束后继续保持 1Hz 脉冲。
- 前台服务：模拟位置在应用进入后台后仍可持续工作；通知栏显示当前位置并提供「停止模拟」按钮。
- 状态可恢复：旋转/重建后路线与模式保留，UI 以服务状态为准，不会出现“巡航中却无法停止”。
- Root 模式：检测 root 权限并自动配置系统模拟位置应用（AppOps）。

## 项目结构

```text
app/src/main/java/com/ulooln/gpstransform/
├── MainActivity.kt              # 界面与地图交互、权限与状态恢复
├── model/RoutePoint.kt          # 路线点数据模型
├── mock/MockLocationProvider.kt # 模拟定位 Provider 封装（含 Provider 状态、坐标转换、失败上报）
├── cruise/CruiseController.kt   # 巡航移动引擎（区分完成/异常）
├── root/RootHelper.kt           # Root 配置（AppOps，带超时与包名校验）
├── util/CoordinateConverter.kt  # GCJ-02 ↔ WGS-84 坐标转换
└── service/MockLocationService.kt # 前台服务，承载模拟定位与停止入口
```

## 构建

```bash
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

要求：JDK 17+（Gradle 8.9 支持的最高 Java 版本为 22，若使用 JDK 23+ 请升级 Gradle wrapper）、Android SDK Platform 34 + Build-Tools 34。
