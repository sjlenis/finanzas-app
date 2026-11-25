package com.finanzaspersonales.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.finanzaspersonales.core.data.NotificationImporter
import com.finanzaspersonales.engine.notifications.BankNotificationParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Recibe las notificaciones del teléfono solo si diste el permiso en Android y
 * activaste la detección en la app. Descarta de inmediato todo lo que no venga de
 * Nequi, Bancolombia o una app de SMS; el texto no se guarda.
 */
@AndroidEntryPoint
class BankNotificationListener : NotificationListenerService() {

    @Inject lateinit var importer: NotificationImporter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName !in BankNotificationParser.ALL_PACKAGES) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.takeIf { it.isNotBlank() } ?: return
        val packageName = sbn.packageName
        val postedAt = Instant.ofEpochMilli(sbn.postTime)
        scope.launch {
            // Un error al leer una notificación nunca debe cerrar la app ni el servicio.
            runCatching { importer.process(packageName, title, text, postedAt) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
