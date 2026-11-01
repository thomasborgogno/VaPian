package com.bortho.autovelox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Average position of a set of rows; both values are null when there are none. */
data class Centroid(val lat: Double?, val lng: Double?)

@Dao
abstract class SpeedCameraDao {
    /**
     * Rows whose geohash starts with any of up to 9 prefixes (a cell and its
     * neighbors, see GeoHash.neighborPrefixes). Callers pad with repeats.
     */
    @Query(
        """
        SELECT * FROM speed_cameras WHERE deleted = 0 AND (
            geohash LIKE :p0 || '%' OR geohash LIKE :p1 || '%' OR geohash LIKE :p2 || '%' OR
            geohash LIKE :p3 || '%' OR geohash LIKE :p4 || '%' OR geohash LIKE :p5 || '%' OR
            geohash LIKE :p6 || '%' OR geohash LIKE :p7 || '%' OR geohash LIKE :p8 || '%')
        """,
    )
    abstract fun observeByPrefixes(
        p0: String, p1: String, p2: String, p3: String, p4: String,
        p5: String, p6: String, p7: String, p8: String,
    ): Flow<List<SpeedCameraEntity>>

    @Query(
        """
        SELECT * FROM speed_cameras WHERE deleted = 0 AND (
            geohash LIKE :p0 || '%' OR geohash LIKE :p1 || '%' OR geohash LIKE :p2 || '%' OR
            geohash LIKE :p3 || '%' OR geohash LIKE :p4 || '%' OR geohash LIKE :p5 || '%' OR
            geohash LIKE :p6 || '%' OR geohash LIKE :p7 || '%' OR geohash LIKE :p8 || '%')
        """,
    )
    abstract suspend fun getByPrefixes(
        p0: String, p1: String, p2: String, p3: String, p4: String,
        p5: String, p6: String, p7: String, p8: String,
    ): List<SpeedCameraEntity>

    @Query("SELECT * FROM speed_cameras WHERE id = :id")
    abstract suspend fun get(id: String): SpeedCameraEntity?

    @Query("SELECT * FROM speed_cameras WHERE deleted = 0")
    abstract fun observeAll(): Flow<List<SpeedCameraEntity>>

    @Query("SELECT * FROM speed_cameras WHERE deleted = 0 AND createdBy = :uid AND source = 'user' AND status = 'pending'")
    abstract fun observeOwnPending(uid: String): Flow<List<SpeedCameraEntity>>

    @Query("SELECT id FROM speed_cameras WHERE dirty = 1 AND id IN (:ids)")
    abstract suspend fun dirtyIdsAmong(ids: List<String>): List<String>

    @Upsert
    abstract suspend fun upsert(cameras: List<SpeedCameraEntity>)

    @Upsert
    abstract suspend fun upsert(camera: SpeedCameraEntity)

    @Query("UPDATE speed_cameras SET dirty = 0 WHERE id = :id")
    abstract suspend fun markClean(id: String)

    /**
     * Radio: its own reports that the phone acknowledged and that did not come back as an approved camera
     * (region set) were deleted or never confirmed on the phone. They never alert, so they are dropped
     * after each complete sync instead of lingering forever.
     */
    @Query("DELETE FROM speed_cameras WHERE source = 'user' AND dirty = 0 AND region IS NULL")
    abstract suspend fun deleteStaleOwnReports()

    @Query("UPDATE speed_cameras SET deleted = 1, dirty = 1 WHERE id = :id")
    abstract suspend fun tombstone(id: String)

    @Query("DELETE FROM speed_cameras WHERE id = :id")
    abstract suspend fun hardDelete(id: String)

    @Query("DELETE FROM speed_cameras WHERE region = :region AND dirty = 0")
    abstract suspend fun deleteCleanInRegion(region: String)

    @Query("SELECT COUNT(*) FROM speed_cameras WHERE region = :region AND deleted = 0")
    abstract suspend fun countInRegion(region: String): Int

    /** Average position of everything stored, used to open the map on the user's data when there is no GPS fix. */
    @Query("SELECT AVG(lat) AS lat, AVG(lng) AS lng FROM speed_cameras WHERE deleted = 0")
    abstract suspend fun centroid(): Centroid?

    @Query("SELECT * FROM speed_cameras WHERE region = :region AND deleted = 0")
    abstract suspend fun inRegion(region: String): List<SpeedCameraEntity>

    /** Everything users reported that is usable for alerts (approved) — shipped to the radio as a pseudo-region. */
    @Query("SELECT * FROM speed_cameras WHERE source = 'user' AND status = 'approved' AND deleted = 0")
    abstract suspend fun approvedUserCameras(): List<SpeedCameraEntity>

    @Query("SELECT MAX(updatedAt) FROM speed_cameras WHERE source = 'user' AND status = 'approved' AND deleted = 0")
    abstract suspend fun latestApprovedUserUpdate(): Long?

    /** Reports created on this device and not yet confirmed (radio: waiting for the phone's ack). */
    @Query("SELECT * FROM speed_cameras WHERE source = 'user' AND dirty = 1 AND deleted = 0 AND createdBy = :localKey")
    abstract suspend fun dirtyOwnReports(localKey: String): List<SpeedCameraEntity>

    /**
     * Merge a batch coming from Firestore or a region download without
     * clobbering local unsynced edits: rows that are `dirty` are skipped.
     * Existing `region` is kept when the incoming row doesn't carry one.
     */
    @Transaction
    open suspend fun mergeRemote(cameras: List<SpeedCameraEntity>) {
        if (cameras.isEmpty()) return
        val dirty = cameras.chunked(SQLITE_MAX_VARS).flatMap { dirtyIdsAmong(it.map(SpeedCameraEntity::id)) }.toSet()
        val toWrite = cameras.filter { it.id !in dirty }.map { incoming ->
            if (incoming.region != null) incoming else get(incoming.id)?.region?.let { incoming.copy(region = it) } ?: incoming
        }
        upsert(toWrite)
    }

    /**
     * Replace the clean rows of [region] with [cameras] in one transaction
     * (a dataset swap): an interrupted transfer never leaves a half-applied region.
     */
    @Transaction
    open suspend fun replaceRegion(region: String, cameras: List<SpeedCameraEntity>) {
        deleteCleanInRegion(region)
        mergeRemote(cameras.map { it.copy(region = region) })
    }

    private companion object {
        const val SQLITE_MAX_VARS = 900
    }
}

@Dao
interface IgnoredDao {
    @Query("SELECT cameraId FROM ignored_cameras WHERE uid = :uid AND deleted = 0")
    fun observeIds(uid: String): Flow<List<String>>

    @Query("SELECT cameraId FROM ignored_cameras WHERE uid = :uid AND deleted = 0")
    suspend fun ids(uid: String): List<String>

    @Query("SELECT * FROM ignored_cameras WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun get(uid: String, cameraId: String): IgnoredCameraEntity?

    @Upsert
    suspend fun upsert(entity: IgnoredCameraEntity)

    @Query("UPDATE ignored_cameras SET deleted = 1, dirty = 1 WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun tombstone(uid: String, cameraId: String)

    @Query("DELETE FROM ignored_cameras WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun hardDelete(uid: String, cameraId: String)

    @Query("UPDATE ignored_cameras SET dirty = 0 WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun markClean(uid: String, cameraId: String)

    @Query("DELETE FROM ignored_cameras WHERE uid = :uid AND dirty = 0")
    suspend fun deleteClean(uid: String)

    /** Replace the clean set with a remote snapshot (keeps rows with unsynced local edits). */
    @Transaction
    suspend fun replaceFromRemote(uid: String, cameraIds: Collection<String>, now: Long) {
        deleteClean(uid)
        for (id in cameraIds) {
            if (get(uid, id)?.dirty == true) continue
            upsert(IgnoredCameraEntity(uid, id, now, dirty = false, deleted = false))
        }
    }
}

@Dao
interface QueuedIgnoreDao {
    @Query("SELECT * FROM queued_ignores WHERE uid = :uid AND deleted = 0 ORDER BY queuedAt")
    fun observe(uid: String): Flow<List<QueuedIgnoreEntity>>

    @Query("SELECT * FROM queued_ignores WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun get(uid: String, cameraId: String): QueuedIgnoreEntity?

    @Upsert
    suspend fun upsert(entity: QueuedIgnoreEntity)

    @Query("UPDATE queued_ignores SET deleted = 1, dirty = 1 WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun tombstone(uid: String, cameraId: String)

    @Query("DELETE FROM queued_ignores WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun hardDelete(uid: String, cameraId: String)

    @Query("UPDATE queued_ignores SET dirty = 0 WHERE uid = :uid AND cameraId = :cameraId")
    suspend fun markClean(uid: String, cameraId: String)

    @Query("SELECT * FROM queued_ignores WHERE uid = :uid AND dirty = 1 AND deleted = 0")
    suspend fun dirty(uid: String): List<QueuedIgnoreEntity>
}

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: SyncOpEntity): Long

    @Query("SELECT * FROM sync_queue ORDER BY id")
    suspend fun pending(): List<SyncOpEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM sync_queue WHERE opType = :opType AND targetId = :targetId")
    suspend fun removeByTarget(opType: String, targetId: String)

    @Query("DELETE FROM sync_queue")
    suspend fun clear()

    @Query("UPDATE sync_queue SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markAttempted(id: Long)

    /**
     * Enqueue, coalescing repeated edits of the same target: an older pending
     * op of the same type/target is superseded by the new one.
     */
    @Transaction
    suspend fun enqueue(opType: SyncOpType, targetId: String, payload: String, now: Long) {
        removeByTarget(opType.name, targetId)
        insert(SyncOpEntity(opType = opType.name, targetId = targetId, payload = payload, createdAt = now))
    }
}

@Dao
interface DatasetMetaDao {
    @Query("SELECT * FROM dataset_meta")
    fun observeAll(): Flow<List<DatasetMetaEntity>>

    @Query("SELECT * FROM dataset_meta")
    suspend fun all(): List<DatasetMetaEntity>

    @Upsert
    suspend fun upsert(meta: DatasetMetaEntity)

    @Query("DELETE FROM dataset_meta WHERE region = :region")
    suspend fun delete(region: String)
}
