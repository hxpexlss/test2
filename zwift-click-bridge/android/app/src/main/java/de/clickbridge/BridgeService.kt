package de.clickbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared state between the service and the screen. Only touched on the main thread. */
object BridgeState {
    var running = false
    var clickStatus = "–"
    var whooshStatus = "–"
    val log = ArrayDeque<String>()
    var listener: (() -> Unit)? = null
    var service: BridgeService? = null

    fun addLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        log.addFirst("$time  $line")
        while (log.size > 50) log.removeLast()
    }
}

/** Foreground service so the bridge keeps running with the screen off. */
class BridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var click: ClickConnection? = null
    private var link: WhooshLinkServer? = null
    private var obc: ObcServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BridgeState.running) return START_STICKY
        startInForeground()
        BridgeState.running = true
        BridgeState.service = this

        // OpenBikeControl is what MyWhoosh finds by itself (connection screen, icon top right).
        obc = ObcServer(this) { status -> handler.post { setWhooshStatus(status) } }.also {
            try {
                it.start()
            } catch (e: IOException) {
                setWhooshStatus("OpenBikeControl konnte nicht starten: ${e.message}")
            }
        }
        // MyWhoosh Link as a fallback; only used while no OpenBikeControl connection exists.
        link = WhooshLinkServer { status -> handler.post { setWhooshStatus("Link: $status") } }.also {
            try {
                it.start()
            } catch (e: IOException) {
                BridgeState.addLog("Link-Port ${WhooshLinkServer.PORT} belegt (MyWhoosh-Link-App offen?)")
            }
        }

        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) {
            setClickStatus("Dieses Gerät hat kein Bluetooth")
        } else {
            click = ClickConnection(this, adapter, ::setClickStatus, ::shift).also { it.start() }
        }
        return START_STICKY
    }

    fun shift(direction: ZwiftProtocol.Shift) {
        val label = if (direction == ZwiftProtocol.Shift.UP) "hoch" else "runter"
        val obc = obc
        val link = link
        // Only one path at a time, otherwise MyWhoosh would shift twice.
        when {
            obc != null && obc.connectedClients > 0 -> {
                obc.shift(direction)
                BridgeState.addLog("Schalten $label")
            }
            link != null && link.connectedClients > 0 -> {
                link.shift(direction)
                BridgeState.addLog("Schalten $label (Link)")
            }
            else -> BridgeState.addLog("Schalten $label – aber MyWhoosh ist nicht verbunden")
        }
        BridgeState.listener?.invoke()
    }

    private fun setClickStatus(status: String) {
        BridgeState.clickStatus = status
        BridgeState.addLog(status)
        updateNotification()
        BridgeState.listener?.invoke()
    }

    private fun setWhooshStatus(status: String) {
        BridgeState.whooshStatus = status
        BridgeState.addLog(status)
        updateNotification()
        BridgeState.listener?.invoke()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Click Bridge", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Click Bridge läuft")
            .setContentText("${BridgeState.clickStatus} · ${BridgeState.whooshStatus}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!BridgeState.running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        click?.stop()
        link?.stop()
        obc?.stop()
        BridgeState.running = false
        BridgeState.service = null
        BridgeState.clickStatus = "–"
        BridgeState.whooshStatus = "–"
        BridgeState.addLog("Gestoppt")
        BridgeState.listener?.invoke()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
