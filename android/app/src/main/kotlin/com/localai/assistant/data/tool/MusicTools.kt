package com.localai.assistant.data.tool

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import com.localai.assistant.data.system.AssistantNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Media transport (play/pause/next/prev) goes through AudioManager.dispatchMediaKeyEvent which
// works for any focused media app without special permissions. nowPlaying needs MediaSessionManager
// which requires the user to grant Notification Listener access to AssistantNotificationListener.
@Singleton
class MusicTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Resumes or starts media playback in whichever music or video app is focused (Spotify, YouTube Music, podcast apps, etc.).")
    fun playMusic(): String {
        Timber.i("TOOL playMusic()")
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        return "Resumed playback."
    }

    @Tool(description = "Pauses any currently-playing media.")
    fun pauseMusic(): String {
        Timber.i("TOOL pauseMusic()")
        sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        return "Paused."
    }

    @Tool(description = "Skips to the next track.")
    fun nextTrack(): String {
        Timber.i("TOOL nextTrack()")
        sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        return "Skipped to next track."
    }

    @Tool(description = "Goes back to the previous track.")
    fun previousTrack(): String {
        Timber.i("TOOL previousTrack()")
        sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "Went to previous track."
    }

    @Tool(description = "Returns the title and artist of whatever is currently playing. Requires notification listener access.")
    fun nowPlaying(): String {
        Timber.i("TOOL nowPlaying()")
        if (!AssistantNotificationListener.isEnabled(context)) {
            return "Notification listener access is not granted. Tell the user to open Settings → Notifications → Notification access and enable EdgeMind."
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, AssistantNotificationListener::class.java)
        return try {
            val sessions = msm.getActiveSessions(component)
            if (sessions.isEmpty()) return "Nothing is playing."
            val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.first()
            val md = active.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            buildString {
                if (title.isNotEmpty()) append("'$title'")
                if (artist.isNotEmpty()) {
                    if (isNotEmpty()) append(" by ") else append("By ")
                    append(artist)
                }
                if (isEmpty()) append("Music is playing on ${active.packageName}.")
            }
        } catch (e: SecurityException) {
            "Notification listener access is not granted. Tell the user to enable it under Settings → Notifications → Notification access."
        }
    }

    private fun sendKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
