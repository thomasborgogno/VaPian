package com.bortho.autovelox.carapp.data

import com.google.firebase.firestore.DocumentSnapshot

/** Mirrors `lib/data/models/user_settings.dart` — same `users/{uid}.settings` map. */
data class UserSettings(
    val alertDistanceMode: String = "fixed",
    val alertDistanceMeters: Int = 300,
    val toleranceFilterEnabled: Boolean = true,
) {
    companion object {
        fun fromDocument(doc: DocumentSnapshot): UserSettings {
            @Suppress("UNCHECKED_CAST")
            val map = doc.get("settings") as? Map<String, Any?> ?: return UserSettings()
            return UserSettings(
                alertDistanceMode = map["alertDistanceMode"] as? String ?: "fixed",
                alertDistanceMeters = (map["alertDistanceMeters"] as? Long)?.toInt() ?: 300,
                toleranceFilterEnabled = map["toleranceFilterEnabled"] as? Boolean ?: true,
            )
        }
    }
}
