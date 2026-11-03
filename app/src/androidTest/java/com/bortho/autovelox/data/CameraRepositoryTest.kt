package com.bortho.autovelox.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.data.local.SyncOpType
import com.bortho.autovelox.data.local.VaPianDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The repository against a real (in-memory) Room database: what the unit tests on the JVM cannot reach. */
@RunWith(AndroidJUnit4::class)
class CameraRepositoryTest {
    private lateinit var db: VaPianDatabase
    private lateinit var repository: CameraRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaPianDatabase::class.java).allowMainThreadQueries().build()
        repository = CameraRepository(db) { now++ }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queue() = db.syncQueue().pending()

    @Test
    fun newReportIsStoredPendingAndQueuedOnce() = runBlocking {
        val camera = repository.addPendingUserCamera(46.05, 11.12, 50, 90, createdBy = "me")
        val stored = repository.camera(camera.id)
        assertNotNull(stored)
        assertEquals(CameraStatus.PENDING, stored!!.status)
        assertEquals(listOf(SyncOpType.UPSERT_CAMERA.name), queue().map { it.opType })
    }

    @Test
    fun repeatedEditsCoalesceIntoASingleQueuedOp() = runBlocking {
        val camera = repository.addPendingUserCamera(46.05, 11.12, 50, 90, createdBy = "me")
        repository.edit(camera.id, 70, 90, reversed = false)
        repository.edit(camera.id, 90, 90, reversed = true)
        assertEquals(1, queue().size)
        val stored = repository.camera(camera.id)!!
        assertEquals(90, stored.limit)
        assertTrue(stored.directionReversed)
    }

    @Test
    fun confirmingApprovesAndCorrectsTheReport() = runBlocking {
        val camera = repository.addPendingUserCamera(46.05, 11.12, null, null, createdBy = "me")
        repository.confirm(camera.id, 50, 180)
        val stored = repository.camera(camera.id)!!
        assertEquals(CameraStatus.APPROVED, stored.status)
        assertEquals(50, stored.limit)
        assertEquals(180, stored.direction)
    }

    @Test
    fun deletingHidesTheCameraAndReplacesTheUpsertWithADelete() = runBlocking {
        val camera = repository.addPendingUserCamera(46.05, 11.12, 50, null, createdBy = "me")
        repository.deleteCamera(camera.id)
        assertTrue(repository.near(46.05, 11.12, 500.0).none { it.id == camera.id })
        assertEquals(listOf(SyncOpType.DELETE_CAMERA.name), queue().map { it.opType })
    }

    @Test
    fun ignoringAndRestoringARegionCamera() = runBlocking {
        repository.ignore("me", "osm_1")
        assertTrue("osm_1" in repository.ignoredIds("me"))
        repository.unignore("me", "osm_1")
        assertTrue(repository.ignoredIds("me").isEmpty())
    }

    @Test
    fun publishedCameraRemembersItsOsmNode() = runBlocking {
        val camera = repository.addPendingUserCamera(46.05, 11.12, 50, 90, createdBy = "me")
        repository.markPublishedToOsm(camera.id, 123456L)
        val stored = repository.camera(camera.id)!!
        assertEquals(123456L, stored.osmNodeId)
        assertEquals(123456L, stored.osmId)
    }
}
