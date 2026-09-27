package com.munjed.husk.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.munjed.husk.R
import com.munjed.husk.data.Prefs
import com.munjed.husk.ui.RecordActivity

class MyAccessibilityService : AccessibilityService() {

    private val volumeKeysDown = mutableSetOf<Int>()
    private var chordFired = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    /**
     * Both volume keys at once toggles the recorder, from the lock screen, from inside any app, with
     * the screen off. This is the only trigger that is genuinely instant, which is the whole point of
     * the feature.
     *
     * It never returns true. Swallowing a volume key to make the gesture cleaner would mean a
     * launcher that can leave you unable to change the volume, and a stuck key state after any event
     * we fail to see. A volume step and the volume dialog are a cheap price for that.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                volumeKeysDown.add(code)
                // fire once per chord, not once per repeat while both keys are held
                if (volumeKeysDown.size == 2 && !chordFired) {
                    chordFired = true
                    startRecordActivity()
                }
            }

            KeyEvent.ACTION_UP -> {
                volumeKeysDown.remove(code)
                if (volumeKeysDown.isEmpty()) chordFired = false
            }
        }
        return false
    }

    /**
     * Not startForegroundService: Android 14 refuses a microphone service started with no visible
     * activity, and an accessibility service is not an exemption. RecordActivity is that activity,
     * and it closes before it draws. This service runs in its own process, so it cannot read a fresh
     * copy of the prefs either; the on/off check lives in RecorderService instead.
     */
    private fun startRecordActivity() {
        val intent = Intent(this, RecordActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { startActivity(intent) }.onFailure { it.printStackTrace() }
    }

    override fun onInterrupt() {

    }
}