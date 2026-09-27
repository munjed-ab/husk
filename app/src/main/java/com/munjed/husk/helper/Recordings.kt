package com.munjed.husk.helper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STAMP = "yyyyMMdd-HHmmss"
private const val PREFIX = "husk-"
private const val EXTENSION = "m4a"

/**
 * Recordings live in app-private storage: no MediaStore entry, so no gallery, no file manager and
 * no other app ever sees them, and [backup_rules] keeps them out of cloud backup. Nothing is
 * encrypted on top of that, which means a rooted or unlocked-and-handed-over phone still gives them
 * up. That is the security line Husk draws, and it is drawn on purpose.
 */
fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

/** Newest first. The timestamp name is zero padded, so sorting by name is sorting by time. */
fun recordings(context: Context): List<File> =
    recordingsDir(context).listFiles()
        ?.filter { it.extension == EXTENSION }
        ?.sortedByDescending { it.name }
        ?: emptyList()

fun newRecordingFile(context: Context): File =
    File(recordingsDir(context), PREFIX + SimpleDateFormat(STAMP, Locale.US).format(Date()) + ".$EXTENSION")

/** "18 Sep 14:32", or the bare file name if it was renamed out of Husk's format. */
fun recordingTitle(fileName: String, locale: Locale = Locale.getDefault()): String {
    val stamp = fileName.removePrefix(PREFIX).substringBeforeLast(".")
    val date = runCatching {
        SimpleDateFormat(STAMP, Locale.US).apply { isLenient = false }.parse(stamp)
    }.getOrNull()
    return date?.let { SimpleDateFormat("d MMM HH:mm", locale).format(it) } ?: fileName
}

/** ponytail: size stands in for length, reading real duration means opening every file on every draw. */
/** Playback position as m:ss, or h:mm:ss once a recording runs past the hour. */
fun playbackTime(ms: Int): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun recordingSize(bytes: Long): String =
    if (bytes < 1024 * 1024) "${(bytes + 512) / 1024} KB"
    else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
