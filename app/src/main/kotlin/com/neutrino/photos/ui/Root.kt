package com.neutrino.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.photos.AppGraph
import com.neutrino.photos.Route
import com.neutrino.photos.Tab
import com.neutrino.shared.core.sync.SyncActivity
import com.neutrino.photos.graph
import com.neutrino.photos.media.MediaPreparer
import com.neutrino.photos.ui.albums.AlbumDetailScreen
import com.neutrino.photos.ui.albums.AlbumsScreen
import com.neutrino.photos.ui.albums.CollectionScreen
import com.neutrino.photos.ui.library.LibraryScreen
import com.neutrino.photos.ui.settings.BackupScreen
import com.neutrino.photos.ui.viewer.ViewerScreen
import com.neutrino.photos.ui.settings.AppLockScreen
import com.neutrino.photos.ui.settings.DevicesScreen
import com.neutrino.photos.ui.settings.SettingsScreen
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConnectivityBanner
import com.neutrino.shared.android.ui.LockScreen
import com.neutrino.shared.android.ui.auth.LoginScreen
import com.neutrino.shared.android.ui.auth.RegisterScreen
import com.neutrino.shared.android.ui.keys.EncryptionSetupScreen
import com.neutrino.shared.android.ui.keys.KeyCodeScreen
import com.neutrino.shared.android.ui.keys.PairingScreen
import com.neutrino.shared.android.ui.keys.RecoveryKitScreen

/** Signed out → sign-in; just registered → encryption setup; otherwise the tabs. The lock sits over all of it. */
@Composable
fun Root() {
    val graph = LocalContext.current.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val locked by AppLock.locked.collectAsState()
    var justRegistered by rememberSaveable { mutableStateOf(false) }
    var registering by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            !signedIn -> if (registering) {
                BackHandler { registering = false }
                RegisterScreen(onBack = { registering = false }, onRegistered = { justRegistered = true; registering = false })
            } else {
                LoginScreen(onRegister = { registering = true })
            }
            justRegistered -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(top = 32.dp)) { EncryptionSetupScreen(onDone = { justRegistered = false }) }
            }
            // Keyed on the account, so a different sign-in never sees the last one's screens.
            else -> key(graph.account.auth.userId) { Home(graph) }
        }
        if (locked && signedIn) LockScreen()
    }
}

@Composable
private fun Home(graph: AppGraph) {
    val router = graph.router

    // A `/open/photo/<file id>` link: opened once the library can say whether it holds that photo.
    val items by graph.library.items.collectAsState()
    LaunchedEffect(router.pendingLink, items.isNotEmpty()) {
        val fileId = router.pendingLink ?: return@LaunchedEffect
        if (items.isEmpty()) graph.library.load()
        router.pendingLink = null
        val item = graph.library.items.value.firstOrNull { it.fileId == fileId }
            ?: runCatching { graph.api.photosForFiles(listOf(fileId)).firstOrNull() }.getOrNull()?.let(com.neutrino.photos.core.model.MediaItem::of)
        if (item == null || item.deletedAt != null) router.linkError = "This photo isn’t in your library, or it has been deleted."
        else router.push(Route.Viewer(listOf(item.id), 0), com.neutrino.photos.Tab.LIBRARY)
    }
    // Media another app shared here.
    LaunchedEffect(router.pendingShares) {
        val shares = router.pendingShares
        if (shares.isEmpty()) return@LaunchedEffect
        router.pendingShares = emptyList()
        router.select(com.neutrino.photos.Tab.LIBRARY)
        graph.imports.enqueue(shares.map { MediaPreparer.Source(it) })
    }

    BackHandler(enabled = router.current != null) { router.pop() }

    val fullScreen = router.current is Route.Viewer
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // A photo takes the whole screen: the tab bar is for choosing what to open.
            if (!fullScreen) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = router.tab == tab, onClick = { router.select(tab) }, label = { Text(tab.label) },
                        icon = {
                            val icon = when (tab) {
                                Tab.LIBRARY -> Icons.Filled.PhotoLibrary
                                Tab.ALBUMS -> Icons.Filled.Collections
                                Tab.SETTINGS -> Icons.Filled.Settings
                            }
                            Icon(icon, null)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            if (!fullScreen) Connectivity(graph)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val route = router.current
                if (route == null) TabRoot(router.tab) else RouteContent(route)
            }
        }
    }

    router.linkError?.let { message ->
        AlertDialog(
            onDismissRequest = { router.linkError = null },
            title = { Text("Couldn’t Open Photo") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { router.linkError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TabRoot(tab: Tab) = when (tab) {
    Tab.LIBRARY -> LibraryScreen()
    Tab.ALBUMS -> AlbumsScreen()
    Tab.SETTINGS -> SettingsScreen()
}

@Composable
private fun RouteContent(route: Route) {
    val router = LocalContext.current.graph.router
    val back: () -> Unit = { router.pop() }
    when (route) {
        is Route.Viewer -> ViewerScreen(route.ids, route.start, route.albumId, back)
        is Route.AlbumDetail -> key(route.album.id) { AlbumDetailScreen(route.album, back) }
        is Route.CollectionView -> CollectionScreen(route.collection, back)
        Route.Backup -> BackupScreen(back)
        Route.Devices -> DevicesScreen(back)
        Route.AppLockSettings -> AppLockScreen(back)
        Route.Pairing -> KeyFlow(back) { PairingScreen(it) }
        Route.RecoveryKit -> KeyFlow(back) { RecoveryKitScreen(it) }
        Route.KeyCode -> KeyFlow(back) { KeyCodeScreen(it) }
        Route.SetUpEncryption -> KeyFlow(back) { EncryptionSetupScreen(it) }
    }
}

/** A shared key screen inside the app: back closes it, finishing drops every key screen. */
@Composable
private fun KeyFlow(onBack: () -> Unit, content: @Composable (onDone: () -> Unit) -> Unit) {
    val graph = LocalContext.current.graph
    Column(Modifier.fillMaxSize()) {
        PageBar("", onBack)
        Box(Modifier.weight(1f)) {
            content {
                graph.account.keyringChanged()
                graph.router.popWhile { it is Route.Pairing || it is Route.RecoveryKit || it is Route.KeyCode || it is Route.SetUpEncryption }
            }
        }
    }
}

/** Offline, and "Back online" when the connection returns. Uploads wait for the connection rather than queue, so there is nothing to count. */
@Composable
private fun Connectivity(graph: AppGraph) {
    val online by graph.isOnline.collectAsState()
    ConnectivityBanner(isOnline = online, pendingChanges = 0, activity = SyncActivity.Idle, onRetry = {})
}
