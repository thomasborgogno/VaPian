package com.bortho.autovelox.link

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only lets an app read other apps' media sessions
 * (`MediaSessionManager.getActiveSessions`) if it owns an enabled notification listener, so the user
 * grants "notification access" to this service once, from the phone's settings.
 */
class MediaListenerService : NotificationListenerService()
