package com.anthropic.claudehud

import android.app.*
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val CH_ID   = "claude_hud_ch"
        const val NOTIF_ID = 42
    }

    private lateinit var wm: WindowManager

    // HUD
    private var hudView: TextView? = null
    private var hudEnabled = false
    private val hudHandler = Handler(Looper.getMainLooper())
    private var hudRunnable: Runnable? = null

    // BT Card
    private var btCardView: View? = null
    private var btCardEnabled = false

    // AOD
    private var aodView: AodCanvas? = null
    private var aodEnabled = false
    private val aodHandler = Handler(Looper.getMainLooper())
    private var aodShiftRunnable: Runnable? = null

    // ── Receivers ──────────────────────────────────────────────────────────────
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!btCardEnabled) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    showBtCard(dev?.name ?: "Bluetooth", "🎧", "Подключено")
                }
                Intent.ACTION_HEADSET_PLUG ->
                    if (intent.getIntExtra("state", 0) == 1)
                        showBtCard(intent.getStringExtra("name") ?: "Наушники", "🎧", "Подключены")
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!aodEnabled) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED    -> showAod()
                Intent.ACTION_POWER_DISCONNECTED -> hideAod()
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        })
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newHud = intent?.getBooleanExtra("hud",    false) ?: false
        val newBt  = intent?.getBooleanExtra("btCard", false) ?: false
        val newAod = intent?.getBooleanExtra("aod",    false) ?: false

        if (newHud != hudEnabled) { hudEnabled = newHud; if (hudEnabled) startHud() else stopHud() }
        btCardEnabled = newBt
        if (newAod != aodEnabled) { aodEnabled = newAod; if (!aodEnabled) hideAod() }

        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        stopHud(); hideAod(); removeBtCard()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
    }

    // ── HUD ────────────────────────────────────────────────────────────────────
    private fun startHud() {
        if (!Settings.canDrawOverlays(this) || hudView != null) return

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(16, 10, 16, 10)
            typeface = Typeface.MONOSPACE
            background = roundedBg(Color.argb(210, 20, 10, 5), 14f)
            elevation = 20f
            setShadowLayer(4f, 0f, 2f, Color.argb(120, 0, 0, 0))
        }

        val lp = overlayLp(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 130 }

        var dX = 0f; var dY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = lp.x - e.rawX; dY = lp.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX + dX).toInt()
                    lp.y = (e.rawY + dY).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                }
            }
            true
        }

        wm.addView(tv, lp)
        hudView = tv

        hudRunnable = object : Runnable {
            override fun run() { updateHud(tv); hudHandler.postDelayed(this, 1000) }
        }.also { hudHandler.post(it) }
    }

    private fun stopHud() {
        hudRunnable?.let { hudHandler.removeCallbacks(it) }; hudRunnable = null
        hudView?.let { runCatching { wm.removeView(it) } }; hudView = null
    }

    private fun updateHud(tv: TextView) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val temp    = (applicationContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val free    = mi.availMem / 1024 / 1024
        val total   = mi.totalMem / 1024 / 1024
        val maCur   = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000)
        val chrg    = bm.isCharging
        val bypass  = chrg && maCur < 50
        val ampStr  = when { bypass -> "Обходная" ; chrg -> "+${maCur}мА" ; else -> "-${maCur}мА" }

        Thread {
            val ping = try {
                val t = System.currentTimeMillis()
                InetAddress.getByName("8.8.8.8").isReachable(800)
                System.currentTimeMillis() - t
            } catch (_: Exception) { -1L }
            hudHandler.post {
                tv.text = "🌡 ${temp}°C   💾 ${free}/${total} МБ\n⚡ $ampStr   📡 ${if (ping < 0) "N/A" else "${ping}мс"}"
            }
        }.start()
    }

    // ── BT Card ────────────────────────────────────────────────────────────────
    private fun showBtCard(name: String, icon: String, status: String) {
        if (!Settings.canDrawOverlays(this)) return
        removeBtCard()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            background = roundedBg(Color.argb(240, 20, 10, 5), 24f)
            elevation = 32f
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(this).apply {
            text = icon; textSize = 34f
        })

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
            addView(TextView(this@OverlayService).apply {
                text = name; textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@OverlayService).apply {
                text = status; textSize = 11f
                setTextColor(Color.parseColor("#CC785C"))
            })
        }.also { row.addView(it) }

        root.addView(row)

        // Animated dots
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setPadding(0, 12, 0, 0)
            repeat(3) { i ->
                addView(View(this@OverlayService).apply {
                    val lp = LinearLayout.LayoutParams(8, 8).apply { setMargins(5, 0, 5, 0) }
                    layoutParams = lp
                    background = circleDrawable(
                        if (i == 0) Color.parseColor("#CC785C") else Color.parseColor("#3A2010")
                    )
                })
            }
        }.also { root.addView(it) }

        val dm = resources.displayMetrics
        val lp = overlayLp((dm.widthPixels * 0.68f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 100
        }

        root.alpha = 0f
        wm.addView(root, lp)
        btCardView = root

        root.animate().alpha(1f).setDuration(280).start()

        Handler(Looper.getMainLooper()).postDelayed({
            root.animate().alpha(0f).setDuration(380).withEndAction { removeBtCard() }.start()
        }, 4000)
    }

    private fun removeBtCard() {
        btCardView?.let { runCatching { wm.removeView(it) } }; btCardView = null
    }

    // ── AOD ────────────────────────────────────────────────────────────────────
    private fun showAod() {
        if (!Settings.canDrawOverlays(this) || aodView != null) return

        val canvas = AodCanvas(this)
        val dm = resources.displayMetrics
        val lp = overlayLp(dm.widthPixels, dm.heightPixels).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.OPAQUE
        }

        wm.addView(canvas, lp)
        aodView = canvas

        val gd = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean { hideAod(); return true }
            })
        canvas.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        aodShiftRunnable = object : Runnable {
            override fun run() {
                canvas.setOffset((-4..4).random().toFloat(), (-4..4).random().toFloat())
                aodHandler.postDelayed(this, 30_000)
            }
        }.also { aodHandler.postDelayed(it, 30_000) }

        canvas.startUpdating()
    }

    private fun hideAod() {
        aodShiftRunnable?.let { aodHandler.removeCallbacks(it) }; aodShiftRunnable = null
        aodView?.stopUpdating()
        aodView?.let { runCatching { wm.removeView(it) } }; aodView = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun overlayLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun roundedBg(color: Int, r: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(color)
    }

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CH_ID, "Claude HUD", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID)
        .setContentTitle("Claude HUD")
        .setContentText("Мониторинг активен")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}

// ── AOD Canvas View ────────────────────────────────────────────────────────────
class AodCanvas(context: Context) : android.view.View(context) {

    private var offX = 0f; private var offY = 0f
    private val h = Handler(Looper.getMainLooper())
    private var updRunnable: Runnable? = null

    private val pRingBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9f
        color = Color.argb(55, 204, 120, 92)
    }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9f
        color = Color.parseColor("#CC785C"); strokeCap = Paint.Cap.ROUND
    }
    private val pBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 212, 137, 106); textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val pClock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 240, 232, 220); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    private var pct = 0; private var temp = 0f
    private var maCur = 0; private var bypass = false
    private var clock = "--:--"

    init { setBackgroundColor(Color.BLACK) }

    fun setOffset(x: Float, y: Float) { offX = x; offY = y; invalidate() }

    fun startUpdating() {
        updRunnable = object : Runnable {
            override fun run() { refresh(); h.postDelayed(this, 1000) }
        }.also { h.post(it) }
    }

    fun stopUpdating() { updRunnable?.let { h.removeCallbacks(it) }; updRunnable = null }

    private fun refresh() {
        val ctx = context
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        pct    = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        temp   = (applicationContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        maCur  = kotlin.math.abs(raw / 1000)
        bypass = bm.isCharging && maCur < 50
        clock  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f + offX
        val cy = height / 2f + offY
        val radius = width * 0.27f

        // Ring
        canvas.drawCircle(cx, cy, radius, pRingBg)
        pRing.color = when {
            pct > 60 -> Color.parseColor("#CC785C")
            pct > 25 -> Color.parseColor("#D4896A")
            else     -> Color.parseColor("#FF6B6B")
        }
        val oval = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, -90f, pct.coerceIn(0,100) * 3.6f, false, pRing)

        // Percent
        pBig.textSize = radius * 0.52f
        canvas.drawText("$pct%", cx, cy + pBig.textSize * 0.36f, pBig)

        // Sub info
        pSub.textSize = width * 0.038f
        val sub = if (bypass) "⚡Обходная  🌡${temp}°C" else "⚡${maCur}мА  🌡${temp}°C"
        canvas.drawText(sub, cx, cy + radius * 0.56f, pSub)

        // Clock
        pClock.textSize = width * 0.13f
        canvas.drawText(clock, cx, cy - radius * 1.38f, pClock)

        // "Double-tap to close" hint
        pSub.textSize = width * 0.028f
        pSub.color = Color.argb(100, 180, 140, 110)
        canvas.drawText("двойной тап — закрыть", cx, cy + radius * 1.5f, pSub)
        pSub.color = Color.argb(180, 212, 137, 106)
    }
}
