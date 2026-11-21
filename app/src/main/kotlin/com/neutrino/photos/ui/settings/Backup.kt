package com.neutrino.photos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.neutrino.photos.graph
import com.neutrino.photos.media.BackupWorker
import com.neutrino.photos.ui.ActionRow
import com.neutrino.photos.ui.ErrorText
import com.neutrino.photos.ui.FormSection
import com.neutrino.photos.ui.PageBar
import com.neutrino.photos.ui.SwitchRow
import com.neutrino.photos.ui.ValueRow
import com.neutrino.photos.ui.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backing up this phone: every photo and video in its library, uploaded encrypted, newest first, in
 * the background. What is already up is remembered, so a run picks up where the last one stopped.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val enabled by prefs.backupEnabled.flow.collectAsState()
    val wifiOnly by prefs.backupWifiOnly.flow.collectAsState()
    val videos by prefs.backupVideos.flow.collectAsState()
    val status by BackupWorker.status.collectAsState()
    var permitted by remember { mutableStateOf(BackupWorker.hasPermission(context)) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val hasKey = graph.account.keys.hasKey

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = BackupWorker.hasPermission(context)
        if (permitted) { prefs.backupEnabled.set(true); BackupWorker.schedule(context, true, prefs.backupWifiOnly.value) }
    }
    LaunchedEffect(permitted, videos, status.running, status.uploaded) {
        pending = withContext(Dispatchers.IO) { BackupWorker.pendingCount(context, videos, graph.ledger::knows) }
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("Back Up This Phone", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FormSection(footer = "Photos are encrypted on this phone before they are uploaded. Only you hold the key.") {
                SwitchRow("Back Up Automatically", enabled && permitted, enabled = hasKey) { on ->
                    if (on && !permitted) ask.launch(BackupWorker.permissions())
                    else { prefs.backupEnabled.set(on); BackupWorker.schedule(context, on, wifiOnly) }
                }
                SwitchRow("Only on Wi-Fi", wifiOnly) { prefs.backupWifiOnly.set(it); if (enabled) BackupWorker.schedule(context, true, it) }
                SwitchRow("Include Videos", videos) { prefs.backupVideos.set(it) }
            }
            if (!hasKey) ErrorText("Bring your encryption key to this phone (Settings › Encryption) to back up.")
            FormSection("Status") {
                ValueRow("Waiting to upload", when {
                    !permitted -> "Photo access needed"
                    pending == null -> "…"
                    else -> "$pending"
                })
                if (status.running) ValueRow("Now", "Uploading… ${status.uploaded} done")
                status.lastRun?.let { ValueRow("Last run", formatDateTime(it)) }
                ValueRow("Backed up from this phone", "${graph.ledger.count}")
                ActionRow("Back Up Now", Icons.Filled.CloudUpload, enabled = hasKey && permitted && !status.running) { BackupWorker.runNow(context, wifiOnly) }
                if (!permitted) ActionRow("Allow Photo Access") { ask.launch(BackupWorker.permissions()) }
            }
            status.error?.let { ErrorText(it) }
        }
    }
}
