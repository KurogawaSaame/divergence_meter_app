package com.zhushui.nixiecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.UUID

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int
)

data class DeviceConfig(
    val timeFormat: String = "24",
    val transitionMode: Int = 0,
    val separatorEffect: Int = 2,
    val autoPower: Boolean = false,
    val autoOn: String = "06:00",
    val autoOff: String = "23:30",
    val cathodeSchedule: Int = 2,
    val timezoneMin: Int = 480,
    val ntpServer: String = "pool.ntp.org",
    val ntpEnabled: Boolean = false,
    val keepWifi: Boolean = false,
    val ssid: String = "",
    val password: String = ""
)

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    /**
     * 多语言文案提供者。默认使用应用上下文所解析到的语言（跟随系统 / 每应用语言设置）。
     * 第一个参数是字符串资源 id，其余参数用于 %1$s、%1$d 这类占位符。
     */
    private val stringProvider: (Int, Array<out Any>) -> String = { resId, args ->
        context.getString(resId, *args)
    }
) {
    companion object {
        val NUS_SERVICE_UUID: UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_UUID: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /** 取一条本地化文案。 */
    private fun text(resId: Int, vararg args: Any): String = stringProvider(resId, args)

    /** 统一的“未知错误”兜底文案。 */
    private fun unknownError(message: String?): String =
        message ?: text(R.string.ble_unknown_error)

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var rxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var notifyBuffer = ""
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private val _devices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val devices: StateFlow<List<BleDeviceItem>> = _devices

    private val _status = MutableStateFlow(text(R.string.ble_status_idle))
    val status: StateFlow<String> = _status

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _worldline = MutableStateFlow("1.048596")
    val worldline: StateFlow<String> = _worldline

    private val _config = MutableStateFlow(DeviceConfig())
    val config: StateFlow<DeviceConfig> = _config

    private val _configLoading = MutableStateFlow(false)
    val configLoading: StateFlow<Boolean> = _configLoading

    private val _configSaving = MutableStateFlow(false)
    val configSaving: StateFlow<Boolean> = _configSaving

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBleScanPermission(): Boolean {
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            true
        }
        return hasScan && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = text(R.string.ble_scan_failed, errorCode)
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val device = result.device
            val address = try {
                device.address ?: return
            } catch (_: SecurityException) {
                return
            }
            val scanName = result.scanRecord?.deviceName
            val deviceName = if (hasBleConnectPermission()) {
                try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
            } else {
                null
            }
            val name = scanName ?: deviceName ?: text(R.string.ble_unknown_device)
            if (_devices.value.any { it.address == address }) return
            _devices.value = _devices.value + BleDeviceItem(name, address, result.rssi)
            _status.value = text(R.string.ble_scanning_count, _devices.value.size)
        } catch (error: Exception) {
            _status.value = text(R.string.ble_scan_found_failed, unknownError(error.message))
        }
    }

    fun startScan() {
        if (!hasBleScanPermission()) {
            _status.value = text(R.string.ble_missing_scan_permission)
            return
        }
        if (!hasBleConnectPermission()) {
            _status.value = text(R.string.ble_missing_connect_permission)
            return
        }
        val btAdapter = adapter
        if (btAdapter == null) {
            _status.value = text(R.string.ble_not_supported)
            return
        }
        if (!btAdapter.isEnabled) {
            _status.value = text(R.string.ble_turn_on)
            return
        }
        val bleScanner = scanner
        if (bleScanner == null) {
            _status.value = text(R.string.ble_scan_unsupported)
            return
        }

        _devices.value = emptyList()
        _status.value = text(R.string.ble_scan_started)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        runCatching { bleScanner.stopScan(scanCallback) }
        try {
            bleScanner.startScan(null, settings, scanCallback)
            handler.postDelayed({ stopScan() }, 30_000L)
        } catch (error: Exception) {
            _status.value = text(R.string.ble_scan_start_failed, unknownError(error.message))
        }
    }

    fun stopScan() {
        runCatching {
            if (hasBleScanPermission()) scanner?.stopScan(scanCallback)
        }
        if (!_connected.value) {
            _status.value = if (_devices.value.isEmpty()) {
                text(R.string.ble_scan_finished_empty)
            } else {
                text(R.string.ble_scan_finished_count, _devices.value.size)
            }
        }
    }

    fun connect(address: String) {
        if (!hasBleConnectPermission()) {
            _status.value = text(R.string.ble_no_connect_permission)
            return
        }
        stopScan()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            _status.value = text(R.string.ble_invalid_address)
            return
        }
        val name = try {
            device.name ?: address
        } catch (_: SecurityException) {
            address
        }
        _status.value = text(R.string.ble_connecting, name)
        try {
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (error: Exception) {
            _status.value = text(R.string.ble_connect_start_failed, unknownError(error.message))
        }
    }

    fun disconnect() {
        runCatching {
            if (hasBleConnectPermission()) {
                gatt?.disconnect()
                gatt?.close()
            }
        }
        gatt = null
        rxCharacteristic = null
        notifyBuffer = ""
        writeQueue.clear()
        writing = false
        _connected.value = false
        _configLoading.value = false
        _configSaving.value = false
        _status.value = text(R.string.ble_disconnected)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasBleConnectPermission()) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _status.value = text(R.string.ble_connected_reading)
                runCatching { gatt.discoverServices() }
                    .onFailure { _status.value = text(R.string.ble_read_services_failed) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                rxCharacteristic = null
                writeQueue.clear()
                writing = false
                _configLoading.value = false
                _configSaving.value = false
                _status.value = text(R.string.ble_disconnected_state)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = text(R.string.ble_discover_failed, status)
                return
            }
            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                _status.value = text(R.string.ble_nus_missing)
                disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(NUS_RX_UUID)
            val tx = service.getCharacteristic(NUS_TX_UUID)
            if (rxCharacteristic == null || tx == null) {
                _status.value = text(R.string.ble_rxtx_incomplete)
                disconnect()
                return
            }
            rxCharacteristic?.let { rx ->
                rxWriteType =
                    if ((rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
            }
            try {
                gatt.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                } else {
                    _connected.value = true
                    _status.value = text(R.string.ble_connected_done)
                }
            } catch (error: Exception) {
                _status.value = text(R.string.ble_notify_failed, unknownError(error.message))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                _connected.value = true
                _status.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                    text(R.string.ble_connected_done)
                } else {
                    text(R.string.ble_connected_notify_failed, status)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NUS_TX_UUID) {
                handleNotifyText(characteristic.value.toString(Charset.forName("UTF-8")))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_UUID) {
                handleNotifyText(value.toString(Charset.forName("UTF-8")))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != NUS_RX_UUID) return
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = text(R.string.ble_write_callback_failed, status)
            }
            writeNextChunk()
        }
    }

    private fun handleNotifyText(chunk: String) {
        notifyBuffer += chunk
        if (chunk.trim().isNotEmpty()) {
            _status.value = text(R.string.ble_data_received, chunk.trim().take(40))
        }

        val lines = notifyBuffer.split("\n")
        notifyBuffer = lines.lastOrNull().orEmpty()
        lines.dropLast(1).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            runCatching {
                val obj = JSONObject(line)
                val cmd = obj.optString("cmd")
                val ok = obj.optBoolean("ok", false)
                val data = obj.optJSONObject("data")

                data?.optString("worldline")?.takeIf { it.isNotEmpty() }?.let {
                    _worldline.value = it
                }

                if (cmd == "get_config") {
                    _configLoading.value = false
                    if (ok && data != null) {
                        _config.value = DeviceConfig(
                            timeFormat = data.optString("time_format", "24"),
                            transitionMode = data.optInt("transition_mode", 0),
                            separatorEffect = data.optInt("separator_effect", 2),
                            autoPower = data.optBoolean("auto_power", false),
                            autoOn = data.optString("auto_on", "06:00"),
                            autoOff = data.optString("auto_off", "23:30"),
                            cathodeSchedule = data.optInt("cathode_schedule", 2),
                            timezoneMin = data.optInt("timezone_min", 480),
                            ntpServer = data.optString("ntp_server", "pool.ntp.org"),
                            ntpEnabled = data.optBoolean("ntp_enabled", false),
                            keepWifi = data.optBoolean("keep_wifi", false),
                            ssid = data.optString("ssid", ""),
                            password = ""
                        )
                        _status.value = text(R.string.ble_config_loaded)
                    } else {
                        _status.value = text(R.string.ble_config_load_failed)
                    }
                }

                if (cmd == "set_config") {
                    _configSaving.value = false
                    _status.value = if (ok) {
                        text(R.string.ble_config_saved)
                    } else {
                        text(R.string.ble_config_save_failed)
                    }
                }
            }
        }
    }

    fun sendJson(obj: JSONObject) {
        val characteristic = rxCharacteristic
        val currentGatt = gatt
        if (characteristic == null || currentGatt == null) {
            _status.value = text(R.string.ble_not_connected)
            return
        }
        if (!hasBleConnectPermission()) {
            _status.value = text(R.string.ble_no_connect_permission)
            return
        }
        val payload = obj.toString() + "\n"
        _status.value = text(R.string.ble_sending, payload.trim().take(80))
        enqueueWrite(payload.toByteArray(Charsets.UTF_8))
    }

    private fun enqueueWrite(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 20, bytes.size)
            writeQueue.add(bytes.copyOfRange(offset, end))
            offset = end
        }
        writeNextChunk()
    }

    private fun writeNextChunk() {
        if (writing) return
        val currentGatt = gatt
        val characteristic = rxCharacteristic
        if (currentGatt == null || characteristic == null) {
            writeQueue.clear()
            writing = false
            return
        }
        val chunk = writeQueue.poll() ?: return
        try {
            characteristic.writeType = rxWriteType
            characteristic.value = chunk
            writing = true
            val started = currentGatt.writeCharacteristic(characteristic)
            if (!started) {
                writing = false
                handler.postDelayed({ writeNextChunk() }, 60L)
            }
            if (rxWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                writing = false
                handler.postDelayed({ writeNextChunk() }, 50L)
            }
        } catch (error: Exception) {
            writing = false
            _status.value = text(R.string.ble_write_failed, unknownError(error.message))
        }
    }

    fun worldlineStart() {
        _worldline.value = text(R.string.worldline_deducing)
        sendJson(JSONObject().put("cmd", "worldline2_start"))
    }

    fun worldlineStop() {
        _worldline.value = text(R.string.worldline_locking)
        sendJson(JSONObject().put("cmd", "worldline2_stop"))
    }

    fun setMode(mode: String) {
        sendJson(
            JSONObject()
                .put("cmd", "set_mode")
                .put("data", JSONObject().put("mode", mode))
        )
    }

    fun tubePower(on: Boolean) {
        sendJson(
            JSONObject()
                .put("cmd", "tube_power")
                .put("data", JSONObject().put("on", on))
        )
    }

    fun setBrightness(auto: Boolean, value: Int) {
        sendJson(
            JSONObject()
                .put("cmd", "set_brightness")
                .put(
                    "data",
                    JSONObject()
                        .put("mode", if (auto) "auto" else "manual")
                        .put("value", value)
                )
        )
    }

    fun syncPhoneTime() {
        sendJson(
            JSONObject()
                .put("cmd", "sync_time")
                .put("data", JSONObject().put("timestamp", System.currentTimeMillis() / 1000))
        )
    }

    fun setCustom(value: String) {
        sendJson(
            JSONObject()
                .put("cmd", "set_custom")
                .put("data", JSONObject().put("digits", value))
        )
        handler.postDelayed({ setMode("custom") }, 250L)
    }

    fun startCathodeCycle() {
        setMode("cathode")
    }

    fun loadConfig() {
        _configLoading.value = true
        sendJson(JSONObject().put("cmd", "get_config"))
        handler.postDelayed({
            if (_configLoading.value) {
                _configLoading.value = false
                _status.value = text(R.string.ble_config_load_timeout)
            }
        }, 4_500L)
    }

    fun saveConfig(config: DeviceConfig) {
        _configSaving.value = true
        val data = JSONObject()
            .put("time_format", if (config.timeFormat == "12") "12" else "24")
            .put("transition_mode", config.transitionMode.coerceIn(0, 4))
            .put("separator_effect", config.separatorEffect.coerceIn(0, 2))
            .put("auto_power", config.autoPower)
            .put("auto_on", config.autoOn)
            .put("auto_off", config.autoOff)
            .put("cathode_schedule", config.cathodeSchedule.coerceIn(0, 2))
            .put("timezone_min", config.timezoneMin.coerceIn(-720, 840))
            .put("ntp_server", config.ntpServer.ifBlank { "pool.ntp.org" })
            .put("ntp_enabled", config.ntpEnabled)
            .put("keep_wifi", config.keepWifi)
            .put("ssid", config.ssid)

        if (config.password.isNotBlank()) {
            data.put("password", config.password)
        }

        sendJson(
            JSONObject()
                .put("cmd", "set_config")
                .put("data", data)
        )
        handler.postDelayed({
            if (_configSaving.value) {
                _configSaving.value = false
                _status.value = text(R.string.ble_config_save_timeout)
            }
        }, 6_500L)
    }
}
