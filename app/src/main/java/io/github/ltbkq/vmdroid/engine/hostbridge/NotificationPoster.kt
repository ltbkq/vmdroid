/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Seam between the host-bridge dispatcher and Android's NotificationManager,
 * so the dispatcher can be unit-tested with a fake.
 */
package io.github.ltbkq.vmdroid.engine.hostbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ltbkq.vmdroid.MainActivity
import io.github.ltbkq.vmdroid.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

interface NotificationPoster {
    /** True when the app may post notifications (Android 13+ runtime grant). */
    fun notificationsPermitted(): Boolean

    /**
     * Post (or, when [id] is non-null and matches an earlier call, replace) a
     * notification. Returns the notification id actually used.
     * @param priority one of HostProtocol.PRIO_* ; callers pass a validated value.
     */
    fun post(title: String?, body: String, priority: String, id: Int?): Int
}

@Singleton
class AndroidNotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPoster {

    companion object {
        private const val CHANNEL_ID = "podroid_guest"
        // Guest notification ids live well above VmdroidService.NOTIFICATION_ID (1001)
        // so a guest --id can never replace the foreground-service notification.
        private const val ID_BASE = 0x7000_0000
        private const val ID_SPAN = 0x0000_FFFF
    }

    private val autoId = AtomicInteger(0)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID, "VM notifications", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Notifications sent from inside the VM (podroid-notify)" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun notificationsPermitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun post(title: String?, body: String, priority: String, id: Int?): Int {
        val notifId = if (id != null) ID_BASE + (id and ID_SPAN)
                      else ID_BASE + ID_SPAN + 1 + (autoId.incrementAndGet() and ID_SPAN)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val prio = when (priority) {
            HostProtocol.PRIO_HIGH -> NotificationCompat.PRIORITY_HIGH
            HostProtocol.PRIO_LOW -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title ?: "VMDroid")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(prio)
            .build()
        NotificationManagerCompat.from(context).notify(notifId, n)
        return notifId
    }
}
