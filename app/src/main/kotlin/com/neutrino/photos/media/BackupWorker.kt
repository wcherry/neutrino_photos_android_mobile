package com.neutrino.photos.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neutrino.photos.core.service.ImportPipeline
import com.neutrino.photos.graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Backs up this phone's library: every photo and video in MediaStore that this device has not
 * uploaded yet, newest first, through the same pipeline as an import — the iOS full-library import,
 * run by WorkManager so it continues with the app closed. Resumable by construction: what is done is
 * in the ledger (by MediaStore id), so a run that is stopped picks up where it was on the next one.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    data class Status(val running: Boolean = false, val remaining: Int? = null, val uploaded: Int = 0, val lastRun: Instant? = null, val error: String? = null)

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (!graph.account.auth.isSignedIn || !graph.account.keys.hasKey) return Result.success()
        if (!hasPermission(applicationContext)) { status.update { it.copy(error = "Allow access to your photos to back them up.") }; return Result.success() }
        val videos = graph.prefs.backupVideos.value
        val pending = scan(applicationContext, videos).filter { !graph.ledger.knows(it.mediaStoreId.toString()) }
        status.value = Status(running = true, remaining = pending.size, lastRun = status.value.lastRun)
        var uploaded = 0
        try {
            for (source in pending) {
                if (isStopped) break
                var staged: java.io.File? = null
                try {
                    val prepared = graph.preparer.prepare(source)
                    staged = prepared.file
                    val outcome = graph.pipeline.import(prepared)
                    if (outcome is ImportPipeline.Outcome.Imported) { uploaded++; graph.library.inserted(outcome.item) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: MediaPreparer.Unsupported) {
                    // Nothing to retry; skipped until the library changes.
                } catch (e: Exception) {
                    status.update { it.copy(error = e.message) }
                    return Result.retry()
                } finally {
                    staged?.delete()
                    status.update { it.copy(remaining = (it.remaining ?: 1) - 1, uploaded = uploaded) }
                }
            }
            status.update { it.copy(error = null) }
            return Result.success()
        } finally {
            status.update { it.copy(running = false, lastRun = Instant.now()) }
        }
    }

    companion object {
        private const val PERIODIC = "photos-backup"
        private const val NOW = "photos-backup-now"
        val status = MutableStateFlow(Status())

        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            Build.VERSION.SDK_INT >= 29 -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasPermission(context: Context): Boolean {
            val needed = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
            return context.checkSelfPermission(needed) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()

        /**
         * On: a run every few hours — the first of them straight away, so this is the only request.
         * Off: nothing scheduled. (A second, immediate request here ran beside the periodic one's
         * first run, and both uploaded everything.)
         */
        fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(NOW); return }
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS).setConstraints(constraints(wifiOnly)).build())
        }

        fun runNow(context: Context, wifiOnly: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(wifiOnly)).build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }

        /** The device library, newest first. */
        fun scan(context: Context, videos: Boolean): List<MediaPreparer.Source> {
            val out = ArrayList<MediaPreparer.Source>()
            val collections = buildList {
                add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                if (videos) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
            for (collection in collections) {
                val projection = buildList {
                    add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DATE_TAKEN)
                    if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
                }.toTypedArray()
                context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val taken = c.getLong(1).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                        val favorite = Build.VERSION.SDK_INT >= 30 && c.getInt(2) == 1
                        out += MediaPreparer.Source(ContentUris.withAppendedId(collection, id), id, favorite, taken)
                    }
                }
            }
            return out.sortedByDescending { it.dateTaken }
        }

        /** How much of the device library is not backed up yet. */
        fun pendingCount(context: Context, videos: Boolean, knows: (String) -> Boolean): Int? =
            if (!hasPermission(context)) null else runCatching { scan(context, videos).count { !knows(it.mediaStoreId.toString()) } }.getOrNull()
    }
}
