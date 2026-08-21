# Android GPS Transform

一个用 Kotlin 编写的 Android 模拟定位应用。打开后直接显示地图，可以在地图上点选位置立即修改本机模拟定位；也可以进入巡航模式，设置起点、终点、多个途经点以及每段通过时间，让模拟定位按路线移动。

> 模拟定位需要手机开启「开发者选项」中的模拟位置应用权限，并把本应用设为模拟位置应用。部分系统还需要关闭「仅使用真实位置」等限制。

## 功能

- 地图展示：基于 OpenStreetMap 的 osmdroid，无需 API Key。
- 单点定位：单机模式下点击地图，立即把模拟位置设置为点击处。
- 巡航模式：长按地图依次添加起点、途经点、终点；每段可单独设置通过秒数。
- 巡航播放：按路线匀速移动模拟位置，支持开始/停止/清除。
- 前台服务：模拟位置在应用进入后台后仍可持续工作。

## 项目结构

```text
app/src/main/java/com/ulooln/gpstransform/
├── MainActivity.kt              # 界面与地图交互
├── model/RoutePoint.kt          # 路线点数据模型
├── mock/MockLocationProvider.kt # 模拟定位 Provider 封装
├── cruise/CruiseController.kt   # 巡航移动引擎
└── service/MockLocationService.kt # 前台服务，承载模拟定位
```

## 构建

```bash
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
