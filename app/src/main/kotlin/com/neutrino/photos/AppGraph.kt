package com.neutrino.photos

import android.content.Context
import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.service.AlbumService
import com.neutrino.photos.core.service.DeviceSessionService
import com.neutrino.photos.core.service.ImportLedger
import com.neutrino.photos.core.service.ImportPipeline
import com.neutrino.photos.core.service.MediaContentService
import com.neutrino.photos.core.service.PhotoLibrary
import com.neutrino.photos.core.service.Uploader
import com.neutrino.photos.media.BackupWorker
import com.neutrino.photos.media.ImportRunner
import com.neutrino.photos.media.MediaPreparer
import com.neutrino.photos.ui.ThumbnailMemory
import com.neutrino.shared.android.NeutrinoAccount
import com.neutrino.shared.android.net.NetworkMonitor
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Every long-lived object, built once per process. Main-thread confined. */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    val account = NeutrinoAccount(this.context, NeutrinoApps.photos, "NeutrinoPhotos-Android/${BuildConfig.VERSION_NAME}") { http, auth -> PhotosApi(http, auth) }
    val api: PhotosApi get() = account.api
    val prefs = PhotosPrefs(account.settings.prefs)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val network = NetworkMonitor(this.context)
    val isOnline: StateFlow<Boolean> = network.isOnline

    // Decrypted media is disposable and large: the cache directory, which the system may clear.
    // The ledger and the listing are small and worth keeping: no-backup files.
    private val dataDir = File(this.context.noBackupFilesDir, "photos")
    val content = MediaContentService(account.api, { account.keys.keyring() }, File(this.context.cacheDir, "photos"), prefs.cacheMegabytes.value * 1024L * 1024L)
    val ledger = ImportLedger(File(dataDir, "ledger.json"))
    val library = PhotoLibrary(account.api, scope, File(dataDir, "library.json"))
    val albums = AlbumService(account.api, scope)
    val uploader = Uploader(account.api, account.keyCheck, File(this.context.cacheDir, "uploads"))
    val pipeline = ImportPipeline(account.api, uploader, content, ledger) { prefs.publishLocation.value }
    val preparer = MediaPreparer(this.context, File(this.context.cacheDir, "staging"))
    val imports = ImportRunner(preparer, pipeline, library, scope)
    val sessions = DeviceSessionService(account.api, scope) { account.auth.config.headerSafeDeviceName(account.settings.deviceName) }
    val thumbnails = ThumbnailMemory(content)
    val router = AppRouter()

    init {
        account.onSignOut {
            imports.cancel()
            BackupWorker.cancel(this.context)
            prefs.backupEnabled.set(false)
            library.reset()
            albums.reset()
            content.clear()
            ledger.clear()
            thumbnails.clear()
            File(this.context.cacheDir, "exports").deleteRecursively()
            File(this.context.cacheDir, "staging").deleteRecursively()
            router.reset()
        }
        network.start()
    }
}
