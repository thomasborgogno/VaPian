package com.bortho.autovelox.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bortho.autovelox.auth.UserSession
import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.CameraRepository
import com.bortho.autovelox.data.remote.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The part of the world currently on screen. */
data class Viewport(val lat: Double, val lng: Double, val radiusMeters: Double, val zoom: Float)

/** A camera as drawn on the map. */
data class MapCamera(val camera: SpeedCamera, val ignored: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val session: UserSession,
    private val sync: SyncEngine,
) : ViewModel() {
    private val viewport = MutableStateFlow<Viewport?>(null)
    val showIgnored = MutableStateFlow(false)

    private val ignoredIds: StateFlow<Set<String>> =
        repository.observeIgnoredIds(session.localKey).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Own reports waiting for review (captured quickly from the radio or the car). */
    val pending: StateFlow<List<SpeedCamera>> =
        repository.observeOwnPending(session.localKey).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val inView: StateFlow<List<SpeedCamera>> = viewport.flatMapLatest { vp ->
        if (vp == null || vp.zoom < MIN_ZOOM) {
            flowOf(emptyList())
        } else {
            repository.observeNear(vp.lat, vp.lng, vp.radiusMeters).map { list ->
                list.sortedBy { Geo.haversineMeters(vp.lat, vp.lng, it.lat, it.lng) }.take(MAX_MARKERS)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cameras: StateFlow<List<MapCamera>> = combine(inView, ignoredIds, showIgnored) { list, ignored, withIgnored ->
        list.map { MapCamera(it, it.id in ignored) }.filter { withIgnored || !it.ignored }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when zoomed out too far to draw markers (the screen then says so). */
    val tooFar: StateFlow<Boolean> = viewport.map { it != null && it.zoom < MIN_ZOOM }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun dataCenter(): Pair<Double, Double>? = repository.dataCenter()

    fun onViewport(value: Viewport) {
        viewport.value = value
    }

    fun ignore(id: String) = mutate { repository.ignore(session.localKey, id) }
    fun unignore(id: String) = mutate { repository.unignore(session.localKey, id) }
    fun delete(id: String) = mutate { repository.deleteCamera(id) }
    fun confirm(id: String, limit: Int?, direction: Int?) = mutate { repository.confirm(id, limit, direction) }
    fun edit(id: String, limit: Int?, direction: Int?, reversed: Boolean) = mutate { repository.edit(id, limit, direction, reversed) }
    fun add(lat: Double, lng: Double, limit: Int?, direction: Int?) =
        mutate { repository.addApprovedUserCamera(lat, lng, limit, direction, session.localKey) }

    /** True if [camera] was created on this install (the only ones that may be edited or deleted). */
    fun isOwn(camera: SpeedCamera): Boolean = camera.createdBy == session.localKey

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            // replication is best effort: the change is already visible locally
            runCatching { sync.drain() }
        }
    }

    companion object {
        /** Below this zoom a viewport covers too much ground to draw markers usefully. */
        const val MIN_ZOOM = 9f
        const val MAX_MARKERS = 300
    }
}
