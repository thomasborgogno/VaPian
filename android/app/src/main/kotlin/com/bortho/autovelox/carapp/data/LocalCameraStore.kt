package com.bortho.autovelox.carapp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bortho.autovelox.carapp.util.GeoHashUtil
import org.json.JSONObject

/** A pending `sync_queue` row — see `lib/data/local/sync_queue_op.dart`'s `SyncOpType` for the [opType] values. */
data class SyncQueueOpRow(val id: Long, val opType: String, val targetId: String, val payload: String)

/**
 * Shared SQLite database also opened by the Dart side
 * (`lib/data/local/app_database.dart`) — same file name, same table/column
 * names, kept in sync by hand since there's no codegen shared across the two
 * languages. Both engines run in this app's single process
 * (`AutoveloxCarAppService` declares no `android:process`), so this and
 * `sqflite` opening the same file just works, no Method Channel involved.
 *
 * [CameraRepository]/[BlacklistRepository] read from here first (works with
 * no network at all) and keep listening to Firestore to refresh it — the
 * same "local-first, Firestore only for sync" split as the Dart
 * repositories, kept independent so native driving alerts still get fresh
 * data even on a pure Android-Auto launch that never opens the Flutter UI
 * (see PLAN.md's native-first decision).
 */
class LocalCameraStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS speed_cameras (
                id TEXT PRIMARY KEY,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                speed_limit INTEGER,
                direction INTEGER,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                geohash TEXT NOT NULL,
                region TEXT,
                created_by TEXT,
                created_at INTEGER,
                updated_at INTEGER,
                osm_node_id INTEGER,
                osm_node_version INTEGER,
                direction_reversed INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ignored_cameras (
                uid TEXT NOT NULL,
                camera_id TEXT NOT NULL,
                ignored_at INTEGER NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uid, camera_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queued_ignores (
                uid TEXT NOT NULL,
                camera_id TEXT NOT NULL,
                queued_at INTEGER NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uid, camera_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                op_type TEXT NOT NULL,
                target_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Matches app_database.dart's WAL setup — lets this and sqflite's
        // connection to the same file read/write concurrently within the
        // process instead of blocking on each other.
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // --- speed_cameras -------------------------------------------------

    fun near(lat: Double, lng: Double, radiusMeters: Double): List<SpeedCamera> {
        val prefixes = GeoHashUtil.neighborPrefixes(lat, lng, radiusMeters)
        val placeholders = prefixes.joinToString(" OR ") { "geohash LIKE ?" }
        val args = prefixes.map { "$it%" }.toTypedArray()
        val cameras = mutableListOf<SpeedCamera>()
        readableDatabase.rawQuery(
            "SELECT * FROM speed_cameras WHERE deleted = 0 AND ($placeholders)",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val camera = cursor.toSpeedCamera()
                if (GeoHashUtil.haversineDistanceMeters(lat, lng, camera.lat, camera.lng) <= radiusMeters) {
                    cameras.add(camera)
                }
            }
        }
        return cameras
    }

    /** Upserts cameras pulled from a Firestore snapshot — never marks them dirty (they're already synced). */
    fun upsertFromRemote(cameras: List<SpeedCamera>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (camera in cameras) {
                db.insertWithOnConflict("speed_cameras", null, camera.toContentValues(dirty = false), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** "Segnala Velox" from the car — local-first: written here, pushed to Firestore by [NativeSyncDrainer]. */
    fun insertPendingUserCamera(
        id: String,
        lat: Double,
        lng: Double,
        limitKmh: Int,
        directionDeg: Int,
        geohash: String,
        createdBy: String,
    ) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("lat", lat)
            put("lng", lng)
            put("speed_limit", limitKmh)
            put("direction", directionDeg)
            put("status", "pending")
            put("source", "user")
            put("geohash", geohash)
            put("created_by", createdBy)
            put("created_at", now)
            put("updated_at", now)
            put("direction_reversed", 0)
            put("dirty", 1)
            put("deleted", 0)
        }
        writableDatabase.insertWithOnConflict("speed_cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        enqueue("upsertCamera", id, JSONObject())
    }

    // --- ignored_cameras / queued_ignores -------------------------------

    fun ignoredCameraIds(uid: String): Set<String> {
        val ids = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT camera_id FROM ignored_cameras WHERE uid = ? AND deleted = 0",
            arrayOf(uid),
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    fun upsertIgnoredFromRemote(uid: String, cameraIds: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("ignored_cameras", "uid = ?", arrayOf(uid))
            for (cameraId in cameraIds) {
                val values = ContentValues().apply {
                    put("uid", uid)
                    put("camera_id", cameraId)
                    put("ignored_at", System.currentTimeMillis())
                    put("dirty", 0)
                    put("deleted", 0)
                }
                db.insertWithOnConflict("ignored_cameras", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** "Ignora Velox" from the car (features.md 3.3.1) — queued locally, not written straight to the blacklist. */
    fun queueIgnoreLocal(uid: String, cameraId: String) {
        val values = ContentValues().apply {
            put("uid", uid)
            put("camera_id", cameraId)
            put("queued_at", System.currentTimeMillis())
            put("dirty", 1)
            put("deleted", 0)
        }
        writableDatabase.insertWithOnConflict("queued_ignores", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val payload = JSONObject().apply {
            put("uid", uid)
            put("cameraId", cameraId)
        }
        enqueue("queueIgnore", "$uid/$cameraId", payload)
    }

    // --- sync_queue ------------------------------------------------------

    private fun enqueue(opType: String, targetId: String, payload: JSONObject) {
        val values = ContentValues().apply {
            put("op_type", opType)
            put("target_id", targetId)
            put("payload", payload.toString())
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
        }
        writableDatabase.insert("sync_queue", null, values)
    }

    /** All pending ops, oldest first — read by [NativeSyncDrainer]. */
    fun pendingSyncOps(): List<SyncQueueOpRow> {
        val ops = mutableListOf<SyncQueueOpRow>()
        readableDatabase.rawQuery("SELECT id, op_type, target_id, payload FROM sync_queue ORDER BY id", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    ops.add(
                        SyncQueueOpRow(
                            id = cursor.getLong(0),
                            opType = cursor.getString(1),
                            targetId = cursor.getString(2),
                            payload = cursor.getString(3),
                        ),
                    )
                }
            }
        return ops
    }

    fun removeSyncOp(id: Long) {
        writableDatabase.delete("sync_queue", "id = ?", arrayOf(id.toString()))
    }

    fun markSyncOpAttempted(id: Long) {
        writableDatabase.execSQL("UPDATE sync_queue SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
    }

    /** Full `speed_cameras` row for [id], as a Firestore-ready map — null if the id isn't known locally. */
    fun speedCameraFirestoreData(id: String): Map<String, Any?>? {
        readableDatabase.rawQuery("SELECT * FROM speed_cameras WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val data = mutableMapOf<String, Any?>(
                "lat" to cursor.getDouble(cursor.getColumnIndexOrThrow("lat")),
                "lng" to cursor.getDouble(cursor.getColumnIndexOrThrow("lng")),
                "status" to cursor.getString(cursor.getColumnIndexOrThrow("status")),
                "source" to cursor.getString(cursor.getColumnIndexOrThrow("source")),
                "geohash" to cursor.getString(cursor.getColumnIndexOrThrow("geohash")),
                "directionReversed" to (cursor.getInt(cursor.getColumnIndexOrThrow("direction_reversed")) != 0),
            )
            val limitIndex = cursor.getColumnIndexOrThrow("speed_limit")
            if (!cursor.isNull(limitIndex)) data["limit"] = cursor.getInt(limitIndex)
            val directionIndex = cursor.getColumnIndexOrThrow("direction")
            if (!cursor.isNull(directionIndex)) data["direction"] = cursor.getInt(directionIndex)
            val createdByIndex = cursor.getColumnIndexOrThrow("created_by")
            if (!cursor.isNull(createdByIndex)) data["createdBy"] = cursor.getString(createdByIndex)
            val osmNodeIdIndex = cursor.getColumnIndexOrThrow("osm_node_id")
            if (!cursor.isNull(osmNodeIdIndex)) data["osmNodeId"] = cursor.getInt(osmNodeIdIndex)
            val osmNodeVersionIndex = cursor.getColumnIndexOrThrow("osm_node_version")
            if (!cursor.isNull(osmNodeVersionIndex)) data["osmNodeVersion"] = cursor.getInt(osmNodeVersionIndex)
            data["updatedAt"] = com.google.firebase.Timestamp.now()
            return data
        }
    }

    /** `ignored_at` for [uid]/[cameraId], as millis — null if not known locally. */
    fun ignoredAtMillis(uid: String, cameraId: String): Long? {
        readableDatabase.rawQuery(
            "SELECT ignored_at FROM ignored_cameras WHERE uid = ? AND camera_id = ?",
            arrayOf(uid, cameraId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    /** `queued_at` for [uid]/[cameraId], as millis — null if not known locally. */
    fun queuedAtMillis(uid: String, cameraId: String): Long? {
        readableDatabase.rawQuery(
            "SELECT queued_at FROM queued_ignores WHERE uid = ? AND camera_id = ?",
            arrayOf(uid, cameraId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    private fun Cursor.toSpeedCamera(): SpeedCamera {
        return SpeedCamera(
            id = getString(getColumnIndexOrThrow("id")),
            lat = getDouble(getColumnIndexOrThrow("lat")),
            lng = getDouble(getColumnIndexOrThrow("lng")),
            limit = if (isNull(getColumnIndexOrThrow("speed_limit"))) null else getInt(getColumnIndexOrThrow("speed_limit")),
            direction = if (isNull(getColumnIndexOrThrow("direction"))) null else getInt(getColumnIndexOrThrow("direction")),
            status = getString(getColumnIndexOrThrow("status")),
            source = getString(getColumnIndexOrThrow("source")),
            geohash = getString(getColumnIndexOrThrow("geohash")),
        )
    }

    private fun SpeedCamera.toContentValues(dirty: Boolean): ContentValues {
        return ContentValues().apply {
            put("id", id)
            put("lat", lat)
            put("lng", lng)
            put("speed_limit", limit)
            put("direction", direction)
            put("status", status)
            put("source", source)
            put("geohash", geohash)
            put("updated_at", System.currentTimeMillis())
            put("dirty", if (dirty) 1 else 0)
            put("deleted", 0)
        }
    }

    companion object {
        private const val DATABASE_NAME = "autovelox_shared.db"
        private const val DATABASE_VERSION = 1

        @Volatile private var instance: LocalCameraStore? = null

        fun getInstance(context: Context): LocalCameraStore {
            return instance ?: synchronized(this) {
                instance ?: LocalCameraStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
