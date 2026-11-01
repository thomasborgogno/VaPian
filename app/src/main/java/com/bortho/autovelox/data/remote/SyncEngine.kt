package com.bortho.autovelox.data.remote

import android.util.Log
import com.bortho.autovelox.auth.UserSession
import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.local.SyncOpEntity
import com.bortho.autovelox.data.local.SyncOpType
import com.bortho.autovelox.data.local.VaPianDatabase
import com.bortho.autovelox.data.local.toEntity
import com.bortho.autovelox.data.local.toModel
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only component that talks to Firestore (phone role). Room is the source
 * of truth: writes land in Room plus `sync_queue`; [drain] replays the queue
 * once connectivity returns, and the pull functions bring remote changes into
 * Room. Every replay is idempotent (`set(merge)` / `delete`), so draining
 * twice or after a crash is safe.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: VaPianDatabase,
    private val session: UserSession,
) {
    private val firestore get() = FirebaseFirestore.getInstance()
    private val drainLock = Mutex()

    /** Replays pending operations, oldest first. Returns true when the queue ended up empty. */
    suspend fun drain(): Boolean = drainLock.withLock { drainLocked() }

    private suspend fun drainLocked(): Boolean {
        val uid = session.ensureFirebaseUid() ?: return false
        val queue = db.syncQueue()
        while (true) {
            val pending = queue.pending()
            if (pending.isEmpty()) return true
            var progressed = false
            for (op in pending) {
                try {
                    replay(op, uid)
                    queue.remove(op.id)
                    progressed = true
                } catch (e: Exception) {
                    Log.w(TAG, "replay failed for ${op.opType}/${op.targetId}", e)
                    queue.markAttempted(op.id)
                }
            }
            // A pass with no success means offline or a persistently failing op: stop, retry on next trigger.
            if (!progressed) return false
        }
    }

    private suspend fun replay(op: SyncOpEntity, firebaseUid: String) {
        val local = session.localKey
        when (SyncOpType.valueOf(op.opType)) {
            SyncOpType.UPSERT_CAMERA -> {
                val row = db.cameras().get(op.targetId) ?: return
                if (row.deleted) return
                val camera = row.toModel()
                firestore.collection(FirestorePaths.SPEED_CAMERAS).document(camera.id)
                    .set(camera.toFirestoreMap(local, firebaseUid), SetOptions.merge()).await()
                db.cameras().markClean(camera.id)
            }
            SyncOpType.DELETE_CAMERA -> {
                firestore.collection(FirestorePaths.SPEED_CAMERAS).document(op.targetId).delete().await()
                db.cameras().hardDelete(op.targetId)
            }
            SyncOpType.UPSERT_IGNORED -> {
                val cameraId = cameraIdOf(op)
                val row = db.ignored().get(local, cameraId) ?: return
                userCollection(firebaseUid, FirestorePaths.IGNORED).document(cameraId)
                    .set(mapOf("ignoredAt" to Timestamp(Date(row.ignoredAt)))).await()
                db.ignored().markClean(local, cameraId)
            }
            SyncOpType.DELETE_IGNORED -> {
                val cameraId = cameraIdOf(op)
                userCollection(firebaseUid, FirestorePaths.IGNORED).document(cameraId).delete().await()
                db.ignored().hardDelete(local, cameraId)
            }
            SyncOpType.QUEUE_IGNORE -> {
                val cameraId = cameraIdOf(op)
                val row = db.queuedIgnores().get(local, cameraId) ?: return
                userCollection(firebaseUid, FirestorePaths.QUEUED_IGNORES).document(cameraId)
                    .set(mapOf("queuedAt" to Timestamp(Date(row.queuedAt)))).await()
                db.queuedIgnores().markClean(local, cameraId)
            }
            SyncOpType.DISMISS_QUEUED_IGNORE -> {
                val cameraId = cameraIdOf(op)
                userCollection(firebaseUid, FirestorePaths.QUEUED_IGNORES).document(cameraId).delete().await()
                db.queuedIgnores().hardDelete(local, cameraId)
            }
        }
    }

    /** Targets of user-scoped ops are "<localKey>/<cameraId>". */
    private fun cameraIdOf(op: SyncOpEntity) = op.targetId.substringAfter('/')

    private fun userCollection(firebaseUid: String, name: String) =
        firestore.collection(FirestorePaths.USERS).document(firebaseUid).collection(name)

    /** Pulls cameras around a point into Room (skips rows with unsynced local edits). */
    suspend fun pullNear(lat: Double, lng: Double, radiusMeters: Double, region: String? = null) {
        session.ensureFirebaseUid() ?: return
        val collection = firestore.collection(FirestorePaths.SPEED_CAMERAS)
        for (prefix in GeoHash.neighborPrefixes(lat, lng, radiusMeters)) {
            val snapshot = collection
                .whereGreaterThanOrEqualTo("geohash", prefix)
                // U+F8FF sorts above every base32 character: matches all full-precision hashes with this prefix.
                .whereLessThanOrEqualTo("geohash", prefix + HIGH_SURROGATE_CHAR)
                .get().await()
            mergeSnapshot(snapshot.documents.mapNotNull { it.toSpeedCamera() }, region)
        }
    }

    /** Pulls the user's blacklist and settings; returns the settings. */
    suspend fun pullUserState(): UserSettings? {
        val uid = session.ensureFirebaseUid() ?: return null
        val ids = userCollection(uid, FirestorePaths.IGNORED).get().await().documents.map { it.id }
        db.ignored().replaceFromRemote(session.localKey, ids, System.currentTimeMillis())
        val doc = firestore.collection(FirestorePaths.USERS).document(uid).get().await()
        return doc.toUserSettings()
    }

    private suspend fun mergeSnapshot(cameras: List<SpeedCamera>, region: String?) {
        db.cameras().mergeRemote(cameras.map { it.toEntity(region = region) })
    }

    /** Writes OSM-sourced cameras of a region (a fresh Overpass download) to Firestore, then Room. */
    suspend fun publishOsmCameras(cameras: List<SpeedCamera>) {
        session.ensureFirebaseUid() ?: return
        val collection = firestore.collection(FirestorePaths.SPEED_CAMERAS)
        val now = Timestamp.now()
        cameras.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            for (camera in chunk) {
                val data = camera.toFirestoreMap(session.localKey, "").toMutableMap()
                data["updatedAt"] = now
                batch.set(collection.document(camera.id), data, SetOptions.merge())
            }
            batch.commit().await()
        }
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val HIGH_SURROGATE_CHAR = ""
        const val BATCH_LIMIT = 400
    }
}
