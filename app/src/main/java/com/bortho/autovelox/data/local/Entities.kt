package com.bortho.autovelox.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of Firestore `speed_cameras/{id}`. `dirty` = local edit not yet
 * confirmed on Firestore (a matching [SyncOpEntity] is pending); `deleted` =
 * tombstone kept until the delete is replicated.
 */
@Entity(
    tableName = "speed_cameras",
    indices = [Index("geohash"), Index("region"), Index("createdBy", "status", "source")],
)
data class SpeedCameraEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lng: Double,
    val speedLimit: Int?,
    val direction: Int?,
    val status: String,
    val source: String,
    val geohash: String,
    /** Italian region this row was downloaded for (phone) or received for (radio). */
    val region: String?,
    val createdBy: String?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val osmNodeId: Long?,
    val osmNodeVersion: Long?,
    val directionReversed: Boolean,
    val dirty: Boolean,
    val deleted: Boolean,
)

/** `users/{uid}/ignored_cameras/{cameraId}` — the personal blacklist. */
@Entity(tableName = "ignored_cameras", primaryKeys = ["uid", "cameraId"])
data class IgnoredCameraEntity(
    val uid: String,
    val cameraId: String,
    val ignoredAt: Long,
    val dirty: Boolean,
    val deleted: Boolean,
)

/** `users/{uid}/queued_ignores/{cameraId}` — "Ignora" tapped while driving, awaiting confirmation on the phone. */
@Entity(tableName = "queued_ignores", primaryKeys = ["uid", "cameraId"])
data class QueuedIgnoreEntity(
    val uid: String,
    val cameraId: String,
    val queuedAt: Long,
    val dirty: Boolean,
    val deleted: Boolean,
)

/** Operation waiting to be replayed against Firestore. See [SyncOpType]. */
@Entity(tableName = "sync_queue", indices = [Index("opType", "targetId")])
data class SyncOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opType: String,
    val targetId: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
)

enum class SyncOpType {
    UPSERT_CAMERA,
    DELETE_CAMERA,
    UPSERT_IGNORED,
    DELETE_IGNORED,
    QUEUE_IGNORE,
    DISMISS_QUEUED_IGNORE,
}

/** What dataset version of a region this device holds (radio: received from the phone). */
@Entity(tableName = "dataset_meta")
data class DatasetMetaEntity(
    @PrimaryKey val region: String,
    /** Download time of the region on the phone, epoch seconds — monotonic, changes only on a fresh OSM download. */
    val version: Long,
    val receivedAt: Long,
    val count: Int,
)
