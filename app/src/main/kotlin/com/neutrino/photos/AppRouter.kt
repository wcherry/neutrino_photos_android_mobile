package com.neutrino.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neutrino.photos.core.model.Album

/** The bottom bar, as iOS: Library, Albums, Settings. Favorites, Archive and Trash are collections under Albums. */
enum class Tab(val label: String) { LIBRARY("Library"), ALBUMS("Albums"), SETTINGS("Settings") }

/** Which items a viewer pages through. */
enum class Collection(val title: String) { LIBRARY("Library"), FAVORITES("Favorites"), VIDEOS("Videos"), ARCHIVE("Archive"), TRASH("Recently Deleted"), RECENTLY_ADDED("Recently Added") }

sealed class Route {
    /** One photo full screen, paging through [ids]. */
    data class Viewer(val ids: List<String>, val start: Int, val albumId: String? = null) : Route()
    data class AlbumDetail(val album: Album) : Route()
    data class CollectionView(val collection: Collection) : Route()
    object Devices : Route()
    object AppLockSettings : Route()
    object Pairing : Route()
    object RecoveryKit : Route()
    object KeyCode : Route()
    object SetUpEncryption : Route()
    object Backup : Route()
}

/** Where the app is showing, for what arrives from outside the tabs — a link naming a photo, a share. */
class AppRouter {
    var tab by mutableStateOf(Tab.LIBRARY)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }

    fun stack(tab: Tab): List<Route> = stacks.getValue(tab)
    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    fun push(route: Route, on: Tab = tab) {
        tab = on
        stacks.getValue(on).add(route)
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popWhile(predicate: (Route) -> Boolean) {
        val stack = stacks.getValue(tab)
        while (stack.isNotEmpty() && predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }

    fun select(tab: Tab) {
        if (this.tab == tab) stacks.getValue(tab).clear()
        this.tab = tab
    }

    /** A `/open/photo/<file id>` link waiting for the library. */
    var pendingLink by mutableStateOf<String?>(null)
    var linkError by mutableStateOf<String?>(null)
    /** Media another app shared here, waiting to be imported once signed in. */
    var pendingShares by mutableStateOf<List<android.net.Uri>>(emptyList())

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.LIBRARY
        pendingLink = null
        linkError = null
        pendingShares = emptyList()
    }
}
