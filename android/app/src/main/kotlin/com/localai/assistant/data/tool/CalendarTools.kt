package com.localai.assistant.data.tool

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
import java.time.ZonedDateTime
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

    @Tool(description = "Adds a new event to the user's primary calendar.")
    fun createCalendarEvent(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Start time in ISO 8601 local format, e.g. 2026-04-26T14:00:00") startIso: String,
        @ToolParam(description = "End time in ISO 8601 local format. Optional; defaults to one hour after start.") endIso: String = "",
        @ToolParam(description = "Optional location string") location: String = "",
    ): String {
        val zone = TimeZone.getDefault()
        Timber.i("TOOL createCalendarEvent(title='$title', start='$startIso', end='$endIso', location='$location') deviceZone=${zone.id}")
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return "Calendar write permission is not granted. Tell the user to grant Calendar permission in app settings."
        }
        val start = parseIso(startIso) ?: return "Invalid start time. Use ISO 8601 like 2026-04-26T14:00:00."
        val end = if (endIso.isEmpty()) start + 3_600_000L else (parseIso(endIso) ?: return "Invalid end time.")
        Timber.i("createCalendarEvent: parsed start=${Date(start)} in zone=${zone.id}")
        val calendarId = primaryCalendarId() ?: return "No writable calendar found on the device."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "Failed to create event."
        val format = SimpleDateFormat("EEE h:mm a z", Locale.getDefault())
        return "Created event '$title' starting ${format.format(Date(start))} (device timezone ${zone.id})."
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun parseIso(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching {
            ZonedDateTime.parse(s).toInstant().toEpochMilli()
        }.recoverCatching {
            LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

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
