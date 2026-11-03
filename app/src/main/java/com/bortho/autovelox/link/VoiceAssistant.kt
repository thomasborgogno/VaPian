package com.bortho.autovelox.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when the dashboard asked the phone for its assistant. */
enum class VoiceAssistantResult {
    /** The request was sent; the phone opens its assistant and the conversation runs over the hands-free audio. */
    STARTED,

    /** The radio is not connected to the phone over the hands-free profile. */
    NOT_CONNECTED,

    /** This device offers no hands-free client (or its Bluetooth stack refuses the request). */
    UNSUPPORTED,
}

/**
 * Radio side: asks the phone to start its voice assistant (Gemini) the way a car kit's voice button does, through the
 * Bluetooth hands-free profile (HFP): the radio, as the hands-free unit, sends the standard "voice recognition on" request.
 * The phone's own system then opens the assistant, also with the screen off and the phone locked, and routes the audio to
 * the radio's microphone and speakers. Nothing runs on the phone side from this app.
 *
 * The radio's HFP client ([PROFILE_HEADSET_CLIENT]) is a hidden Android API: no public call exists to ask for it. Reflection
 * reaches it on the Android 8.1 of the head unit; later Android versions block it, where the result is [UNSUPPORTED].
 */
@Singleton
class VoiceAssistant @Inject constructor(@ApplicationContext private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun start(preferredAddress: String?): VoiceAssistantResult {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return VoiceAssistantResult.UNSUPPORTED
        val proxy = connectProxy(adapter) ?: return VoiceAssistantResult.UNSUPPORTED
        try {
            val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
            val phone = devices.firstOrNull { it.address == preferredAddress } ?: devices.firstOrNull() ?: return VoiceAssistantResult.NOT_CONNECTED
            val ok = runCatching { proxy.javaClass.getMethod("startVoiceRecognition", BluetoothDevice::class.java).invoke(proxy, phone) as? Boolean }
                .onFailure { Log.w(TAG, "startVoiceRecognition not available: ${it.message}") }
                .getOrNull()
            Log.i(TAG, "voice recognition requested from ${phone.address}: $ok")
            return if (ok == true) VoiceAssistantResult.STARTED else VoiceAssistantResult.UNSUPPORTED
        } finally {
            runCatching { adapter.closeProfileProxy(PROFILE_HEADSET_CLIENT, proxy) }
        }
    }

    private suspend fun connectProxy(adapter: BluetoothAdapter): BluetoothProfile? {
        val ready = CompletableDeferred<BluetoothProfile?>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                ready.complete(proxy)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        val asked = runCatching { adapter.getProfileProxy(context, listener, PROFILE_HEADSET_CLIENT) }.getOrDefault(false)
        if (!asked) return null
        return withTimeoutOrNull(PROXY_TIMEOUT_MS) { ready.await() }
    }

    private companion object {
        const val TAG = "VoiceAssistant"

        /** `BluetoothProfile.HEADSET_CLIENT`: hidden, so spelled out. */
        const val PROFILE_HEADSET_CLIENT = 16
        const val PROXY_TIMEOUT_MS = 3_000L
    }
}
