package com.munjed.husk.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.munjed.husk.BuildConfig
import com.munjed.husk.data.Prefs
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
 * Transport controls for the music app, VLC by default or whatever is chosen in settings. That app
 * publishing its playback service as a MediaBrowserService (Android Auto needs the same thing) is
 * what gives the real queue, its ordering and its history, without the notification listener
 * permission. Binding also starts the app when it is not running, which is what makes the play
 * button work from cold.
 *
 * ponytail: media key events are the fallback path. They cost nothing and cover every other player,
 * so there is no second integration to write when VLC is missing.
 */
// the Transsion ROM drops third party debug logs, hence info level, hence the release guard
private fun log(message: String) {
    if (BuildConfig.DEBUG) Log.i(TAG, message)
}

class MediaControl(private val context: Context) {

    private val prefs = Prefs(context)
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

    // shared by both connection paths: bind, wire callbacks, replay whatever waited on it
    private fun onControllerReady() {
        log("connected, state=${controller?.playbackState?.state}")
        onPlayingChanged?.invoke(isPlaying)
        onTrackChanged?.invoke(titleOf(controller?.metadata))
        if (playWhenConnected) {
            playWhenConnected = false
            playPause()
        }
    }

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val browser = browser ?: return
            controller = MediaController(context, browser.sessionToken).apply {
                registerCallback(controllerCallback)
            }
            onControllerReady()
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

    /** The app the controls are currently driving, for a "tap the title to open it" button. */
    val targetPackage: String?
        get() = controller?.packageName?.takeIf { it.isNotBlank() }
            ?: prefs.musicAppPackage.takeIf { it.isNotBlank() }
            ?: mediaBrowserService()?.packageName

    /**
     * False when no app publishes a browser service, no active session can be read either, and
     * nothing is playing right now, i.e. there is nothing the buttons could ever drive, so the home
     * screen hides them.
     */
    fun hasPlayer(): Boolean {
        if (mediaBrowserService() != null) return true
        if (activeSessionController() != null) return true
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.isMusicActive
    }

    fun connect() {
        val service = mediaBrowserService()
        if (service != null) {
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
            return
        }
        // the chosen app publishes no browser service (most apps don't): read its session directly
        // instead of flying blind on media keys. Needs notification access, which Husk already asks
        // for its own notification list, so this is opportunistic rather than a new permission ask.
        val sessionController = activeSessionController() ?: return
        if (controller?.sessionToken == sessionController.sessionToken) return
        disconnect()
        controller = sessionController.apply { registerCallback(controllerCallback) }
        onControllerReady()
    }

    /**
     * The chosen app's currently active MediaSession, read through the notification listener Husk
     * already runs for its own notification list. Null when notification access was never granted
     * (getActiveSessions throws) or the app has no active session right now.
     */
    private fun activeSessionController(): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, NotificationService::class.java)
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            return null
        }
        val preferred = prefs.musicAppPackage
        return if (preferred.isNotBlank()) sessions.firstOrNull { it.packageName == preferred }
        else sessions.firstOrNull { it.packageName != context.packageName }
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
            PlaybackState.STATE_PLAYING -> sendCommand({ it.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)
            PlaybackState.STATE_PAUSED -> sendCommand({ it.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
            else -> shuffleAll() // nothing loaded, so start something at random
        }
    }

    /**
     * Browser-bound apps (VLC) get real transport controls, already fast there. Apps only reached
     * through the notification-listener session (no browser, e.g. an app with no MediaBrowserService)
     * get a media key instead: on this device the same command noticeably lags going through the
     * session's own transport-control binder instead of the system's media key dispatch.
     */
    private fun sendCommand(controlsAction: (MediaController.TransportControls) -> Unit, keyCode: Int) {
        val controls = controller?.transportControls
        if (browser != null && controls != null) controlsAction(controls) else sendKey(keyCode)
    }

    /**
     * Start a fresh queue when nothing is loaded. `playFromSearch("", null)` is a transport control,
     * not a browse-tree one, so it works whether or not this app has a browser to walk; VLC's browse
     * tree (steps in ensureStarted()) is only an escalation for when that plain ask does not stick.
     */
    fun shuffleAll() {
        val controller = controller ?: run {
            playWhenConnected = true
            connect()
            return
        }
        // its browse tree, when there is one, is not reliably populated in the first moments after
        // the service starts, so this goes first and the browse is a retry step.
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
        if (controller == null) connect()
        sendCommand({ it.skipToNext() }, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        if (controller == null) connect()
        sendCommand({ it.skipToPrevious() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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
     * The app chosen in settings, when it publishes a browser service; VLC when installed and
     * nothing was chosen; otherwise any other app that publishes a MediaBrowserService (most music
     * players do, it is how Android Auto talks to them). Null means no such app, and every action
     * falls back to media keys. A chosen app that has no browser service also falls back to media
     * keys rather than silently binding to a different player.
     */
    private fun mediaBrowserService(): ComponentName? {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
        val preferred = prefs.musicAppPackage
        if (preferred.isNotBlank()) {
            return services.firstOrNull { it.serviceInfo.packageName == preferred }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }
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
