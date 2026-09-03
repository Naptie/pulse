package me.naptie.pulse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import me.naptie.pulse.DeviceInfo
import me.naptie.pulse.HrPoint
import me.naptie.pulse.PulseEngine
import me.naptie.pulse.PulseListener
import me.naptie.pulse.Spo2Point
import me.naptie.pulse.UiStrings
import me.naptie.pulse.VitalPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFFFF375F)
private val SpoAccent = Color(0xFF4CC9B0)
private val BgTop = Color(0xFF0B0B14)
private val BgBottom = Color(0xFF1B1B35)
private val CardTint = Color(0xFF161624)
private val NavBarColor = Color(0xFF0E0E1A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PulseRoot()
            }
        }
    }
}

@Composable
fun PulseRoot() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var bpm by remember { mutableIntStateOf(0) }
    var spo2 by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf("idle") }
    var detail by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<HrPoint>>(emptyList()) }
    var spo2History by remember { mutableStateOf<List<Spo2Point>>(emptyList()) }
    var lastDeviceName by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    var engineRef: PulseEngine? by remember { mutableStateOf(null) }

    val listener = remember {
        object : PulseListener {
            override fun onDevicesChanged(newDevices: List<DeviceInfo>) { devices = newDevices }
            override fun onHeartRate(newBpm: Int) { bpm = newBpm }
            override fun onBloodOxygen(newSpo2: Int) { spo2 = newSpo2 }
            override fun onStateChanged(newState: String, newDetail: String) {
                state = newState
                detail = newDetail
                engineRef?.savedDeviceName()?.takeIf { it.isNotEmpty() }?.let { lastDeviceName = it }
            }
            override fun onHistory(newHistory: List<HrPoint>) { history = newHistory }
            override fun onSpo2History(newHistory: List<Spo2Point>) { spo2History = newHistory }
        }
    }

    val engine = remember {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val isEmulator = fp.startsWith("generic") || fp.contains("emulator") || model.contains("sdk_gphone")
        PulseEngine(listener, mock = isEmulator)
    }
    LaunchedEffect(Unit) {
        engineRef = engine
        engine.initialize(context.applicationContext)
        lastDeviceName = engine.savedDeviceName()
    }
    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    val permState = remember { mutableStateOf<Boolean?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permState.value = true
    }
    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.BLUETOOTH)
            needed.add(Manifest.permission.BLUETOOTH_ADMIN)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val granted = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (granted.isNotEmpty()) permLauncher.launch(granted.toTypedArray()) else permState.value = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = NavBarColor,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = UiStrings.tabDevices) },
                    label = { Text(UiStrings.tabDevices, fontSize = 12.sp) },
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = UiStrings.tabVitals) },
                    label = { Text(UiStrings.tabVitals, fontSize = 12.sp) },
                    colors = navColors()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            when (tab) {
                0 -> DevicesScreen(
                    devices = remember(devices) {
                        devices.sortedWith(
                             compareByDescending<DeviceInfo> { it.hr || it.spo2 }
                                .thenByDescending { it.rssi / 3 }
                                .thenBy { it.id }
                        )
                    },
                    state = state,
                    onScan = {
                        if (permState.value == true) {
                            engine.stopScan()
                            engine.startScan()
                        }
                    },
                    onPick = { d ->
                        engine.connect(d!!.id)
                        tab = 1
                    }
                )
                1 -> MonitorScreen(
                    bpm = bpm, spo2 = spo2, state = state, detail = detail,
                    history = history, spo2History = spo2History,
                    lastDeviceName = lastDeviceName,
                    onStartLast = { engine.connectLastDevice() },
                    onStop = { engine.disconnect() },
                    onFindDevice = { tab = 0 }
                )
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = Accent,
    indicatorColor = Accent.copy(alpha = 0.16f),
    unselectedIconColor = Color(0xFF8A90BD),
    unselectedTextColor = Color(0xFF8A90BD)
)

@Composable
fun DevicesScreen(
    devices: List<DeviceInfo>,
    state: String,
    onScan: () -> Unit,
    onPick: (DeviceInfo?) -> Unit,
) {
    val listState = rememberLazyListState()
    var prevIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(devices) {
        val cur = listState.firstVisibleItemIndex
        if (prevIndex <= 1 && cur > prevIndex && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
        prevIndex = listState.firstVisibleItemIndex
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(UiStrings.appTitle, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(UiStrings.appSubtitle, color = Color(0xFF9BA0C8), fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onScan,
            colors = ButtonDefaults.buttonColors(containerColor = if (state == "scanning") Color(0xFF3A3A55) else Accent),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state == "scanning") {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(UiStrings.scanning, color = Color.White, fontSize = 17.sp)
            } else {
                Text(UiStrings.scanForDevices, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (devices.isEmpty() && state != "scanning") {
            Text(
                UiStrings.noDevicesYet,
                color = Color(0xFF6F7598), fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.id }) { d ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) 0.97f else 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .scale(scale)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (state == "scanning") CardTint.copy(alpha = 0.35f) else CardTint)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current
                        ) { onPick(d) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (d.hr) Accent.copy(alpha = 0.22f) else Color(0xFF2E2E46)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (d.hr) "♥" else "≈",
                            color = if (d.hr) Accent else Color(0xFF8A90BD),
                            fontSize = 19.sp
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("${d.rssi} ${UiStrings.dbm}", color = Color(0xFF8A90BD), fontSize = 13.sp)
                    }
                    if (d.hr || d.spo2) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (d.hr) {
                                Surface(color = Accent.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                                    Text(" ${UiStrings.hrBadge} ", color = Accent, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            if (d.spo2) {
                                Surface(color = SpoAccent.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                                    Text(" ${UiStrings.o2Badge} ", color = SpoAccent, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("›", color = Color(0xFF6F7598), fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
fun MonitorScreen(
    bpm: Int, spo2: Int, state: String, detail: String,
    history: List<HrPoint>, spo2History: List<Spo2Point>,
    lastDeviceName: String,
    onStartLast: () -> Unit,
    onStop: () -> Unit,
    onFindDevice: () -> Unit,
) {
    val monitoring = state == "connected" || state == "connecting"
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(
            color = Color(0xFF20203A),
            shape = RoundedCornerShape(99.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 14.dp).size(10.dp).clip(CircleShape)
                    .background(if (monitoring) Color(0xFF34C759) else Color(0xFFFF9F0A)))
                Text(
                    "  ${if (monitoring) detail else if (lastDeviceName.isEmpty()) UiStrings.notMonitoring else UiStrings.nameNotMeasuring(lastDeviceName)}",
                    color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MetricBlock(
                title = "♥", value = bpm,
                unitLabel = UiStrings.bpm, accent = Accent,
                zone = if (monitoring) hrZone(bpm) else null
            )
            Spacer(Modifier.height(22.dp))
            VitalCard(
                points = history.map { VitalPoint(it.t, it.bpm) },
                domain = if (history.isEmpty()) 40f..140f else hrDomain(history),
                accent = Accent, unit = UiStrings.bpm, unitLabel = " BPM",
                monitoring = monitoring, lastDeviceName = lastDeviceName
            )
            Spacer(Modifier.height(22.dp))
            MetricBlock(
                title = "O₂", value = spo2,
                unitLabel = UiStrings.percent, accent = SpoAccent,
                zone = if (monitoring) spo2Zone(spo2) else null
            )
            Spacer(Modifier.height(22.dp))
            VitalCard(
                points = spo2History.map { VitalPoint(it.t, it.spo2) },
                domain = 80f..100f,
                accent = SpoAccent, unit = UiStrings.percent, unitLabel = " %",
                monitoring = monitoring, lastDeviceName = lastDeviceName
            )
        }
        Spacer(Modifier.height(16.dp))
        if (monitoring) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A55)),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(UiStrings.stopMeasuring, color = Color.White, fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = if (lastDeviceName.isEmpty()) onFindDevice else onStartLast,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (lastDeviceName.isNotEmpty()) UiStrings.startMeasuring else UiStrings.findADevice,
                    color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun hrDomain(history: List<HrPoint>): ClosedFloatingPointRange<Float> {
    val minV = (history.minOf { it.bpm } - 8f).coerceAtLeast(20f)
    val maxV = (history.maxOf { it.bpm } + 10f).coerceAtMost(220f)
    return minV..maxV
}

private fun hrZone(bpm: Int): Pair<String, Color> = when {
    bpm < 60 -> UiStrings.zoneRest to Color(0xFF73CFFF)
    bpm < 100 -> UiStrings.zoneNormal to Color(0xFF34C759)
    bpm < 120 -> UiStrings.zoneElevated to Color(0xFFFF9F0A)
    bpm < 160 -> UiStrings.zoneExercise to SpoAccent
    else -> UiStrings.zonePeak to Color(0xFFFF375F)
}

private fun spo2Zone(spo2: Int): Pair<String, Color> = when {
    spo2 >= 95 -> UiStrings.zoneNormal to Color(0xFF34C759)
    spo2 >= 91 -> UiStrings.zoneAttention to Color(0xFFFF9F0A)
    else -> UiStrings.zoneLow to Color(0xFFFF375F)
}

@Composable
private fun MetricBlock(
    title: String,
    value: Int,
    unitLabel: String,
    accent: Color,
    zone: Pair<String, Color>?,
) {
    val monitoring = value > 0
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (!monitoring && title == "♥") "♡" else title,
                color = if (monitoring) accent else Color(0xFF4A4F6E), fontSize = 42.sp
            )
            Text(if (monitoring) "$value" else "—", fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = if (monitoring) Color.White else Color.White.copy(alpha = 0.3f))
            Text(unitLabel, color = if (monitoring) Color(0xFF8A90BD) else Color(0xFF4A4F6E),
                fontSize = 18.sp, letterSpacing = 4.sp)
            zone?.let { (text, color) ->
                Spacer(Modifier.height(8.dp))
                Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(99.dp)) {
                    Text(" $text ", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun VitalCard(
    points: List<VitalPoint>,
    domain: ClosedFloatingPointRange<Float>,
    accent: Color,
    unit: String,
    unitLabel: String,
    monitoring: Boolean,
    lastDeviceName: String,
) {
    Surface(
        color = Color(0xFF161624),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(UiStrings.last5Minutes, color = Color(0xFF9BA0C8), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            if (points.size >= 2) {
                VitalChart(
                    points = points, domain = domain, accent = accent, unit = unit,
                    modifier = Modifier.fillMaxWidth().height(170.dp)
                )
            } else {
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (monitoring) UiStrings.waitingForData
                        else if (lastDeviceName.isEmpty()) UiStrings.findYourSensor
                        else UiStrings.startMeasuringToRecord,
                        color = Color(0xFF6F7598), fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun VitalChart(
    points: List<VitalPoint>,
    domain: ClosedFloatingPointRange<Float>,
    accent: Color,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val valid = points.filter { it.value > 0 }
    if (valid.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(UiStrings.waitingForData, color = Color(0xFF6F7598))
        }
        return
    }
    val runs = mutableListOf<MutableList<VitalPoint>>()
    runs.add(mutableListOf(valid[0]))
    for (i in 1 until valid.size) {
        if (valid[i].t - valid[i - 1].t > 2600) runs.add(mutableListOf())
        runs.last().add(valid[i])
    }
    val minV = domain.start
    val maxV = domain.endInclusive
    val t0 = valid.minOf { it.t }
    val span = (valid.maxOf { it.t } - t0).coerceAtLeast(1)

    var canvasW by remember { mutableFloatStateOf(0f) }
    var cursor by remember { mutableStateOf<VitalPoint?>(null) }
    var lastTouchMs by remember { mutableLongStateOf(0L) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val axisStyle = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
    val density = LocalDensity.current
    val labelH = with(density) { 20.dp.toPx() }
    val yLabelWx = maxOf(
        textMeasurer.measure("${maxV.roundToInt()}", axisStyle).size.width,
        textMeasurer.measure("${minV.roundToInt()}", axisStyle).size.width
    ) + 10f
    val pts by rememberUpdatedState(valid)
    val t0s by rememberUpdatedState(t0)
    val spans by rememberUpdatedState(span)

    LaunchedEffect(lastTouchMs) {
        if (lastTouchMs > 0) {
            kotlinx.coroutines.delay(3000)
            cursor = null
        }
    }

    val overlayModifier = Modifier
        .onSizeChanged { canvasW = it.width.toFloat() }
        .pointerInput(Unit) {
            fun updateFor(x: Float) {
                val pw = (canvasW - yLabelWx).coerceAtLeast(1f)
                val pp = pts
                if (pw <= 0f || pp.size < 2) return
                val target = t0s + ((x / pw).coerceIn(0f, 1f) * spans).toLong()
                val hit = pp.minByOrNull { kotlin.math.abs(it.t - target) } ?: return
                cursor = hit
                lastTouchMs = System.currentTimeMillis()
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                updateFor(down.position.x)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    updateFor(change.position.x)
                    change.consume()
                }
            }
        }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height - labelH
            val plotW = (w - yLabelWx).coerceAtLeast(10f)
        val n = (maxV - minV)
        fun yOf(value: Int) = h - (value - minV) / n * h
        fun xOf(t: Long) = (t - t0).toFloat() / span.toFloat() * plotW

        for (g in 0..3) {
            val gy = h * g / 3f
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, gy), Offset(plotW, gy), 1f)
            val tick = (maxV - n * g / 3f).roundToInt()
            val yLayout = textMeasurer.measure("$tick", axisStyle)
            drawText(
                yLayout,
                topLeft = Offset(plotW + 8f, gy - yLayout.size.height / 2f)
            )
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (g in 0..3) {
            val tickT = t0 + (span * g / 3f).toLong()
            val xLayout = textMeasurer.measure(timeFmt.format(Date(tickT)), axisStyle)
            val xPos = (xOf(tickT) - xLayout.size.width / 2f).coerceIn(2f, plotW - xLayout.size.width - 2f)
            drawText(xLayout, topLeft = Offset(xPos, h + 3f))
        }
        for (run in runs) {
            if (run.size < 2) continue
            val path = Path()
            run.forEachIndexed { i, p ->
                val x = xOf(p.t)
                val y = yOf(p.value)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val lastX = xOf(run.last().t)
            val fill = Path().apply {
                addPath(path)
                lineTo(lastX, h)
                lineTo(xOf(run.first().t), h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, accent, style = Stroke(width = 3f))
        }

        val c = cursor
        if (c != null) {
            val cx = xOf(c.t)
            val cy = yOf(c.value)
            drawLine(
                Color.White.copy(alpha = 0.55f), Offset(cx, 0f), Offset(cx, h),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, cy), Offset(plotW, cy), 1f)
            drawCircle(accent, radius = 6f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = 2.5f, center = Offset(cx, cy))
            val layout = textMeasurer.measure("${c.value} $unit", labelStyle)
            val pad = 8f
            val tW = layout.size.width
            val tH = layout.size.height
            val boxL = (cx - tW / 2 - pad).coerceIn(pad, plotW - tW - pad * 2)
            val boxT = (cy - tH - pad * 2 - 8f).coerceAtLeast(pad)
            drawRoundRect(
                color = Color(0xE6161624),
                topLeft = Offset(boxL, boxT),
                size = Size(tW + pad * 2, tH + pad * 2),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawText(layout, topLeft = Offset(boxL + pad, boxT + pad))
        }
        }

        Box(Modifier.fillMaxSize().then(overlayModifier)) {}
    }
}



