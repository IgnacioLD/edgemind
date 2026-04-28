package com.vela.assistant.data.tool

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Tools that work without runtime permissions or special user-granted access.
// Each function returns a short String the model verbalizes back to the user.
@Singleton
class SystemTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Sets a countdown timer in the device's clock app. Use for cooking, breaks, reminders, any 'set a timer for N minutes' request.")
    fun setTimer(
        @ToolParam(description = "Minutes from now to fire the timer (1-1440)") minutes: Int,
        @ToolParam(description = "Optional label shown on the timer notification") label: String = "",
    ): String {
        Timber.i("TOOL setTimer(minutes=$minutes, label='$label')")
        if (minutes <= 0) return "Error: minutes must be positive."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifEmpty { "Vela" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Resolve before launching: ACTION_SET_TIMER goes silent on devices where no clock
        // app handles it, instead of throwing ActivityNotFoundException. Without this check
        // the tool would log success while nothing actually happens.
        if (intent.resolveActivity(context.packageManager) == null) {
            Timber.w("setTimer: no activity resolves ACTION_SET_TIMER")
            return "No clock app on this device can set timers."
        }
        return try {
            context.startActivity(intent)
            "Timer set for $minutes minute(s)."
        } catch (e: Exception) {
            Timber.w(e, "setTimer startActivity failed")
            "Failed to set the timer: ${e.message}"
        }
    }

    @Tool(description = "Searches the web in the user's browser. Use as a fallback for general-knowledge questions you cannot answer locally, current events, or anything that needs fresh information.")
    fun searchWeb(
        @ToolParam(description = "The search query") query: String,
    ): String {
        Timber.i("TOOL searchWeb(query='$query')")
        if (query.isBlank()) return "Error: query is empty."
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(webSearch)
            "Searching the web for: $query"
        } catch (e: ActivityNotFoundException) {
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                )
                "Searching the web for: $query"
            } catch (e2: ActivityNotFoundException) {
                "No browser available to perform the search."
            }
        }
    }

    @Tool(description = "Turns the device flashlight on or off.")
    fun toggleFlashlight(
        @ToolParam(description = "true to turn on, false to turn off") on: Boolean,
    ): String {
        Timber.i("TOOL toggleFlashlight(on=$on)")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight on this device."
            cm.setTorchMode(cameraId, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Could not toggle flashlight: ${e.message}"
        }
    }

    @Tool(description = "Sets the media playback volume to a percentage (0 = mute, 100 = max).")
    fun setVolume(
        @ToolParam(description = "Volume level, 0 to 100") percent: Int,
    ): String {
        Timber.i("TOOL setVolume(percent=$percent)")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = clamped * max / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $clamped%."
    }

    @Tool(description = "Opens an installed app by name. Use when the user says 'open X', 'launch X', or 'go to X'.")
    fun launchApp(
        @ToolParam(description = "App name as the user would say it (e.g. 'YouTube', 'Chrome', 'Spotify')") appName: String,
    ): String {
        Timber.i("TOOL launchApp(appName='$appName')")
        if (appName.isBlank()) return "Error: app name is empty."
        val pm = context.packageManager
        val all = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            return "Could not list installed apps: ${e.message}"
        }
        val labelled = all.mapNotNull { info ->
            val label = pm.getApplicationLabel(info).toString()
            if (label.isBlank()) null else info to label
        }
        val exact = labelled.firstOrNull { it.second.equals(appName, ignoreCase = true) }
        val partial = labelled.firstOrNull { it.second.contains(appName, ignoreCase = true) }
        val target = exact ?: partial ?: return "Could not find an app named '$appName'."
        val intent = pm.getLaunchIntentForPackage(target.first.packageName)
            ?: return "App '${target.second}' has no launcher activity."
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            context.startActivity(intent)
            "Opening ${target.second}."
        } catch (e: Exception) {
            "Could not open ${target.second}: ${e.message}"
        }
    }
}
