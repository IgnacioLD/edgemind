package com.localai.assistant.data.system

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Empty NotificationListenerService whose only job is to be a registered, user-grantable
// listener component. MediaSessionManager.getActiveSessions(component) requires the caller
// to be either an enabled Notification Listener or a granted assistant — registering this
// service is the standard way to qualify without requiring the system Assistant role.
class AssistantNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val listeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val component = ComponentName(context, AssistantNotificationListener::class.java)
            return listeners.split(":").any { entry ->
                ComponentName.unflattenFromString(entry) == component
            }
        }
    }
}
