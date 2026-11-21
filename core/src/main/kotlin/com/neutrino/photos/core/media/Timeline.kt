package com.neutrino.photos.core.media

import com.neutrino.photos.core.model.MediaItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How densely the timeline groups — iOS `TimelineGrouping`, pinched between. */
enum class Grouping(val label: String, val columns: Int) {
    DAY("Days", 3), MONTH("Months", 5), YEAR("Years", 7);

    val denser get() = entries.getOrElse(ordinal + 1) { this }
    val sparser get() = entries.getOrElse(ordinal - 1) { this }
}

data class TimelineSection(val key: String, val title: String, val subtitle: String?, val items: List<MediaItem>)

object Timeline {

    /**
     * What the library shows: newest first, with the clips of Live Photos hidden — rule 3 of
     * `agent_docs/live-photos.md`. A clip is claimed when a still's `device.liveVideoFileID` names
     * its file, or, failing that, when both carry the same `motionPhoto.contentIdentifier`.
     */
    fun visible(items: List<MediaItem>): List<MediaItem> {
        val claimedFiles = items.mapNotNullTo(HashSet()) { it.liveVideoFileId }
        val stillIdentifiers = items.filter { it.kind == MediaItem.Kind.PHOTO }.mapNotNullTo(HashSet()) { it.motionContentIdentifier }
        return items.filter { item ->
            if (item.kind != MediaItem.Kind.VIDEO) return@filter true
            if (item.fileId in claimedFiles) return@filter false
            val id = item.motionContentIdentifier
            !(id != null && id in stillIdentifiers)
        }.sortedWith(compareByDescending<MediaItem> { it.timelineDate }.thenByDescending { it.createdAt })
    }

    /** The clip a still names, from the whole library (hidden ones included). */
    fun clipOf(still: MediaItem, all: List<MediaItem>): MediaItem? {
        still.liveVideoFileId?.let { file -> return all.firstOrNull { it.fileId == file } }
        val id = still.motionContentIdentifier ?: return null
        return all.firstOrNull { it.id != still.id && it.kind == MediaItem.Kind.VIDEO && it.motionContentIdentifier == id }
    }

    fun sections(items: List<MediaItem>, grouping: Grouping, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<TimelineSection> {
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()
        val dates = HashMap<String, LocalDate>()
        for (item in items) {
            val date = LocalDate.ofInstant(item.timelineDate, zone)
            val key = when (grouping) {
                Grouping.DAY -> date.toString()
                Grouping.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
                Grouping.YEAR -> date.year.toString()
            }
            groups.getOrPut(key) { mutableListOf() } += item
            dates.putIfAbsent(key, date)
        }
        return groups.map { (key, list) -> TimelineSection(key, title(dates[key]!!, grouping, today), subtitle(list), list) }
    }

    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    fun title(date: LocalDate, grouping: Grouping, today: LocalDate): String = when (grouping) {
        Grouping.DAY -> when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> if (date.year == today.year) dayFormat.format(date) else dayYearFormat.format(date)
        }
        Grouping.MONTH -> date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (date.year == today.year) "" else " ${date.year}"
        Grouping.YEAR -> date.year.toString()
    }

    private fun subtitle(items: List<MediaItem>): String {
        val videos = items.count { it.kind == MediaItem.Kind.VIDEO }
        val photos = items.size - videos
        return listOfNotNull(
            photos.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
            videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
        ).joinToString(" · ")
    }
}
