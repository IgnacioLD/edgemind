package com.vela.assistant.data.tool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Permissionless device-status and device-control tools that complement SystemTools.
// Kept tight — each tool here pays prefill cost on every turn, so only voice-natural,
// high-value queries belong.
@Singleton
class DeviceTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Sets a one-shot alarm at a specific time of day on the device's clock app. Use for wake-up alarms; for short countdowns prefer set_timer.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24-hour format (0-23)") hour: Int,
        @ToolParam(description = "Minute (0-59)") minute: Int,
        @ToolParam(description = "Optional label shown on the alarm") label: String = "",
    ): String {
        Timber.i("TOOL setAlarm(hour=$hour, minute=$minute, label='$label')")
        if (hour !in 0..23 || minute !in 0..59) return "Error: hour must be 0-23 and minute 0-59."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifEmpty { "Vela alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            Timber.w("setAlarm: no activity resolves ACTION_SET_ALARM")
            return "No clock app on this device can set alarms."
        }
        return try {
            context.startActivity(intent)
            val displayHour = if (hour % 12 == 0) 12 else hour % 12
            val ampm = if (hour < 12) "AM" else "PM"
            "Alarm set for ${displayHour}:${minute.toString().padStart(2, '0')} $ampm."
        } catch (e: Exception) {
            Timber.w(e, "setAlarm startActivity failed")
            "Failed to set the alarm: ${e.message}"
        }
    }

    @Tool(description = "Returns the current battery percentage and whether the device is charging.")
    fun getBatteryStatus(): String {
        Timber.i("TOOL getBatteryStatus()")
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return if (level < 0) {
            "Battery status is unavailable."
        } else {
            "Battery is at $level%${if (charging) " and charging" else ""}."
        }
    }
}
