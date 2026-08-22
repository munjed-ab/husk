package com.munjed.husk.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.munjed.husk.BuildConfig
import android.view.KeyEvent

private const val VLC_PACKAGE = "org.videolan.vlc"
private const val SERVICE_INTERFACE = "android.media.browse.MediaBrowserService"

// plenty of system components publish a browser service without being music players
private val NON_PLAYERS = listOf(
    "com.android.bluetooth",
    "googlequicksearchbox",
    "com.android.tv",
    "telecom",
    "carrier",
)
private const val TAG = "MediaControl"
private const val MAX_BROWSE_DEPTH = 3
private const val START_RETRY_MS = 1500L
private const val SLEEP_TIMER_MINUTES = 30

/**
 * Transport controls for VLC. VLC publishes its playback service as a MediaBrowserService for
 * Android Auto, so binding to it gives the real queue, its ordering and its history without the
 * notification listener permission. Binding also starts VLC when it is not running, which is what
 * makes the play button work from cold.
 *
 * ponytail: media key events are the fallback path. They cost nothing and cover every other player,
 * so there is no second integration to write when VLC is missing.
 */
// the Transsion ROM drops third party debug logs, hence info level, hence the release guard
private fun log(message: String) {
    if (BuildConfig.DEBUG) Log.i(TAG, message)
}

class MediaControl(private val context: Context) {

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var playWhenConnected = false
    private val handler = Handler(Looper.getMainLooper())

    /** Called with true while something is playing, so the button can show a pause icon. */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /** Called with the current track title, or null when nothing is loaded. */
    var onTrackChanged: ((String?) -> Unit)? = null

    /** Minutes left on the sleep timer, 0 when none is armed. */
    var sleepMinutes = 0
        private set

    private val sleepRunnable = Runnable {
        controller?.transportControls?.pause() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        sleepMinutes = 0
        onTrackChanged?.invoke(titleOf(controller?.metadata))
    }

    /** Arms the sleep timer, or cancels it when one is already running. Returns minutes armed. */
    fun toggleSleepTimer(minutes: Int = SLEEP_TIMER_MINUTES): Int {
        handler.removeCallbacks(sleepRunnable)
        sleepMinutes = if (sleepMinutes > 0) 0 else minutes
        if (sleepMinutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
        onTrackChanged?.invoke(titleOf(controller?.metadata))
        return sleepMinutes
    }

    val isPlaying: Boolean
        get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onPlayingChanged?.invoke(state?.state == PlaybackState.STATE_PLAYING)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            onTrackChanged?.invoke(titleOf(metadata))
        }
    }

    private fun titleOf(metadata: MediaMetadata?): String? {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val name = if (artist == null) title else "$title · $artist"
        return if (sleepMinutes > 0) "$name · ${sleepMinutes}m" else name
    }

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val browser = browser ?: return
            controller = MediaController(context, browser.sessionToken).apply {
                registerCallback(controllerCallback)
            }
            log("connected, state=${controller?.playbackState?.state}")
            onPlayingChanged?.invoke(isPlaying)
            onTrackChanged?.invoke(titleOf(controller?.metadata))
            if (playWhenConnected) {
                playWhenConnected = false
                playPause()
            }
        }

        override fun onConnectionFailed() {
            log("browser connection refused, falling back to media keys")
            browser = null
            val queued = playWhenConnected
            playWhenConnected = false
            if (queued) sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        // VLC's service died or was force stopped, the controller we hold is dead with it
        override fun onConnectionSuspended() {
            log("player connection suspended")
            controller?.unregisterCallback(controllerCallback)
            controller = null
            browser = null
        }
    }

    /**
     * False when no app publishes a browser service and nothing is playing right now, i.e. there is
     * nothing the buttons could ever drive, so the home screen hides them.
     */
    fun hasPlayer(): Boolean {
        if (mediaBrowserService() != null) return true
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.isMusicActive
    }

    fun connect() {
        val service = mediaBrowserService() ?: return
        // a browser left over from a killed player never reconnects on its own, throw it away
        if (browser?.isConnected == true && controller != null) return
        disconnect()
        log("connecting to $service")
        browser = MediaBrowser(context, service, connectionCallback, null)
        try {
            browser?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
            browser = null
        }
    }

    fun disconnect() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        try {
            browser?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        browser = null
    }

    fun playPause() {
        val controller = controller ?: run {
            // not connected yet: queue the press and reconnect, media keys cover the no-VLC case
            playWhenConnected = true
            connect()
            if (browser == null) sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        val state = controller.playbackState?.state
        log("play pressed, state=$state, queue=${controller.queue?.size}")
        when (state) {
            PlaybackState.STATE_PLAYING -> controller.transportControls.pause()
            PlaybackState.STATE_PAUSED -> controller.transportControls.play()
            else -> shuffleAll() // nothing loaded, so start something at random
        }
    }

    /**
     * Start a fresh queue when VLC has nothing loaded. Walks its browse tree from the real root
     * (subscribing to a guessed id returns nothing), preferring shuffle, then history, then any
     * playable track. Ordering after this point is VLC's own.
     */
    fun shuffleAll() {
        val controller = controller ?: run {
            playWhenConnected = true
            connect()
            return
        }
        val browser = browser ?: return
        // VLC's own "play something" entry point. Its browse tree is not reliably populated in the
        // first moments after the service starts, so this goes first and the browse is a retry step.
        controller.transportControls.playFromSearch("", null)
        ensureStarted()
    }

    private fun browseAndPlay(parentId: String, depth: Int) {
        val browser = browser ?: return
        if (depth > MAX_BROWSE_DEPTH) {
            log("browse gave up, asking VLC to play anything")
            controller?.transportControls?.playFromSearch("", null)
            return
        }
        browser.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                browser.unsubscribe(parentId)
                children.forEach { log("[$depth] $parentId > ${it.mediaId} | ${it.description.title} | playable=${it.isPlayable}") }
                if (children.isEmpty()) {
                    controller?.transportControls?.playFromSearch("", null)
                    return
                }
                val byId = { needle: String -> children.firstOrNull { it.mediaId?.contains(needle, true) == true } }
                val pick = byId("shuffle") ?: byId("history") ?: byId("last_added")
                    ?: children.filter { it.isPlayable }.randomOrNull()
                    ?: byId("/home") ?: byId("track") ?: byId("/l")
                    ?: children.filter { it.isBrowsable }.randomOrNull()
                val mediaId = pick?.mediaId ?: return
                if (pick.isPlayable) {
                    log("playing $mediaId")
                    controller?.transportControls?.playFromMediaId(mediaId, null)
                } else browseAndPlay(mediaId, depth + 1)
            }

            override fun onError(parentId: String) {
                log("browse failed for $parentId")
                controller?.transportControls?.playFromSearch("", null)
            }
        })
    }

    fun next() {
        val controls = controller?.transportControls
        if (controls == null) {
            connect()
            sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        } else controls.skipToNext()
    }

    fun previous() {
        val controls = controller?.transportControls
        if (controls == null) {
            connect()
            sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        } else controls.skipToPrevious()
    }

    /**
     * VLC sometimes swallows playFromMediaId when its service has just started. Give it a moment,
     * then escalate: play anything by search, then the plain play command, then a media key.
     */
    private fun ensureStarted(attempt: Int = 0) {
        if (attempt > 2) return
        handler.postDelayed({
            val state = controller?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) return@postDelayed
            log("still not playing (state=$state), escalating step $attempt")
            when (attempt) {
                0 -> browser?.let { browseAndPlay(it.root, 0) } // ask for shuffle all explicitly
                1 -> controller?.transportControls?.play()
                else -> sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            ensureStarted(attempt + 1)
        }, START_RETRY_MS)
    }

    /**
     * VLC when it is installed, otherwise any other app that publishes a MediaBrowserService
     * (most music players do, it is how Android Auto talks to them). Null means no such app, and
     * every action falls back to media keys.
     */
    private fun mediaBrowserService(): ComponentName? {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
        val vlc = services.firstOrNull { it.serviceInfo.packageName == VLC_PACKAGE }
        val chosen = vlc ?: services.firstOrNull { service ->
            val pkg = service.serviceInfo.packageName
            pkg != context.packageName && NON_PLAYERS.none { pkg.contains(it) }
        }
        return chosen?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }

    private fun sendKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
