package com.localai.assistant.data.tool

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.localai.assistant.data.system.AssistantNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// Media transport (pause/next/prev/now-playing) goes through the standard MediaSession plumbing.
// playSong is more involved — see its docstring.
@Singleton
class MusicTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Plays music. The ONLY tool for any 'play <something>' request — artists, songs, albums, playlists, or even just 'play music'. Hands off to the device's default music app and starts playback. IMPORTANT: pass ONLY the name as `query`, never the user's full sentence — e.g. 'Bad Bunny' not 'play music by Bad Bunny'. ALSO pass `type` so the music app auto-plays instead of just opening search results: 'artist' (default for 'play X'), 'song' / 'album' / 'playlist' as appropriate.")
    fun playSong(
        @ToolParam(description = "ONLY the artist/song/album/playlist name. Strip any verb like 'play' or 'put on'. Examples: 'Bad Bunny', 'Bohemian Rhapsody', 'Discovery'.") query: String,
        @ToolParam(description = "Kind of query: 'artist', 'song', 'album', 'playlist', or 'any'. Default to 'artist' for ambiguous 'play X' requests because that maximises auto-play success.") type: String = "artist",
    ): String {
        Timber.i("TOOL playSong(query='$query', type='$type')")
        if (query.isBlank()) return "Error: query is empty."

        // Target Poweramp explicitly when installed. Per its documented API
        // (powerampapp.com/api), Poweramp implements onPlayFromSearch reliably from any
        // caller — unlike Spotify, which gates that path to Google Assistant. With Poweramp
        // as the target we also won't fall victim to the system picking some random handler.
        val rawIntent = buildPlayFromSearchIntent(query, type)
        val powerampInstalled = runCatching {
            context.packageManager.getApplicationInfo(POWERAMP_PKG, 0)
        }.isSuccess
        val targetIntent = if (powerampInstalled) {
            val targeted = Intent(rawIntent).apply { setPackage(POWERAMP_PKG) }
            if (targeted.resolveActivity(context.packageManager) != null) {
                Timber.i("playSong: targeting Poweramp")
                targeted
            } else {
                Timber.i("playSong: Poweramp installed but doesn't resolve play-from-search; falling back")
                rawIntent
            }
        } else {
            rawIntent
        }

        val resolved = targetIntent.resolveActivity(context.packageManager)
            ?: return "No music app on this device can play that."
        val targetPackage = resolved.packageName

        // After cold-starting the app, watch for its MediaSession to come up and drive playback
        // via TransportControls.playFromSearch — the documented Android API for "tell this media
        // app to play these search results", same one Google Assistant uses. Belt-and-suspenders
        // play() fallback fires 1.5 s later if the session never reaches PLAYING.
        return try {
            context.startActivity(targetIntent)
            scheduleAutoPlay(targetPackage, query, type)
            "Playing '$query'."
        } catch (e: Exception) {
            Timber.w(e, "playSong startActivity failed")
            "Failed to play: ${e.message}"
        }
    }

    private fun buildPlayFromSearchIntent(query: String, type: String): Intent {
        val focus = mediaFocusFor(type)
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
            when (type.lowercase().trim()) {
                "artist" -> putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
                "album" -> putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
                "playlist" -> putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun mediaFocusFor(type: String): String = when (type.lowercase().trim()) {
        "song", "track", "title" -> "vnd.android.cursor.item/audio"
        "album" -> "vnd.android.cursor.item/album"
        "playlist" -> "vnd.android.cursor.item/playlist"
        "genre" -> "vnd.android.cursor.item/genre"
        "artist" -> "vnd.android.cursor.item/artist"
        else -> "vnd.android.cursor.item/audio"
    }

    // Watches the active media-session list for the target package's session to come up,
    // then drives playback via TransportControls. Without notification-listener access there's
    // no way to enumerate sessions across packages, so we silently no-op (the user still gets
    // the search-results page from the intent we already fired).
    private fun scheduleAutoPlay(targetPackage: String, query: String, type: String) {
        if (!AssistantNotificationListener.isEnabled(context)) {
            Timber.i("scheduleAutoPlay: notification listener access not granted — skipping")
            return
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listenerComponent = ComponentName(context, AssistantNotificationListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        val triggered = AtomicBoolean(false)

        val sessionsListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
                if (triggered.get()) return
                val target = controllers?.firstOrNull { it.packageName == targetPackage }
                if (target != null && triggered.compareAndSet(false, true)) {
                    triggerPlayFromSearch(target, query, type)
                    runCatching { msm.removeOnActiveSessionsChangedListener(this) }
                }
            }
        }

        handler.post {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
                // Spotify might already be running — check existing sessions immediately so
                // we don't wait pointlessly for a "change" event that won't fire.
                val current = msm.getActiveSessions(listenerComponent)
                val existing = current.firstOrNull { it.packageName == targetPackage }
                if (existing != null && triggered.compareAndSet(false, true)) {
                    triggerPlayFromSearch(existing, query, type)
                    runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
                }
            } catch (e: SecurityException) {
                Timber.w(e, "scheduleAutoPlay: session manager access denied")
            }
        }

        // Hard timeout: if the target's session never appeared (app failed to launch, user
        // dismissed, etc.), drop the listener so it doesn't leak.
        handler.postDelayed({
            if (!triggered.get()) {
                Timber.w("playFromSearch: target session never appeared for $targetPackage")
                runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
            }
        }, AUTO_PLAY_TIMEOUT_MS)
    }

    private fun triggerPlayFromSearch(controller: MediaController, query: String, type: String) {
        Timber.i("triggerPlayFromSearch: package=${controller.packageName} query='$query'")
        val tc = controller.transportControls
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocusFor(type))
            when (type.lowercase().trim()) {
                "artist" -> putString(MediaStore.EXTRA_MEDIA_ARTIST, query)
                "album" -> putString(MediaStore.EXTRA_MEDIA_ALBUM, query)
                "playlist" -> putString(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
            }
        }
        runCatching { tc.playFromSearch(query, extras) }
            .onFailure { Timber.w(it, "playFromSearch threw") }

        // Some apps (Spotify is the famous example) don't implement onPlayFromSearch reliably
        // when invoked from a non-privileged caller. They DO implement plain play(), and after
        // our earlier MEDIA_PLAY_FROM_SEARCH intent the app's UI is already on the searched
        // artist/album/playlist — so a follow-up play() presses their play button. If the
        // session is already PLAYING by the time we check, we leave it alone.
        Handler(Looper.getMainLooper()).postDelayed({
            val state = controller.playbackState?.state
            if (state != PlaybackState.STATE_PLAYING) {
                Timber.i("playFromSearch didn't start playback (state=$state); falling back to play()")
                runCatching { tc.play() }
            }
        }, PLAY_FALLBACK_DELAY_MS)
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

    private companion object {
        const val POWERAMP_PKG = "com.maxmpz.audioplayer"
        const val AUTO_PLAY_TIMEOUT_MS = 5_000L
        const val PLAY_FALLBACK_DELAY_MS = 1_500L
    }
}
