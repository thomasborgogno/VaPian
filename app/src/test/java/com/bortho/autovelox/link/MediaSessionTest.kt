package com.bortho.autovelox.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMediaSource : MediaSource {
    val flow = MutableSharedFlow<MediaState>(replay = 1, extraBufferCapacity = 16)
    override val state: Flow<MediaState> = flow
    val commands = mutableListOf<MediaCmd>()
    val coverRequests = mutableListOf<String>()
    override suspend fun cover(coverId: String): ByteArray? {
        coverRequests += coverId
        return if (coverId == "cover-1") byteArrayOf(1, 2, 3, 4) else null
    }
    override fun command(cmd: MediaCmd) { commands += cmd }
}

private class RecordingListener : MediaListener {
    val states = mutableListOf<MediaState>()
    val covers = mutableMapOf<String, ByteArray>()
    override fun onState(state: MediaState) { states += state }
    override fun onCover(coverId: String, jpeg: ByteArray) { covers[coverId] = jpeg }
}

private fun playing(title: String, position: Long, cover: String = "cover-1") =
    MediaState(MediaState.STATE_PLAYING, title, "Artista", "Album", 200_000, position, 1_000, "player", cover)

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionTest {
    @Test
    fun radioReceivesStateUpdatesAndFetchesTheCoverOnlyOnce() = runTest {
        val (phoneT, radioT) = pipe()
        val source = FakeMediaSource()
        val listener = RecordingListener()
        launch { PhoneLinkSession(phoneT, FakePhoneStore(), "phone", { 1L }, media = source).run() }
        launch { RadioLinkSession(radioT, FakeRadioStore(), "radio", "1.0", { 1L }, mediaListener = listener).run() }
        advanceUntilIdle()

        source.flow.emit(playing("Canzone", 1_000))
        source.flow.emit(playing("Canzone", 2_000)) // same cover: must not be requested again
        advanceUntilIdle()

        assertEquals(listOf(1_000L, 2_000L), listener.states.map { it.positionMs })
        assertEquals("Canzone", listener.states.first().title)
        assertEquals(listOf("cover-1"), source.coverRequests)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), listener.covers["cover-1"])

        source.flow.emit(playing("Altra", 0, cover = "cover-2")) // new cover id: asked again, phone has no bytes
        advanceUntilIdle()
        assertEquals(listOf("cover-1", "cover-2"), source.coverRequests)
        assertTrue("cover-2" !in listener.covers)
        phoneT.close(); radioT.close()
    }

    @Test
    fun commandsFromTheRadioReachThePhonePlayer() = runTest {
        val (phoneT, radioT) = pipe()
        val source = FakeMediaSource()
        launch { PhoneLinkSession(phoneT, FakePhoneStore(), "phone", { 1L }, media = source).run() }
        val radio = RadioLinkSession(radioT, FakeRadioStore(), "radio", "1.0", { 1L })
        launch { radio.run() }
        advanceUntilIdle()

        radio.sendMediaCommand(MediaCmd(MediaCmd.NEXT))
        radio.sendMediaCommand(MediaCmd(MediaCmd.SEEK, 45_000))
        advanceUntilIdle()

        assertEquals(listOf(MediaCmd(MediaCmd.NEXT), MediaCmd(MediaCmd.SEEK, 45_000)), source.commands)
        phoneT.close(); radioT.close()
    }

    @Test
    fun nothingIsPushedWithoutTheMediaCapability() = runTest {
        val (phoneT, radioT) = pipe()
        val source = FakeMediaSource()
        val phone = PhoneLinkSession(phoneT, FakePhoneStore(), "phone", { 1L }, media = source)
        launch { phone.run() }
        // a radio that does NOT announce "media" in its HELLO
        radioT.send(Messages.encode(MsgType.HELLO, Hello(PROTOCOL_VERSION, "1.0", "radio", "radio", listOf("dataset"))))
        advanceUntilIdle()
        source.flow.emit(playing("Canzone", 0))
        advanceUntilIdle()

        val frames = mutableListOf<Int>()
        radioT.receive()?.let { frames += it.type } // HELLO_ACK
        assertEquals(listOf(MsgType.HELLO_ACK), frames)
        assertTrue(phoneT.sentTypes.none { it == MsgType.MEDIA_STATE })
        phoneT.close(); radioT.close()
    }
}
