package com.bortho.autovelox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SpeedCameraEntity::class,
        IgnoredCameraEntity::class,
        QueuedIgnoreEntity::class,
        SyncOpEntity::class,
        DatasetMetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaPianDatabase : RoomDatabase() {
    abstract fun cameras(): SpeedCameraDao
    abstract fun ignored(): IgnoredDao
    abstract fun queuedIgnores(): QueuedIgnoreDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun datasetMeta(): DatasetMetaDao

    companion object {
        const val NAME = "vapian.db"
    }
}
