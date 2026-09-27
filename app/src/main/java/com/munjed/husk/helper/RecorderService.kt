package com.munjed.husk.helper

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.munjed.husk.R
import com.munjed.husk.data.Prefs
import com.munjed.husk.ui.RecordingsActivity
import java.io.File

/**
 * Records voice to app-private storage, from a cold phone, in one action.
 *
 * The ongoing notification and the system's green microphone dot are not optional and not hideable:
 * Android only lets an app hold the microphone in the background behind a foreground service, and a
 * foreground service must show a notification. Husk records openly or not at all. Every route in
 * (tile, chord, settings) lands here, so the permission and pref checks live in one place.
 */
class RecorderService : Service() {

    // Only touched on [worker]. This service lives in the launcher's process, and opening the mic
    // takes 100-500 ms; on the main thread that stalled the launcher while RecordActivity was
    // closing, the system timed out its pause, and the launcher came back unable to draw new screens.
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private val worker = HandlerThread("recorder").apply { start() }
    private val handler = Handler(worker.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        handler.post {
            when (action) {
                ACTION_STOP -> stop()
                else -> if (recorder == null) start() else stop()
            }
        }
        return START_NOT_STICKY
    }

    private fun start() {
        // the chord fires from another process, where the prefs copy is stale, so the gate is here
        if (!Prefs(this).recorderEnabled) {
            showToast(R.string.recorder_is_off)
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast(R.string.recorder_needs_mic)
            stopSelf()
            return
        }
        // foreground first: a background process that opens the mic is handed silence, not an error
        if (!goForeground()) {
            stopSelf()
            return
        }
        val target = newRecordingFile(this)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(64000) // ~0.5 MB a minute, speech stays legible
            rec.setOutputFile(target.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { rec.release() }
            target.delete()
            showToast(R.string.recorder_failed)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = rec
        file = target
        isRecording = true
        refreshTile()
    }

    private fun stop() {
        val rec = recorder
        recorder = null
        isRecording = false
        if (rec != null) {
            // stop() throws when the encoder got no frames at all; that file is a zero-length lie
            val kept = runCatching { rec.stop() }.isSuccess
            runCatching { rec.release() }
            if (kept) showToast(getString(R.string.recording_saved, recordingTitle(file?.name.orEmpty())))
            else file?.delete()
        }
        file = null
        refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.recorder))
                .build()
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, RecordingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.recording))
            .setUsesChronometer(true) // elapsed time, so a pocket recording is not a guess
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else
                startForeground(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            // Android 14 refuses a microphone service with no visible activity behind it. The tile and
            // the chord's one-frame activity both satisfy that; a stray background start lands here.
            e.printStackTrace()
            showToast(R.string.recorder_blocked)
            false
        }
    }

    /** The tile reads [isRecording] when the system next lets it listen; this is how we ask. */
    private fun refreshTile() = runCatching {
        TileService.requestListeningState(this, ComponentName(this, RecorderTile::class.java))
    }

    override fun onDestroy() {
        // a kill mid-recording still leaves a playable file: MPEG_4 is written as it goes
        handler.post { if (recorder != null) stop() }
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.munjed.husk.RECORD_STOP"
        const val ACTION_TOGGLE = "com.munjed.husk.RECORD_TOGGLE"
        private const val CHANNEL = "husk_recorder"
        private const val NOTIFICATION_ID = 8213

        @Volatile
        var isRecording = false
            private set

        /** Every trigger goes through here: start if idle, stop if running. */
        fun toggle(context: Context) {
            val intent = Intent(context, RecorderService::class.java).setAction(ACTION_TOGGLE)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { it.printStackTrace() } // Android 12+ can refuse the start outright
        }
    }
}
