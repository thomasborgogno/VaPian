package com.bortho.autovelox.link

import com.bortho.autovelox.core.model.AlertDistanceMode
import com.bortho.autovelox.core.model.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class AppsSource : MediaSource {
    override val state: Flow<MediaState> = emptyFlow()
    val appsFlow = MutableSharedFlow<MediaApps>(replay = 1, extraBufferCapacity = 8)
    override val apps: Flow<MediaApps> = appsFlow
    val commands = mutableListOf<MediaCmd>()
    override suspend fun cover(coverId: String): ByteArray? = null
    override fun command(cmd: MediaCmd) { commands += cmd }
}

private class AppsListener : MediaListener {
    val apps = mutableListOf<MediaApps>()
    override fun onState(state: MediaState) = Unit
    override fun onCover(coverId: String, jpeg: ByteArray) = Unit
    override fun onApps(apps: MediaApps) { this.apps += apps }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaAppsAndSettingsTest {
    @Test
    fun launchableAppsAndActiveSessionsReachTheRadio() = runTest {
        val (phoneT, radioT) = pipe()
        val source = AppsSource()
        val listener = AppsListener()
        launch { PhoneLinkSession(phoneT, FakePhoneStore(), "phone", { 1L }, media = source).run() }
        launch { RadioLinkSession(radioT, FakeRadioStore(), "radio", "1.0", { 1L }, mediaListener = listener).run() }
        advanceUntilIdle()

        val first = MediaApps(listOf(AppRef("com.spotify.music", "Spotify")), emptyList())
        val second = MediaApps(
            listOf(AppRef("com.spotify.music", "Spotify")),
            listOf(SessionRef("com.spotify.music", "Spotify", playing = true, selected = true), SessionRef("yt", "YouTube Music", playing = false, selected = false)),
        )
        source.appsFlow.emit(first)
        source.appsFlow.emit(second)
        advanceUntilIdle()

        assertEquals(listOf(first, second), listener.apps)
        phoneT.close(); radioT.close()
    }

    @Test
    fun launchAndSelectCarryThePackageToThePhone() = runTest {
        val (phoneT, radioT) = pipe()
        val source = AppsSource()
        launch { PhoneLinkSession(phoneT, FakePhoneStore(), "phone", { 1L }, media = source).run() }
        val radio = RadioLinkSession(radioT, FakeRadioStore(), "radio", "1.0", { 1L })
        launch { radio.run() }
        advanceUntilIdle()

        radio.sendMediaCommand(MediaCmd(MediaCmd.LAUNCH, pkg = "com.spotify.music"))
        radio.sendMediaCommand(MediaCmd(MediaCmd.SELECT, pkg = "yt"))
        radio.sendMediaCommand(MediaCmd(MediaCmd.NEXT)) // the old commands still decode without a package
        advanceUntilIdle()

        assertEquals(
            listOf(MediaCmd(MediaCmd.LAUNCH, pkg = "com.spotify.music"), MediaCmd(MediaCmd.SELECT, pkg = "yt"), MediaCmd(MediaCmd.NEXT)),
            source.commands,
        )
        phoneT.close(); radioT.close()
    }

    @Test
    fun settingsEditedOnTheRadioReachThePhoneAndAreNotOverwrittenByItsSnapshot() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val radioStore = FakeRadioStore().also { it.pending = UserSettings(AlertDistanceMode.FIXED, 650, false) }
        launch { PhoneLinkSession(phoneT, phoneStore, "phone", { 1L }).run() }
        launch { RadioLinkSession(radioT, radioStore, "radio", "1.0", { 1L }).run() }
        advanceUntilIdle()

        assertEquals(UserSettings(AlertDistanceMode.FIXED, 650, false), phoneStore.saved)
        assertNull(radioStore.pending) // delivered, so it is not sent again
        phoneT.close(); radioT.close()
    }

    @Test
    fun outOfRangeDistanceFromTheRadioIsClamped() = runTest {
        val (phoneT, radioT) = pipe()
        val phoneStore = FakePhoneStore()
        val radioStore = FakeRadioStore().also { it.pending = UserSettings(AlertDistanceMode.FIXED, 99_999, true) }
        launch { PhoneLinkSession(phoneT, phoneStore, "phone", { 1L }).run() }
        launch { RadioLinkSession(radioT, radioStore, "radio", "1.0", { 1L }).run() }
        advanceUntilIdle()

        assertEquals(UserSettings.MAX_ALERT_METERS, phoneStore.saved?.alertDistanceMeters)
        phoneT.close(); radioT.close()
    }
}
