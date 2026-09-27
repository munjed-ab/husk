package com.munjed.husk.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.munjed.husk.helper.RecorderService

/**
 * One invisible frame, whose only job is to be visible.
 *
 * Android 14 will not start a microphone foreground service for an app with no visible activity, and
 * an accessibility service does not count. So the volume chord opens this, it flips the recorder on
 * while it is on screen, and it closes again before it draws anything. Shows over the lock screen on
 * purpose: an emergency recording that needs a PIN first is not an emergency recording.
 */
class RecordActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    override fun onResume() {
        super.onResume()
        // onResume, not onCreate: "visible activity" is what the platform checks, and this is where
        // that becomes true. Whatever app was in front loses focus for a frame; that is the price.
        RecorderService.toggle(this)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
