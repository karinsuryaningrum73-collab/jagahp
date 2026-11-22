package com.example.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

data class BatteryTelemetry(
    val percent: Int = 100,
    val temperatureCelsius: Double = 30.0,
    val charging: Boolean = false
)

object BatteryTelemetryMonitor {

    fun readSnapshot(context: Context): BatteryTelemetry {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        return parseIntent(intent)
    }

    fun observe(context: Context): Flow<BatteryTelemetry> = callbackFlow {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(parseIntent(intent))
            }
        }
        val initial = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        trySend(parseIntent(initial))
        awaitClose {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }.distinctUntilChanged()

    private fun parseIntent(intent: Intent?): BatteryTelemetry {
        if (intent == null) return BatteryTelemetry()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        } else {
            100
        }
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300)
        val tempC = ((tenthsC / 10.0) * 10.0).roundToInt() / 10.0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryTelemetry(
            percent = percent,
            temperatureCelsius = tempC,
            charging = charging
        )
    }
}
