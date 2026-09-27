package com.munjed.husk

import com.munjed.husk.helper.playbackTime
import com.munjed.husk.helper.recordingSize
import com.munjed.husk.helper.recordingTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Locale
import org.junit.Test

class RecordingsTest {

    @Test
    fun `husk name becomes a readable timestamp`() {
        // the month abbreviation is the platform's ("Sep" on Android, "Sept" on a desktop JVM), so
        // the day and the time are what this pins down
        val title = recordingTitle("husk-20260918-143205.m4a", Locale.UK)
        assertTrue(title, title.startsWith("18 Sep") && title.endsWith(" 14:32"))
    }

    @Test
    fun `a name Husk did not write falls back to itself`() {
        assertEquals("voice note.m4a", recordingTitle("voice note.m4a", Locale.UK))
        assertEquals("husk-nonsense.m4a", recordingTitle("husk-nonsense.m4a", Locale.UK))
        // a real-looking stamp that is not a real date must not parse into a wrong one
        assertEquals("husk-20261345-143205.m4a", recordingTitle("husk-20261345-143205.m4a", Locale.UK))
    }

    @Test
    fun `size reads in whole KB below a megabyte and one decimal above`() {
        assertEquals("1 KB", recordingSize(1024))
        assertEquals("64 KB", recordingSize(65_536))
        assertEquals("1.0 MB", recordingSize(1024 * 1024))
        assertEquals("2.5 MB", recordingSize(2_621_440))
    }

    @Test
    fun `playback time reads like a player, hours only past the hour`() {
        assertEquals("0:00", playbackTime(0))
        assertEquals("0:00", playbackTime(-5)) // a player reports a negative position on some devices
        assertEquals("1:05", playbackTime(65_400))
        assertEquals("59:59", playbackTime(3_599_999))
        assertEquals("1:53:05", playbackTime(6_785_000))
    }
}
