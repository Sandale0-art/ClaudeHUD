package com.anthropic.claudehud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

// ── Colour palette (Claude brand) ─────────────────────────────────────────────
private val ClaudeOrange  = Color(0xFFCC785C)
private val ClaudeOrangeL = Color(0xFFD4896A)
private val ClaudeBrown   = Color(0xFF8B5E4A)
private val BgDeep        = Color(0xFF0D0A07)
private val BgCard        = Color(0xFF1A110C)
private val BgCardAlt     = Color(0xFF201510)
private val TextPrimary   = Color(0xFFF0E8E0)
private val TextSecondary = Color(0xFF8A7060)
private val TextDim       = Color(0xFF4A3828)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClaudeHudApp() }
    }

    // ── Root composable ────────────────────────────────────────────────────────
    @Composable
    fun ClaudeHudApp() {
        val ctx = this

        var hudEnabled   by remember { mutableStateOf(false) }
        var btEnabled    by remember { mutableStateOf(false) }
        var aodEnabled   by remember { mutableStateOf(false) }

        // Live stats
        var temp    by remember { mutableStateOf(0f) }
        var pct     by remember { mutableStateOf(0) }
        var maCur   by remember { mutableStateOf(0) }
        var freeRam by remember { mutableStateOf(0L) }
        var totRam  by remember { mutableStateOf(0L) }
        var charging by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                temp    = (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                pct     = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                maCur   = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000)
                freeRam = mi.availMem / 1024 / 1024
                totRam  = mi.totalMem / 1024 / 1024
                charging = bm.isCharging
                delay(2000)
            }
        }

        // Service control
        LaunchedEffect(hudEnabled, btEnabled, aodEnabled) {
            val intent = Intent(ctx, OverlayService::class.java).apply {
                putExtra("hud",    hudEnabled)
                putExtra("btCard", btEnabled)
                putExtra("aod",    aodEnabled)
            }
            if (hudEnabled || btEnabled || aodEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } else {
                ctx.stopService(Intent(ctx, OverlayService::class.java))
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(BgDeep)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                AppHeader()

                // Live stats ring card
                LiveStatsCard(temp, pct, maCur, freeRam, totRam, charging)

                // Permissions
                SectionLabel("Разрешения")
                PermRow(ctx)

                // Features
                SectionLabel("Функции")
                FeatureCard(
                    emoji = "📊",
                    title = "Плавающий HUD",
                    sub = "Температура · ОЗУ · Ток · Пинг",
                    enabled = hudEnabled
                ) { hudEnabled = it }
                FeatureCard(
                    emoji = "🎧",
                    title = "Карточка подключения",
                    sub = "Nothing-стиль при BT / jack",
                    enabled = btEnabled
                ) { btEnabled = it }
                FeatureCard(
                    emoji = "🌙",
                    title = "Индикатор зарядки AOD",
                    sub = "AMOLED · защита от выгорания",
                    enabled = aodEnabled
                ) { aodEnabled = it }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Claude HUD • made with ♥",
                    color = TextDim,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ── Header ─────────────────────────────────────────────────────────────────
    @Composable
    fun AppHeader() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Animated Claude logo
            val inf = rememberInfiniteTransition(label = "logo")
            val pulse by inf.animateFloat(
                initialValue = 0.92f, targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    tween(2200, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse
                ), label = "pulse"
            )
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(ClaudeOrangeL, ClaudeOrange, ClaudeBrown)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Face: eyes + smile drawn on canvas
                Canvas(Modifier.size(34.dp)) {
                    val w = size.width; val h = size.height
                    val eyeY = h * 0.38f
                    val eyeRx = w * 0.10f; val eyeRy = h * 0.12f
                    // left eye
                    drawOval(Color(0xFF1A0A00),
                        topLeft = Offset(w*0.22f - eyeRx, eyeY - eyeRy),
                        size = androidx.compose.ui.geometry.Size(eyeRx*2, eyeRy*2))
                    // right eye
                    drawOval(Color(0xFF1A0A00),
                        topLeft = Offset(w*0.78f - eyeRx, eyeY - eyeRy),
                        size = androidx.compose.ui.geometry.Size(eyeRx*2, eyeRy*2))
                    // smile arc
                    val smilePath = Path().apply {
                        moveTo(w*0.28f, h*0.62f)
                        cubicTo(w*0.38f, h*0.80f, w*0.62f, h*0.80f, w*0.72f, h*0.62f)
                    }
                    drawPath(smilePath, Color(0xFF1A0A00),
                        style = Stroke(width = w*0.07f,
                            cap = StrokeCap.Round))
                }
            }

            Spacer(Modifier.width(14.dp))
            Column {
                Text("Claude HUD", color = TextPrimary,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("System monitor & overlays",
                    color = TextSecondary, fontSize = 12.sp)
            }
        }
    }

    // ── Live stats ring ────────────────────────────────────────────────────────
    @Composable
    fun LiveStatsCard(
        temp: Float, pct: Int, maCur: Int,
        freeRam: Long, totRam: Long, charging: Boolean
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Battery ring
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 7f
                        val r = size.minDimension / 2f - stroke
                        val cx = size.width / 2f; val cy = size.height / 2f
                        // bg ring
                        drawCircle(TextDim.copy(alpha = 0.4f), radius = r,
                            style = Stroke(stroke))
                        // progress
                        val sweep = pct.coerceIn(0, 100) * 3.6f
                        val ringColor = when {
                            pct > 60 -> ClaudeOrangeL
                            pct > 25 -> Color(0xFFFFD93D)
                            else     -> Color(0xFFFF6B6B)
                        }
                        drawArc(ringColor, startAngle = -90f, sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$pct%", color = TextPrimary,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (charging) "⚡" else "🔋",
                            fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Stats grid
                Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)) {
                    StatRow("🌡", "Темп.", "${temp}°C", ClaudeOrange)
                    StatRow("⚡", "Ток",
                        "${maCur}мА${if (charging && maCur < 50) " (Обход)" else ""}",
                        ClaudeOrangeL)
                    StatRow("💾", "ОЗУ",
                        "${freeRam}/${totRam} МБ", Color(0xFFA89080))
                }
            }
        }
    }

    @Composable
    fun StatRow(icon: String, label: String, value: String, color: Color) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(icon, fontSize = 12.sp)
            Text(label, color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.width(44.dp))
            Text(value, color = color, fontSize = 12.sp,
                fontWeight = FontWeight.Medium)
        }
    }

    // ── Section label ──────────────────────────────────────────────────────────
    @Composable
    fun SectionLabel(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(3.dp, 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ClaudeOrange))
            Text(text, color = TextSecondary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp)
        }
    }

    // ── Permissions row ────────────────────────────────────────────────────────
    @Composable
    fun PermRow(ctx: Context) {
        Card(colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermItem("Поверх других окон",
                    Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")))
                }
                HudDivider()
                PermItem("Bluetooth Connect", false) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        requestPermissions(
                            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1)
                }
                HudDivider()
                PermItem("Уведомления", false) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        requestPermissions(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                HudDivider()
                PermItem("Оптимизация батареи (realme UI)", false) {
                    ctx.startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")))
                }
            }
        }
    }

    @Composable
    fun PermItem(label: String, granted: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (granted) Color(0xFF4CAF50) else TextDim))
            Spacer(Modifier.width(10.dp))
            Text(label, color = if (granted) TextPrimary else TextSecondary,
                fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (!granted) {
                TextButton(onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("Выдать", color = ClaudeOrange, fontSize = 12.sp)
                }
            } else {
                Text("✓", color = Color(0xFF4CAF50), fontSize = 13.sp)
            }
        }
    }

    @Composable
    fun HudDivider() {
        Box(Modifier.fillMaxWidth().height(1.dp)
            .background(TextDim.copy(alpha = 0.4f)))
    }

    // ── Feature toggle card ────────────────────────────────────────────────────
    @Composable
    fun FeatureCard(
        emoji: String, title: String, sub: String,
        enabled: Boolean, onToggle: (Boolean) -> Unit
    ) {
        val bg by animateColorAsState(
            if (enabled) Color(0xFF221508) else BgCard,
            tween(300), label = "bg"
        )
        val border by animateColorAsState(
            if (enabled) ClaudeOrange.copy(alpha = 0.6f) else Color.Transparent,
            tween(300), label = "border"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = bg),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(sub, color = TextSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White,
                        checkedTrackColor   = ClaudeOrange,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TextDim
                    )
                )
            }
        }
    }
}
