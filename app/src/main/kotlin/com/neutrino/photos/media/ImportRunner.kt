package com.neutrino.photos.media

import com.neutrino.photos.core.service.ImportPipeline
import com.neutrino.photos.core.service.PhotoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.logging.Logger

/**
 * What the picker and shares hand over, imported one item at a time — the iOS `PhotoImportService`.
 * One at a time on purpose: each item is staged, encrypted and uploaded through a temp file, and
 * the queue is what bounds how much of that is on the phone at once.
 */
class ImportRunner(
    private val preparer: MediaPreparer,
    private val pipeline: ImportPipeline,
    private val library: PhotoLibrary,
    private val scope: CoroutineScope,
) {
    data class State(
        val total: Int = 0,
        val done: Int = 0,
        val imported: Int = 0,
        val duplicates: Int = 0,
        val failures: List<String> = emptyList(),
        /** Fraction of the current item's upload. */
        val currentProgress: Float = 0f,
        val running: Boolean = false,
    ) {
        val finished get() = !running && total > 0
        val summary: String get() = buildList {
            if (imported > 0) add(if (imported == 1) "1 item imported" else "$imported items imported")
            if (duplicates > 0) add(if (duplicates == 1) "1 already in your library" else "$duplicates already in your library")
            if (failures.isNotEmpty()) add("${failures.size} failed")
        }.joinToString(" · ").ifEmpty { "Nothing imported" }
    }

    private val log = Logger.getLogger("ImportRunner")
    private val queue = ArrayDeque<MediaPreparer.Source>()
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun enqueue(sources: List<MediaPreparer.Source>) {
        if (sources.isEmpty()) return
        synchronized(queue) { queue.addAll(sources) }
        _state.update { if (it.running) it.copy(total = it.total + sources.size) else State(total = sources.size, running = true) }
        if (job?.isActive != true) job = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (scope.isActive) {
            val source = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            var staged: java.io.File? = null
            try {
                val prepared = withContext(Dispatchers.IO) { preparer.prepare(source) }
                staged = prepared.file
                when (val outcome = pipeline.import(prepared) { sent, total -> _state.update { it.copy(currentProgress = if (total > 0) sent.toFloat() / total else 0f) } }) {
                    is ImportPipeline.Outcome.Imported -> { library.inserted(outcome.item); _state.update { it.copy(imported = it.imported + 1) } }
                    is ImportPipeline.Outcome.Duplicate -> _state.update { it.copy(duplicates = it.duplicates + 1) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning("import failed: ${e.message}")
                _state.update { it.copy(failures = it.failures + (e.message ?: "Import failed")) }
            } finally {
                staged?.delete()
                _state.update { it.copy(done = it.done + 1, currentProgress = 0f) }
            }
        }
        _state.update { it.copy(running = false) }
    }

    /** Stops after the item in flight; what was not started is dropped. */
    fun cancel() {
        synchronized(queue) { queue.clear() }
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    fun dismiss() { if (!_state.value.running) _state.value = State() }
}
