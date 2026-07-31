package com.bortho.autovelox.carapp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Replays `sync_queue` against Firestore for writes made natively (the
 * "Segnala"/"Ignora Velox" quick actions in [CameraProximityService]) —
 * independent of the Dart-side `SyncService`, since a pure Android-Auto
 * session may never open the Flutter UI (see PLAN.md's native-first
 * decision). Registered once, in [AutoveloxApplication], via a
 * [ConnectivityManager] callback instead of `WorkManager`/a headless
 * `FlutterEngine` — this app deliberately has neither (see PLAN.md §1).
 *
 * Draining the same queue from both this and the Dart `SyncService` is
 * safe: whichever runs first removes the row, and Firestore
 * writes/deletes here are idempotent (`set(..., merge: true)`/`delete()`
 * on an already-applied change is a no-op).
 */
class NativeSyncDrainer(context: Context) {
    private val store = LocalCameraStore.getInstance(context)
    private val firestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var draining = false

    fun register(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = drain()
            },
        )
        drain()
    }

    fun drain() {
        if (draining) return
        draining = true
        scope.launch {
            try {
                for (op in store.pendingSyncOps()) {
                    try {
                        replay(op)
                        store.removeSyncOp(op.id)
                    } catch (e: Exception) {
                        store.markSyncOpAttempted(op.id)
                    }
                }
            } finally {
                draining = false
            }
        }
    }

    private suspend fun replay(op: SyncQueueOpRow) {
        when (op.opType) {
            "upsertCamera" -> {
                val data = store.speedCameraFirestoreData(op.targetId) ?: return
                firestore.collection("speed_cameras").document(op.targetId).set(data, SetOptions.merge()).await()
            }
            "deleteCamera" -> {
                firestore.collection("speed_cameras").document(op.targetId).delete().await()
            }
            "upsertIgnoredCamera" -> {
                val (uid, cameraId) = payloadUidAndCameraId(op.payload)
                val ignoredAt = store.ignoredAtMillis(uid, cameraId) ?: return
                firestore.collection("users").document(uid).collection("ignored_cameras").document(cameraId)
                    .set(mapOf("ignoredAt" to Timestamp(ignoredAt / 1000, 0))).await()
            }
            "deleteIgnoredCamera" -> {
                val (uid, cameraId) = payloadUidAndCameraId(op.payload)
                firestore.collection("users").document(uid).collection("ignored_cameras").document(cameraId)
                    .delete().await()
            }
            "queueIgnore" -> {
                val (uid, cameraId) = payloadUidAndCameraId(op.payload)
                val queuedAt = store.queuedAtMillis(uid, cameraId) ?: return
                firestore.collection("users").document(uid).collection("queued_ignores").document(cameraId)
                    .set(mapOf("queuedAt" to Timestamp(queuedAt / 1000, 0))).await()
            }
            "dismissQueuedIgnore" -> {
                val (uid, cameraId) = payloadUidAndCameraId(op.payload)
                firestore.collection("users").document(uid).collection("queued_ignores").document(cameraId)
                    .delete().await()
            }
        }
    }

    private fun payloadUidAndCameraId(payload: String): Pair<String, String> {
        val json = JSONObject(payload)
        return json.getString("uid") to json.getString("cameraId")
    }
}
