package com.bortho.autovelox.link

import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import com.bortho.autovelox.core.model.UserSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One end of an in-memory pipe: bytes written on one side are parsed into frames on the other. */
private class PipeTransport(private val outgoing: Channel<ByteArray>, private val incoming: Channel<ByteArray>) : FrameTransport {
    private val reader = FrameReader()
    private val pending = ArrayDeque<Frame>()
    val sentTypes = mutableListOf<Int>()

    override suspend fun send(frameBytes: ByteArray) {
        sentTypes += (frameBytes[2].toInt() and 0xFF)
        outgoing.send(frameBytes)
    }

    override suspend fun receive(): Frame? {
        while (pending.isEmpty()) {
            val bytes = incoming.receiveCatching().getOrNull() ?: return null
            pending.addAll(reader.feed(bytes))
        }
        return pending.removeFirst()
    }

    fun close() {
        outgoing.close()
    }
}

private fun pipe(): Pair<PipeTransport, PipeTransport> {
    val a = Channel<ByteArray>(Channel.UNLIMITED)
    val b = Channel<ByteArray>(Channel.UNLIMITED)
    return PipeTransport(a, b) to PipeTransport(b, a)
}

private fun camera(id: String, lat: Double = 45.0, lng: Double = 9.0) = SpeedCamera(
    id, lat, lng, 70, 90, CameraStatus.APPROVED, CameraSource.OPENSTREETMAP, GeoHash.encode(lat, lng), updatedAt = 1_700_000_000_000,
)

private class FakePhoneStore(var regions: List<RegionDataset> = emptyList()) : PhoneStore {
    val reports = mutableMapOf<String, ReportCamera>()
    val ignores = mutableListOf<String>()
    override suspend fun saveReport(report: ReportCamera): Boolean = reports.putIfAbsent(report.id, report) == null
    override suspend fun queueIgnore(cameraId: String, ts: Long) { ignores += cameraId }
    override suspend fun regions() = regions
    override suspend fun blacklist() = listOf("osm_1", "osm_2")
    override suspend fun settings() = UserSettings(AlertDistanceMode.DYNAMIC, 450, false)
}

private class FakeRadioStore(
    var reports: MutableList<ReportCamera> = mutableListOf(),
    var ignores: MutableList<ReportIgnore> = mutableListOf(),
    val have: MutableMap<String, Long> = mutableMapOf(),
) : RadioStore {
    val applied = mutableMapOf<String, List<SpeedCamera>>()
    var blacklist: List<String>? = null
    var settings: UserSettings? = null
    var syncedAt: Long? = null
    override suspend fun pendingReports() = reports.toList()
    override suspend fun pendingIgnores() = ignores.toList()
    override suspend fun markReportSent(id: String) { reports.removeAll { it.id == id } }
    override suspend fun markIgnoreSent(cameraId: String) { ignores.removeAll { it.cameraId == cameraId } }
    override suspend fun haveVersions() = have.toMap()
    override suspend fun applyRegion(name: String, version: Long, cameras: List<SpeedCamera>) {
        applied[name] = cameras
        have[name] = version
    }
    override suspend fun replaceBlacklist(cameraIds: List<String>) { blacklist = cameraIds }
    override suspend fun saveSettings(settings: UserSettings) { this.settings = settings }
    override suspend fun markDatasetSynced(nowMs: Long) { syncedAt = nowMs }
}

private fun report(id: String, ts: Long = 1_000_000L) = ReportCamera(id, 45.0, 9.0, 88.0, 90.0, 90, ts, "radio-1")

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsTest {
    @Test
    fun reportsAreStoredOnceAcknowledgedAndRemovedFromTheOutbox() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val radioStore = FakeRadioStore(reports = mutableListOf(report("r1")), ignores = mutableListOf(ReportIgnore("osm_9", 5)))
        val done = CompletableDeferred<Unit>()
        launch { PhoneLinkSession(phoneT, phoneStore, "phone-1") { 1_000_000L }.run() }
        launch { RadioLinkSession(radioT, radioStore, "radio-1", "1.0", { 1_000_000L }, onSyncComplete = { done.complete(Unit) }).run() }
        advanceUntilIdle()
        done.await()

        assertEquals(setOf("r1"), phoneStore.reports.keys)
        assertEquals(listOf("osm_9"), phoneStore.ignores)
        assertTrue(radioStore.reports.isEmpty())
        assertTrue(radioStore.ignores.isEmpty())
        phoneT.close(); radioT.close()
    }

    @Test
    fun retransmittedReportIsAcknowledgedWithoutDuplicating() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val radioStore = FakeRadioStore(reports = mutableListOf(report("r1")))
        launch { PhoneLinkSession(phoneT, phoneStore, "phone-1") { 1_000_000L }.run() }
        val radio = RadioLinkSession(radioT, radioStore, "radio-1", "1.0", { 1_000_000L })
        launch { radio.run() }
        advanceUntilIdle()
        radioStore.reports += report("r1") // e.g. the ack was lost and the outbox still holds it
        radio.flushOutbox()
        advanceUntilIdle()
        assertEquals(1, phoneStore.reports.size)
        assertTrue(radioStore.reports.isEmpty())
        phoneT.close(); radioT.close()
    }

    @Test
    fun secondNewReportWithinTwoSecondsIsRateLimitedAndStaysQueued() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val radioStore = FakeRadioStore(reports = mutableListOf(report("r1"), report("r2")))
        launch { PhoneLinkSession(phoneT, phoneStore, "phone-1") { 1_000_000L }.run() }
        launch { RadioLinkSession(radioT, radioStore, "radio-1", "1.0", { 1_000_000L }).run() }
        advanceUntilIdle()
        assertEquals(setOf("r1"), phoneStore.reports.keys)
        assertEquals(listOf("r2"), radioStore.reports.map { it.id })
        phoneT.close(); radioT.close()
    }

    @Test
    fun invalidReportsAreRejected() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val badLat = report("bad").copy(lat = 123.0)
        val future = report("future", ts = 1_000_000L + 10 * 60 * 1000)
        val radioStore = FakeRadioStore(reports = mutableListOf(badLat, future))
        launch { PhoneLinkSession(phoneT, phoneStore, "phone-1") { 1_000_000L }.run() }
        val radio = RadioLinkSession(radioT, radioStore, "radio-1", "1.0", { 1_000_000L })
        launch { radio.run() }
        advanceUntilIdle()
        assertTrue(phoneStore.reports.isEmpty())
        assertEquals(2, radio.protocolErrors)
        phoneT.close(); radioT.close()
    }

    @Test
    fun datasetSyncFetchesOnlyNewerRegionsAndAppliesBlacklistAndSettings() = runTest {
        val (phoneT, radioT) = pipe()
        val big = (1..3000).map { camera("osm_$it", 45.0 + it * 1e-4, 9.0 + it * 1e-4) } // > 1 chunk
        val phoneStore = FakePhoneStore(
            listOf(
                RegionDataset("Lombardia", 200, big),
                RegionDataset("Veneto", 300, listOf(camera("osm_90000000001"))),
                RegionDataset("Lazio", 100, listOf(camera("osm_5"))),
            ),
        )
        val radioStore = FakeRadioStore(have = mutableMapOf("Lazio" to 100L, "Veneto" to 250L))
        val done = CompletableDeferred<Unit>()
        launch { PhoneLinkSession(phoneT, phoneStore, "phone-1") { 1L }.run() }
        launch { RadioLinkSession(radioT, radioStore, "radio-1", "1.0", { 42L }, onSyncComplete = { done.complete(Unit) }).run() }
        advanceUntilIdle()
        done.await()

        assertEquals(setOf("Lombardia", "Veneto"), radioStore.applied.keys) // Lazio is up to date: not transferred
        assertEquals(3000, radioStore.applied["Lombardia"]!!.size)
        assertEquals("osm_90000000001", radioStore.applied["Veneto"]!!.single().id)
        assertEquals(300L, radioStore.have["Veneto"])
        assertEquals(listOf("osm_1", "osm_2"), radioStore.blacklist)
        assertEquals(UserSettings(AlertDistanceMode.DYNAMIC, 450, false), radioStore.settings)
        assertEquals(42L, radioStore.syncedAt)
        phoneT.close(); radioT.close()
    }

    @Test
    fun userRegionFilterLimitsWhatIsFetched() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore(listOf(RegionDataset("Lombardia", 1, listOf(camera("osm_1"))), RegionDataset("Veneto", 1, listOf(camera("osm_2")))))
        val radioStore = FakeRadioStore()
        val done = CompletableDeferred<Unit>()
        launch { PhoneLinkSession(phoneT, phoneStore, "p") { 1L }.run() }
        launch { RadioLinkSession(radioT, radioStore, "r", "1.0", { 1L }, wantedRegions = setOf("Veneto"), onSyncComplete = { done.complete(Unit) }).run() }
        advanceUntilIdle()
        done.await()
        assertEquals(setOf("Veneto"), radioStore.applied.keys)
        phoneT.close(); radioT.close()
    }

    @Test
    fun corruptDatasetIsDiscardedAndSyncIsNotMarkedComplete() = runTest {
        val (phoneT, radioT) = pipe()
        val radioStore = FakeRadioStore(have = mutableMapOf("Lombardia" to 1L))
        val synced = CompletableDeferred<Unit>()
        val radio = RadioLinkSession(radioT, radioStore, "r", "1.0", { 7L }, onSyncComplete = { synced.complete(Unit) })
        launch { radio.run() }
        val good = CameraRecords.encode(listOf(camera("osm_1")))
        // a "phone" that announces the wrong CRC for the region it then sends
        phoneT.send(Messages.encode(MsgType.MANIFEST, Manifest(listOf(RegionInfo("Lombardia", 2, 1, good.size, 0)))))
        phoneT.send(Messages.encode(MsgType.BLACKLIST_SNAPSHOT, BlacklistSnapshot(emptyList())))
        phoneT.send(Messages.encode(MsgType.SETTINGS_SNAPSHOT, SettingsSnapshot("fixed", 300, true)))
        advanceUntilIdle()
        phoneT.send(Messages.encode(MsgType.DATASET_BEGIN, DatasetBegin("Lombardia", 2, 1, good.size, 12345)))
        phoneT.send(Messages.encode(MsgType.DATASET_CHUNK, DatasetChunk("Lombardia", 0, good)))
        phoneT.send(Messages.encode(MsgType.DATASET_END, DatasetEnd("Lombardia")))
        advanceUntilIdle()

        assertTrue(radioStore.applied.isEmpty())
        assertEquals(1L, radioStore.have["Lombardia"]) // previous version untouched
        assertFalse(synced.isCompleted)
        assertEquals(null, radioStore.syncedAt)
        phoneT.close(); radioT.close()
    }

    @Test
    fun oldProtocolIsRefused() = runTest {
        val (phoneT, radioT) = pipe()
        launch { PhoneLinkSession(phoneT, FakePhoneStore(), "p") { 1L }.run() }
        radioT.send(Messages.encode(MsgType.HELLO, Hello(1, "0.1", "r", "radio", emptyList())))
        advanceUntilIdle()
        val reply = radioT.receive()!!
        assertEquals(MsgType.ERROR, reply.type)
        assertEquals("PROTO_UNSUPPORTED", Messages.decode<ErrorMsg>(reply).code)
        phoneT.close(); radioT.close()
    }
}
