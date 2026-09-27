package com.munjed.husk.helper

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The reliable way in: one swipe and one tap, over any app and from the lock screen, and the tap is
 * a system-blessed start so Android 14 lets the microphone service through. The volume chord is the
 * faster route but it depends on background-start rules the tile does not.
 */
class RecorderTile : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (RecorderService.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        RecorderService.toggle(this)
    }
}
