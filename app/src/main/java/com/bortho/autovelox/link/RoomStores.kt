package com.bortho.autovelox.link

import com.bortho.autovelox.auth.UserSession
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import com.bortho.autovelox.data.CameraRepository
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.local.DatasetMetaEntity
import com.bortho.autovelox.data.local.SyncOpType
import com.bortho.autovelox.data.local.VaPianDatabase
import com.bortho.autovelox.data.local.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** Pseudo-region carrying the cameras reported by users, which belong to no OSM region. */
const val USER_REGION = "_segnalazioni"

/** Phone side: serves the radio from Room. */
@Singleton
class RoomPhoneStore @Inject constructor(
    private val db: VaPianDatabase,
    private val repository: CameraRepository,
    private val session: UserSession,
    private val settings: SettingsStore,
) : PhoneStore {

    override suspend fun saveReport(report: ReportCamera): Boolean {
        if (repository.exists(report.id)) return false
        repository.addPendingUserCamera(
            lat = report.lat,
            lng = report.lng,
            limitKmh = report.limitKmh.takeIf { it > 0 },
            directionDeg = report.bearingDeg.toInt().takeIf { it in 0..359 },
            createdBy = session.localKey, // mapped to the real Firebase uid by the sync engine
            id = report.id,
        )
        return true
    }

    override suspend fun queueIgnore(cameraId: String, ts: Long) {
        repository.queueIgnore(session.localKey, cameraId)
    }

    override suspend fun regions(): List<RegionDataset> {
        val regions = db.datasetMeta().all().map { meta ->
            RegionDataset(meta.region, meta.version, db.cameras().inRegion(meta.region).map { it.toModel() })
        }
        val user = db.cameras().approvedUserCameras()
        if (user.isEmpty()) return regions
        val version = (db.cameras().latestApprovedUserUpdate() ?: 0L) / 1000
        return regions + RegionDataset(USER_REGION, version, user.map { it.toModel() })
    }

    override suspend fun blacklist(): List<String> = repository.ignoredIds(session.localKey).toList()

    override suspend fun settings(): UserSettings = settings.alertSettings.value
}

/** Radio side: outbox, versions and dataset swap on Room. */
@Singleton
class RoomRadioStore @Inject constructor(
    private val db: VaPianDatabase,
    private val repository: CameraRepository,
    private val session: UserSession,
    private val settings: SettingsStore,
) : RadioStore {

    override suspend fun pendingReports(): List<ReportCamera> =
        db.cameras().dirtyOwnReports(session.localKey).map { row ->
            ReportCamera(
                id = row.id,
                lat = row.lat,
                lng = row.lng,
                speedKmh = 0.0,
                bearingDeg = (row.direction ?: 0).toDouble(),
                limitKmh = row.speedLimit ?: 0,
                ts = row.createdAt ?: System.currentTimeMillis(),
                deviceId = session.deviceId,
            )
        }

    override suspend fun pendingIgnores(): List<ReportIgnore> =
        db.queuedIgnores().dirty(session.localKey).map { ReportIgnore(it.cameraId, it.queuedAt) }

    override suspend fun markReportSent(id: String) {
        val row = db.cameras().get(id) ?: return
        if (!row.dirty) return
        db.cameras().markClean(id)
        db.syncQueue().removeByTarget(SyncOpType.UPSERT_CAMERA.name, id)
    }

    override suspend fun markIgnoreSent(cameraId: String) {
        val key = session.localKey
        if (db.queuedIgnores().get(key, cameraId)?.dirty != true) return
        db.queuedIgnores().markClean(key, cameraId)
        db.syncQueue().removeByTarget(SyncOpType.QUEUE_IGNORE.name, "$key/$cameraId")
    }

    override suspend fun haveVersions(): Map<String, Long> = db.datasetMeta().all().associate { it.region to it.version }

    override suspend fun applyRegion(name: String, version: Long, cameras: List<SpeedCamera>) {
        repository.replaceRegion(name, cameras)
        db.datasetMeta().upsert(DatasetMetaEntity(name, version, System.currentTimeMillis(), cameras.size))
    }

    override suspend fun replaceBlacklist(cameraIds: List<String>) {
        repository.replaceIgnored(session.localKey, cameraIds)
    }

    override suspend fun saveSettings(settings: UserSettings) {
        this.settings.saveAlertSettings(settings)
    }

    override suspend fun markDatasetSynced(nowMs: Long) {
        settings.lastDatasetSyncAt = nowMs
    }
}
