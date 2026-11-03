package com.bortho.autovelox.data

import com.bortho.autovelox.core.geo.Geo
import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.data.local.IgnoredCameraEntity
import com.bortho.autovelox.data.local.QueuedIgnoreEntity
import com.bortho.autovelox.data.local.SyncOpType
import com.bortho.autovelox.data.local.VaPianDatabase
import com.bortho.autovelox.data.local.toEntity
import com.bortho.autovelox.data.local.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single access point to cameras, blacklist and queued ignores. Reads come
 * only from Room (works with no network at all); every write goes to Room
 * and a coalesced [SyncOpType] row, replayed by the Firestore sync engine
 * (phone role only). The radio never talks to Firestore.
 */
@Singleton
class CameraRepository @Inject constructor(
    private val db: VaPianDatabase,
    private val clock: Clock,
) {
    private val cameras get() = db.cameras()

    // --- cameras ---------------------------------------------------------

    /** Cameras within [radiusMeters] of the point, live. */
    fun observeNear(lat: Double, lng: Double, radiusMeters: Double): Flow<List<SpeedCamera>> {
        val p = padded(GeoHash.neighborPrefixes(lat, lng, radiusMeters))
        return cameras.observeByPrefixes(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
            .map { rows -> rows.map { it.toModel() }.filter { Geo.haversineMeters(lat, lng, it.lat, it.lng) <= radiusMeters } }
    }

    suspend fun near(lat: Double, lng: Double, radiusMeters: Double): List<SpeedCamera> {
        val p = padded(GeoHash.neighborPrefixes(lat, lng, radiusMeters))
        return cameras.getByPrefixes(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
            .map { it.toModel() }
            .filter { Geo.haversineMeters(lat, lng, it.lat, it.lng) <= radiusMeters }
    }

    fun observeAll(): Flow<List<SpeedCamera>> = cameras.observeAll().map { rows -> rows.map { it.toModel() } }

    fun observeOwnPending(uid: String): Flow<List<SpeedCamera>> =
        cameras.observeOwnPending(uid).map { rows -> rows.map { it.toModel() } }

    /** Rows pulled from Firestore or a dataset download. Never overwrites rows with unsynced local edits. */
    suspend fun mergeRemote(remote: List<SpeedCamera>, region: String? = null) {
        cameras.mergeRemote(remote.map { it.toEntity(region = region) })
    }

    /** Atomic dataset swap for one region (OSM download on the phone, BLE transfer on the radio). */
    suspend fun replaceRegion(region: String, cameras: List<SpeedCamera>) {
        this.cameras.replaceRegion(region, cameras.map { it.toEntity(region = region) })
    }

    /**
     * Quick "Segnala" report (phone or radio): pending, user-sourced, id doubles
     * as the idempotency key. Returns the stored camera.
     */
    suspend fun addPendingUserCamera(
        lat: Double,
        lng: Double,
        limitKmh: Int?,
        directionDeg: Int?,
        createdBy: String,
        id: String = UUID.randomUUID().toString(),
    ): SpeedCamera {
        val now = clock.nowMillis()
        val camera = SpeedCamera(
            id = id,
            lat = lat,
            lng = lng,
            limit = limitKmh,
            direction = directionDeg,
            status = CameraStatus.PENDING,
            source = CameraSource.USER,
            geohash = GeoHash.encode(lat, lng),
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
        )
        saveUserCamera(camera)
        return camera
    }

    /** Insert or edit a camera locally (dirty) and queue its replication. */
    /** Records that this camera now exists as an OpenStreetMap node (replicated like any other edit). */
    suspend fun markPublishedToOsm(id: String, nodeId: Long) {
        val current = camera(id) ?: return
        saveUserCamera(current.copy(osmNodeId = nodeId, osmNodeVersion = 1))
    }

    suspend fun saveUserCamera(camera: SpeedCamera) {
        val stamped = camera.copy(updatedAt = clock.nowMillis())
        cameras.upsert(stamped.toEntity(dirty = true))
        db.syncQueue().enqueue(SyncOpType.UPSERT_CAMERA, camera.id, "{}", clock.nowMillis())
    }

    /** Remove a camera: tombstone locally until Firestore confirms. */
    suspend fun deleteCamera(id: String) {
        if (cameras.get(id) == null) return
        // Firestore deleting a never-replicated doc is a harmless no-op, so no special case.
        cameras.tombstone(id)
        db.syncQueue().removeByTarget(SyncOpType.UPSERT_CAMERA.name, id)
        db.syncQueue().enqueue(SyncOpType.DELETE_CAMERA, id, "{}", clock.nowMillis())
    }

    suspend fun exists(id: String): Boolean = cameras.get(id) != null

    /** (lat, lng) around which the stored cameras lie, or null when nothing is stored yet. */
    suspend fun dataCenter(): Pair<Double, Double>? =
        cameras.centroid()?.let { c -> if (c.lat != null && c.lng != null) c.lat to c.lng else null }

    suspend fun camera(id: String): SpeedCamera? = cameras.get(id)?.toModel()

    /**
     * Review of a pending report (features.md 2.1): promotes it to `approved`, optionally correcting the
     * limit and direction, and queues the change for replication.
     */
    suspend fun confirm(id: String, limitKmh: Int?, directionDeg: Int?) {
        val current = camera(id) ?: return
        saveUserCamera(
            current.copy(
                status = CameraStatus.APPROVED,
                limit = limitKmh ?: current.limit,
                direction = directionDeg ?: current.direction,
            ),
        )
    }

    /**
     * Saves everything the draft overlay can change: position (so the geohash is recomputed), limit, direction,
     * the "opposite travel direction" flag, and optionally the review approval of a pending report.
     */
    suspend fun updateCamera(
        id: String,
        lat: Double,
        lng: Double,
        limitKmh: Int?,
        directionDeg: Int?,
        reversed: Boolean,
        approve: Boolean,
    ) {
        val current = camera(id) ?: return
        saveUserCamera(
            current.copy(
                lat = lat,
                lng = lng,
                geohash = GeoHash.encode(lat, lng),
                limit = limitKmh,
                direction = directionDeg,
                directionReversed = reversed,
                status = if (approve) CameraStatus.APPROVED else current.status,
            ),
        )
    }

    /** Edits limit/direction of a camera the user owns, queued for replication. */
    suspend fun edit(id: String, limitKmh: Int?, directionDeg: Int?, reversed: Boolean) {
        val current = camera(id) ?: return
        saveUserCamera(current.copy(limit = limitKmh, direction = directionDeg, directionReversed = reversed))
    }

    /** A camera placed by hand on the map: an explicit user action, so it is `approved` straight away. */
    suspend fun addApprovedUserCamera(lat: Double, lng: Double, limitKmh: Int?, directionDeg: Int?, createdBy: String): SpeedCamera {
        val now = clock.nowMillis()
        val camera = SpeedCamera(
            id = UUID.randomUUID().toString(),
            lat = lat,
            lng = lng,
            limit = limitKmh,
            direction = directionDeg,
            status = CameraStatus.APPROVED,
            source = CameraSource.USER,
            geohash = GeoHash.encode(lat, lng),
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
        )
        saveUserCamera(camera)
        return camera
    }

    // --- blacklist & queued ignores --------------------------------------

    fun observeIgnoredIds(uid: String): Flow<Set<String>> = db.ignored().observeIds(uid).map { it.toSet() }

    suspend fun ignoredIds(uid: String): Set<String> = db.ignored().ids(uid).toSet()

    suspend fun ignore(uid: String, cameraId: String) {
        val now = clock.nowMillis()
        db.ignored().upsert(IgnoredCameraEntity(uid, cameraId, now, dirty = true, deleted = false))
        db.syncQueue().removeByTarget(SyncOpType.DELETE_IGNORED.name, "$uid/$cameraId")
        db.syncQueue().enqueue(SyncOpType.UPSERT_IGNORED, "$uid/$cameraId", "{}", now)
    }

    suspend fun unignore(uid: String, cameraId: String) {
        db.ignored().tombstone(uid, cameraId)
        db.syncQueue().removeByTarget(SyncOpType.UPSERT_IGNORED.name, "$uid/$cameraId")
        db.syncQueue().enqueue(SyncOpType.DELETE_IGNORED, "$uid/$cameraId", "{}", clock.nowMillis())
    }

    /** Replace the clean blacklist with a snapshot (remote on the phone, BLE on the radio). */
    suspend fun replaceIgnored(uid: String, ids: Collection<String>) {
        db.ignored().replaceFromRemote(uid, ids, clock.nowMillis())
    }

    fun observeQueuedIgnores(uid: String): Flow<List<QueuedIgnoreEntity>> = db.queuedIgnores().observe(uid)

    /** "Ignora velox" tapped while driving: queued, confirmed or dismissed later on the phone. */
    suspend fun queueIgnore(uid: String, cameraId: String) {
        val now = clock.nowMillis()
        db.queuedIgnores().upsert(QueuedIgnoreEntity(uid, cameraId, now, dirty = true, deleted = false))
        db.syncQueue().enqueue(SyncOpType.QUEUE_IGNORE, "$uid/$cameraId", "{}", now)
    }

    /** Confirm: promotes the queued ignore to the permanent blacklist. */
    suspend fun confirmQueuedIgnore(uid: String, cameraId: String) {
        ignore(uid, cameraId)
        dismissQueuedIgnore(uid, cameraId)
    }

    suspend fun dismissQueuedIgnore(uid: String, cameraId: String) {
        db.queuedIgnores().tombstone(uid, cameraId)
        db.syncQueue().removeByTarget(SyncOpType.QUEUE_IGNORE.name, "$uid/$cameraId")
        db.syncQueue().enqueue(SyncOpType.DISMISS_QUEUED_IGNORE, "$uid/$cameraId", "{}", clock.nowMillis())
    }

    private fun padded(prefixes: Set<String>): List<String> {
        val list = prefixes.toList()
        return List(9) { list[it % list.size] }
    }
}

/** Injectable time source (tests, and a single place to fake "now"). */
fun interface Clock {
    fun nowMillis(): Long
}
