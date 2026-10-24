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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BridgeState.running) return START_STICKY
        startInForeground()
        BridgeState.running = true
        BridgeState.service = this

        link = WhooshLinkServer { status -> handler.post { setWhooshStatus(status) } }.also {
            try {
                it.start()
                setWhooshStatus("Warte auf MyWhoosh (Port ${WhooshLinkServer.PORT}) …")
            } catch (e: IOException) {
                setWhooshStatus("Port ${WhooshLinkServer.PORT} belegt – läuft die MyWhoosh-Link-App noch?")
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
        val link = link ?: return
        val label = if (direction == ZwiftProtocol.Shift.UP) "hoch" else "runter"
        if (link.connectedClients == 0) {
            BridgeState.addLog("Schalten $label – aber MyWhoosh ist nicht verbunden")
        } else {
            BridgeState.addLog("Schalten $label")
        }
        link.shift(direction)
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
