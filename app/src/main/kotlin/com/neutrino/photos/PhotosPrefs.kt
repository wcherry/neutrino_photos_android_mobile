package com.neutrino.photos

import android.content.SharedPreferences
import com.neutrino.photos.core.media.Grouping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppTheme(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The user's preferences, in the account's own settings file under `nphoto.` keys — the iOS app's prefix. */
class PhotosPrefs(private val prefs: SharedPreferences) {

    private fun <T> pref(initial: T, write: (SharedPreferences.Editor, T) -> Unit) = Pref(MutableStateFlow(initial)) { v -> prefs.edit().also { write(it, v) }.apply() }

    class Pref<T>(private val state: MutableStateFlow<T>, private val write: (T) -> Unit) {
        val flow: StateFlow<T> get() = state
        val value: T get() = state.value
        fun set(v: T) { write(v); state.value = v }
    }

    val theme = pref(AppTheme.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: AppTheme.SYSTEM) { e, v -> e.putString(THEME, v.name) }
    val grouping = pref(Grouping.entries.firstOrNull { it.name == prefs.getString(GROUPING, null) } ?: Grouping.DAY) { e, v -> e.putString(GROUPING, v.name) }
    /** Off by default: the server stores metadata in the clear, and coordinates are where somebody has been. */
    val publishLocation = pref(prefs.getBoolean(LOCATION, false)) { e, v -> e.putBoolean(LOCATION, v) }
    val backupEnabled = pref(prefs.getBoolean(BACKUP, false)) { e, v -> e.putBoolean(BACKUP, v) }
    val backupWifiOnly = pref(prefs.getBoolean(WIFI_ONLY, true)) { e, v -> e.putBoolean(WIFI_ONLY, v) }
    val backupVideos = pref(prefs.getBoolean(BACKUP_VIDEOS, true)) { e, v -> e.putBoolean(BACKUP_VIDEOS, v) }
    /** Megabytes of decrypted photos kept on the device. */
    val cacheMegabytes = pref(prefs.getInt(CACHE, DEFAULT_CACHE_MB)) { e, v -> e.putInt(CACHE, v) }

    fun resetToDefaults() {
        theme.set(AppTheme.SYSTEM); grouping.set(Grouping.DAY); publishLocation.set(false)
        backupWifiOnly.set(true); backupVideos.set(true); cacheMegabytes.set(DEFAULT_CACHE_MB)
    }

    companion object {
        const val DEFAULT_CACHE_MB = 2048
        val CACHE_OPTIONS = listOf(512, 1024, 2048, 5120, 10240)
        fun cacheLabel(mb: Int) = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

        private const val THEME = "nphoto.theme"
        private const val GROUPING = "nphoto.grouping"
        private const val LOCATION = "nphoto.publish_location"
        private const val BACKUP = "nphoto.backup_enabled"
        private const val WIFI_ONLY = "nphoto.backup_wifi_only"
        private const val BACKUP_VIDEOS = "nphoto.backup_videos"
        private const val CACHE = "nphoto.cache_mb"
    }
}
