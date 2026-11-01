package com.bortho.autovelox.data

import com.bortho.autovelox.data.local.DatasetMetaEntity
import com.bortho.autovelox.data.local.VaPianDatabase
import com.bortho.autovelox.data.remote.OverpassClient
import com.bortho.autovelox.data.remote.OverpassException
import com.bortho.autovelox.data.remote.SyncEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Phone only: downloads one region from OSM into Room (the source of what is later sent to the radio). */
@Singleton
class RegionDownloader @Inject constructor(
    private val overpass: OverpassClient,
    private val repository: CameraRepository,
    private val db: VaPianDatabase,
    private val settings: SettingsStore,
    private val sync: SyncEngine,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Ok(val count: Int) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun download(region: String): Result {
        val cameras = try {
            overpass.fetchSpeedCameras(region)
        } catch (e: OverpassException) {
            return Result.Failed(e.message ?: "Download non riuscito")
        }
        val now = clock.nowMillis()
        // Swap the region's clean rows and bump its version in one logical step: the version is what tells the
        // radio that this region changed.
        repository.replaceRegion(region, cameras)
        db.datasetMeta().upsert(DatasetMetaEntity(region, version = now / 1000, receivedAt = now, count = cameras.size))
        settings.lastOsmDownloadAt = now
        // Mirroring to Firestore is best effort: offline is fine, the data is already usable locally.
        runCatching { sync.publishOsmCameras(cameras) }
        return Result.Ok(cameras.size)
    }
}
