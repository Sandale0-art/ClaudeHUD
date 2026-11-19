package com.anthropic.claudehud

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews
import kotlin.math.abs

class StatsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        ids.forEach { update(ctx, mgr, it) }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))
                .forEach { update(ctx, mgr, it) }
        }
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

            val temp    = (ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val pct     = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val freeRam = mi.availMem / 1024 / 1024

            val views = RemoteViews(ctx.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.w_temp,   "${temp}°C")
            views.setTextViewText(R.id.w_charge, "$pct%")
            views.setTextViewText(R.id.w_ram,    "${freeRam}МБ")

            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, StatsWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_refresh, pi)
            mgr.updateAppWidget(id, views)
        }
    }
}
