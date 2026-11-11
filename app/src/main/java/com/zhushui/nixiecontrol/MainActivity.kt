package com.zhushui.nixiecontrol

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhushui.nixiecontrol.ui.theme.NIXIEControlTheme

private val BgTop = Color(0xFF020807)
private val BgBottom = Color(0xFF000302)
private val Panel = Color(0xFF061411)
private val Panel2 = Color(0xFF091C18)
private val Vfd = Color(0xFF6CCFC0)
private val VfdSoft = Color(0xFF3F9F93)
private val VfdDim = Color(0xFF1B5F58)
private val VfdGlow = Color(0x336CCFC0)
private val TextMain = Color(0xFFD8FFF7)
private val TextSub = Color(0xFF7FAEA5)
private val Danger = Color(0xFFFF8A8A)

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // BleManager checks permissions again before scanning or connecting.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)
        requestBlePermissions()
        enableEdgeToEdge()

        setContent {
            NIXIEControlTheme {
                SteinsCoreApp(bleManager)
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@Composable
fun SteinsCoreApp(ble: BleManager) {
    val devices by ble.devices.collectAsState()
    val status by ble.status.collectAsState()
    val connected by ble.connected.collectAsState()
    val worldline by ble.worldline.collectAsState()
    var page by remember { mutableStateOf("control") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(top = 46.dp, bottom = 18.dp)
        ) {
            Header(status = status, connected = connected)
            Spacer(modifier = Modifier.height(16.dp))

            if (!connected) {
                DeviceScanPage(ble = ble, devices = devices)
            } else {
                TopTabs(
                    page = page,
                    onPageChange = {
                        page = it
                        if (it == "settings") ble.loadConfig()
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))

                when (page) {
                    "control" -> ControlPage(ble = ble, worldline = worldline)
                    "settings" -> SettingsPage(ble = ble)
                }
            }
        }
    }
}

@Composable
fun Header(status: String, connected: Boolean) {
    GlowPanel {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.header_title),
                color = Vfd,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.header_subtitle),
                color = TextSub,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (connected) R.string.state_connected else R.string.state_disconnected
                ),
                color = if (connected) Vfd else Danger,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = status, color = TextSub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TopTabs(page: String, onPageChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        NeonTabButton(
            text = stringResource(R.string.tab_control),
            selected = page == "control",
            modifier = Modifier.weight(1f),
            onClick = { onPageChange("control") }
        )
        Spacer(modifier = Modifier.width(10.dp))
        NeonTabButton(
            text = stringResource(R.string.tab_settings),
            selected = page == "settings",
            modifier = Modifier.weight(1f),
            onClick = { onPageChange("settings") }
        )
    }
}

@Composable
fun DeviceScanPage(ble: BleManager, devices: List<BleDeviceItem>) {
    Column {
        OutlineGlowButton(
            text = stringResource(R.string.scan_start),
            modifier = Modifier.fillMaxWidth(),
            onClick = { ble.startScan() }
        )
        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn {
            items(devices) { device ->
                GlowPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    onClick = { ble.connect(device.address) }
                ) {
                    Column(modifier = Modifier.padding(15.dp)) {
                        Text(
                            text = device.name,
                            color = TextMain,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(device.address, color = TextSub, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(5.dp))
                        Text("RSSI: ${device.rssi}", color = VfdSoft, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ControlPage(ble: BleManager, worldline: String) {
    var tubeOn by remember { mutableStateOf(true) }
    var autoBrightness by remember { mutableStateOf(false) }
    var brightnessPercent by remember { mutableStateOf(70f) }
    var customText by remember { mutableStateOf("1.048596") }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            GlowPanel {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.worldline_rate_label),
                        color = TextSub,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = worldline,
                        color = Vfd,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row {
                OutlineGlowButton(
                    text = stringResource(R.string.worldline_start),
                    modifier = Modifier.weight(1f),
                    onClick = { ble.worldlineStart() }
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlineGlowButton(
                    text = stringResource(R.string.worldline_stop),
                    modifier = Modifier.weight(1f),
                    onClick = { ble.worldlineStop() }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row {
                OutlineGlowButton(
                    text = stringResource(R.string.mode_worldline),
                    modifier = Modifier.weight(1f),
                    onClick = { ble.setMode("worldline") }
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlineGlowButton(
                    text = stringResource(R.string.mode_time),
                    modifier = Modifier.weight(1f),
                    onClick = { ble.setMode("time") }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            OutlineGlowButton(
                text = stringResource(if (tubeOn) R.string.tube_off else R.string.tube_on),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    tubeOn = !tubeOn
                    ble.tubePower(tubeOn)
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlineGlowButton(
                text = stringResource(R.string.cathode_cycle_start),
                modifier = Modifier.fillMaxWidth(),
                onClick = { ble.startCathodeCycle() }
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlineGlowButton(
                text = stringResource(R.string.sync_phone_time),
                modifier = Modifier.fillMaxWidth(),
                onClick = { ble.syncPhoneTime() }
            )
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_brightness)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.auto_brightness),
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoBrightness,
                        onCheckedChange = {
                            autoBrightness = it
                            ble.setBrightness(it, percentToBrightness(brightnessPercent.toInt()))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Vfd,
                            checkedTrackColor = Color(0x332BA89A),
                            uncheckedThumbColor = TextSub,
                            uncheckedTrackColor = Panel2
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.brightness_percent, brightnessPercent.toInt()),
                    color = TextSub
                )
                Slider(
                    value = brightnessPercent,
                    onValueChange = { brightnessPercent = it },
                    onValueChangeFinished = {
                        if (!autoBrightness) {
                            ble.setBrightness(false, percentToBrightness(brightnessPercent.toInt()))
                        }
                    },
                    valueRange = 10f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Vfd,
                        activeTrackColor = VfdSoft,
                        inactiveTrackColor = VfdDim
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_custom_display)) {
                VfdTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = stringResource(R.string.custom_digits_label)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlineGlowButton(
                    text = stringResource(R.string.custom_display_send),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        customText.trim().takeIf { it.isNotEmpty() }?.let(ble::setCustom)
                    }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { ble.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFFFFBBBB)
                ),
                border = BorderStroke(1.dp, Color(0xFF7A2A2A)),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Text(stringResource(R.string.disconnect))
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsPage(ble: BleManager) {
    val config by ble.config.collectAsState()
    val loading by ble.configLoading.collectAsState()
    val saving by ble.configSaving.collectAsState()

    var form by remember { mutableStateOf(config) }

    LaunchedEffect(config) {
        form = config.copy(password = "")
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row {
                OutlineGlowButton(
                    text = stringResource(
                        if (loading) R.string.config_loading else R.string.config_load
                    ),
                    modifier = Modifier.weight(1f),
                    selected = loading,
                    onClick = { ble.loadConfig() }
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlineGlowButton(
                    text = stringResource(
                        if (saving) R.string.config_saving else R.string.config_save
                    ),
                    modifier = Modifier.weight(1f),
                    selected = saving,
                    onClick = { ble.saveConfig(form) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_display)) {
                CycleRow(
                    title = stringResource(R.string.time_format),
                    value = stringResource(
                        if (form.timeFormat == "12") R.string.time_format_12
                        else R.string.time_format_24
                    ),
                    onClick = { form = form.copy(timeFormat = if (form.timeFormat == "12") "24" else "12") }
                )
                CycleRow(
                    title = stringResource(R.string.transition_mode),
                    value = transitionName(form.transitionMode),
                    onClick = { form = form.copy(transitionMode = (form.transitionMode + 1) % 5) }
                )
                CycleRow(
                    title = stringResource(R.string.separator),
                    value = separatorName(form.separatorEffect),
                    onClick = { form = form.copy(separatorEffect = (form.separatorEffect + 1) % 3) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_auto_power)) {
                SwitchRow(
                    title = stringResource(R.string.auto_power_enable),
                    checked = form.autoPower,
                    onCheckedChange = { form = form.copy(autoPower = it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                VfdTextField(
                    value = form.autoOn,
                    onValueChange = { form = form.copy(autoOn = it) },
                    label = stringResource(R.string.auto_on_label)
                )
                Spacer(modifier = Modifier.height(8.dp))
                VfdTextField(
                    value = form.autoOff,
                    onValueChange = { form = form.copy(autoOff = it) },
                    label = stringResource(R.string.auto_off_label)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_cathode)) {
                CycleRow(
                    title = stringResource(R.string.cathode_schedule),
                    value = cathodeName(form.cathodeSchedule),
                    onClick = { form = form.copy(cathodeSchedule = (form.cathodeSchedule + 1) % 3) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard(title = stringResource(R.string.card_network)) {
                VfdTextField(
                    value = form.timezoneMin.toString(),
                    onValueChange = { form = form.copy(timezoneMin = it.toIntOrNull() ?: form.timezoneMin) },
                    label = stringResource(R.string.timezone_label),
                    keyboardType = KeyboardType.Number
                )
                Spacer(modifier = Modifier.height(8.dp))
                VfdTextField(
                    value = form.ntpServer,
                    onValueChange = { form = form.copy(ntpServer = it) },
                    label = stringResource(R.string.ntp_server_label)
                )
                Spacer(modifier = Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(R.string.ntp_enable),
                    checked = form.ntpEnabled,
                    onCheckedChange = { form = form.copy(ntpEnabled = it) }
                )
                SwitchRow(
                    title = stringResource(R.string.keep_wifi),
                    checked = form.keepWifi,
                    onCheckedChange = { form = form.copy(keepWifi = it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                VfdTextField(
                    value = form.ssid,
                    onValueChange = { form = form.copy(ssid = it) },
                    label = stringResource(R.string.ssid_label)
                )
                Spacer(modifier = Modifier.height(8.dp))
                VfdTextField(
                    value = form.password,
                    onValueChange = { form = form.copy(password = it) },
                    label = stringResource(R.string.wifi_password_label)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun GlowPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val panelModifier = modifier
        .shadow(10.dp, shape, ambientColor = VfdGlow, spotColor = VfdGlow)
        .border(1.dp, VfdDim, shape)

    if (onClick != null) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = shape,
            modifier = panelModifier
        ) { Column(content = content) }
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = shape,
            modifier = panelModifier
        ) { Column(content = content) }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlowPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Vfd, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun CycleRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextMain)
            Text(value, color = TextSub, style = MaterialTheme.typography.bodySmall)
        }
        OutlineGlowButton(text = stringResource(R.string.cycle_switch), onClick = onClick)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextMain, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Vfd,
                checkedTrackColor = Color(0x332BA89A),
                uncheckedThumbColor = TextSub,
                uncheckedTrackColor = Panel2
            )
        )
    }
}

@Composable
fun VfdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSub) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = TextMain),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Vfd,
            unfocusedBorderColor = VfdDim,
            focusedLabelColor = Vfd,
            unfocusedLabelColor = TextSub,
            cursorColor = Vfd,
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        )
    )
}

@Composable
fun OutlineGlowButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Vfd else VfdDim
    val contentColor = if (selected) Vfd else TextMain
    val bgColor = if (selected) Color(0x221B5F58) else Color.Transparent

    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 44.dp)
            .shadow(
                elevation = if (selected) 8.dp else 3.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = if (selected) VfdGlow else Color.Transparent,
                spotColor = if (selected) VfdGlow else Color.Transparent
            ),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = contentColor),
        border = BorderStroke(if (selected) 1.4.dp else 1.dp, borderColor),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun NeonTabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlineGlowButton(
        text = text,
        selected = selected,
        modifier = modifier.height(46.dp),
        onClick = onClick
    )
}

fun percentToBrightness(percent: Int): Int {
    val p = percent.coerceIn(10, 100)
    return (p / 100.0 * 255).toInt()
}

@Composable
fun transitionName(value: Int): String = when (value) {
    0 -> stringResource(R.string.transition_fade)
    1 -> stringResource(R.string.transition_jump)
    2 -> stringResource(R.string.transition_blink)
    3 -> stringResource(R.string.transition_direct)
    4 -> stringResource(R.string.transition_crossfade)
    else -> stringResource(R.string.transition_fade)
}

@Composable
fun separatorName(value: Int): String = when (value) {
    0 -> stringResource(R.string.separator_always_on)
    1 -> stringResource(R.string.separator_off)
    2 -> stringResource(R.string.separator_breathing)
    else -> stringResource(R.string.separator_breathing)
}

@Composable
fun cathodeName(value: Int): String = when (value) {
    0 -> stringResource(R.string.cathode_off)
    1 -> stringResource(R.string.cathode_night_hourly)
    2 -> stringResource(R.string.cathode_night_day)
    else -> stringResource(R.string.cathode_night_day)
}
