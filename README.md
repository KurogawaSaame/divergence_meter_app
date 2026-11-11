# NIXIE Control — 世界线变动率探测仪 安卓控制端

Divergence Meter 辉光管时钟的 Android 控制应用，界面为**日文**，通过蓝牙低功耗（BLE）与 ESP32 主控通信。

## 功能

- 扫描并连接设备（BLE 名称 `SC-WORLDLINE`）
- 实时显示世界线变动率
- 推演控制：开始推演 / 停止锁定
- 世界线模式 / 时钟模式切换
- 辉光管总电源开关
- 数字循环（防阴极中毒）
- 手机时间同步
- 亮度调节（自动 / 手动 10%～100%）
- 自定义显示（保存为设备预设）
- 显示设置：12/24 小时制、过渡效果（5 档）、分隔符效果（3 档）
- 自动电源：开机 / 关机时刻
- 网络与 NTP：WiFi SSID / 密码、NTP 服务器、时区

## 技术栈

| 项目 | 说明 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 最低版本 | Android 8.0（API 26）|
| 目标版本 | API 36 |
| 构建 | Gradle 8.13 / AGP 8.13 / JDK 17 |

## 通信协议

使用 Nordic UART Service（NUS）风格的自定义 GATT：

| 用途 | UUID |
|---|---|
| 服务 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| 写入（RX）| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| 通知（TX）| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

指令为**单行 JSON，以换行符 `\n` 结尾**，按 20 字节分片写入。示例：

```json
{"cmd":"sync_time","data":{"timestamp":1761904800}}
{"cmd":"set_custom","data":{"digits":"1.048596"}}
{"cmd":"set_brightness","data":{"mode":"manual","value":178}}
```

## 构建

```bash
# 需要 JDK 17 与 Android SDK
# 在项目根目录创建 local.properties 并写入 SDK 路径：
#   sdk.dir=C\:\\Android\\Sdk

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` 含有本机 SDK 路径，已在 `.gitignore` 中排除，不会入库。

## 说明

- 本应用仅包含控制端，设备固件不在此仓库。
- 设备另提供局域网 HTTP 接口（`/api/state`、`/api/mode?value=` 等），可脱离手机用电脑控制。
- 项目暂未声明开源许可证。