package com.vela.assistant.data.tool

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

// Calendar read/write via the system content provider. Both directions need the matching
// runtime permission; tools return a structured error when permissions are missing so the
// model can verbalize "please grant calendar access in settings."
@Singleton
class CalendarTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Reads upcoming events from the user's calendar over a given time window. Returns up to 10 events with start time and title.")
    fun readCalendar(
        @ToolParam(description = "Hours from now to include. Use 24 for 'today/tomorrow', 168 for 'this week'.") hoursAhead: Int,
    ): String {
        Timber.i("TOOL readCalendar(hoursAhead=$hoursAhead)")
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return "Calendar permission is not granted. Tell the user to grant Calendar permission in app settings."
        }
        val now = System.currentTimeMillis()
        val end = now + hoursAhead.coerceIn(1, 24 * 30) * 3_600_000L

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(now.toString(), end.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val format = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, args, sort,
        )?.use { c ->
            val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIdx = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val locIdx = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            while (c.moveToNext() && rows.size < 10) {
                val title = c.getString(titleIdx) ?: "(untitled)"
                val start = format.format(Date(c.getLong(startIdx)))
                val loc = c.getString(locIdx).orEmpty()
                rows += if (loc.isNotEmpty()) "$start — $title at $loc" else "$start — $title"
            }
        }
        return if (rows.isEmpty()) "No events in the next $hoursAhead hour(s)." else rows.joinToString("\n")
    }

    // Structured params instead of an ISO string. Gemma 4 E2B reliably mangled the ISO
    // string format and even mangled 4-digit years as separate Ints (e.g. '202026'). The
    // year parameter is gone — we always use the device's current year, which covers the
    // overwhelming majority of voice calendar requests. The model only has to produce
    // small 1-2 digit clusters (month, day, hour, minute) it can emit reliably.
    @Tool(description = "Adds a new event to the user's primary calendar this year. Take month/day from [Now] or what the user said; take hour/minute from the spoken time (e.g. '6 PM' -> hour=18, minute=0).")
    fun createCalendarEvent(
        @ToolParam(description = "Event title.") title: String,
        @ToolParam(description = "Month 1-12.") month: Int,
        @ToolParam(description = "Day of month 1-31.") day: Int,
        @ToolParam(description = "Hour of day 0-23 in 24-hour format. '6 PM' = 18.") hour: Int,
        @ToolParam(description = "Minute 0-59. Default 0.") minute: Int = 0,
        @ToolParam(description = "Event duration in minutes. Default 60.") durationMinutes: Int = 60,
        @ToolParam(description = "Optional location string.") location: String = "",
    ): String {
        val zone = TimeZone.getDefault()
        val zoneId = ZoneId.systemDefault()
        val year = java.time.LocalDate.now(zoneId).year
        Timber.i("TOOL createCalendarEvent(title='$title', m=$month d=$day h=$hour min=$minute dur=$durationMinutes loc='$location') currentYear=$year zone=${zone.id}")
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return "Calendar write permission is not granted. Tell the user to grant Calendar permission in app settings."
        }
        if (month !in 1..12) return "Invalid month $month — use 1-12."
        if (day !in 1..31) return "Invalid day $day — use 1-31."
        if (hour !in 0..23) return "Invalid hour $hour — use 0-23 (24-hour clock)."
        if (minute !in 0..59) return "Invalid minute $minute — use 0-59."
        val safeDuration = durationMinutes.coerceIn(1, 24 * 60)

        val startMillis = runCatching {
            LocalDateTime.of(year, month, day, hour, minute)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrElse { return "Invalid date $year-$month-$day: ${it.message}" }
        val endMillis = startMillis + safeDuration * 60_000L

        Timber.i("createCalendarEvent: start=${Date(startMillis)} duration=${safeDuration}m")
        val calendarId = primaryCalendarId() ?: return "No writable calendar found on the device."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "Failed to create event."
        val format = SimpleDateFormat("EEE h:mm a z", Locale.getDefault())
        return "Created event '$title' starting ${format.format(Date(startMillis))} (device timezone ${zone.id})."
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun primaryCalendarId(): Long? {
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            var firstId: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                if (firstId == null) firstId = id
                if (c.getInt(primaryIdx) == 1) return@use id
            }
            firstId
        }
    }
}
