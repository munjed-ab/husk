package app.olauncher.helper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.olauncher.data.Prefs

/** Daily top-up of the reading queue. Manual refresh calls [refreshReadingList] directly. */
class ReadingWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(context)
        if (!prefs.readingEnabled || prefs.readingTopics.isEmpty()) return Result.success()
        return try {
            refreshReadingList(context)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
