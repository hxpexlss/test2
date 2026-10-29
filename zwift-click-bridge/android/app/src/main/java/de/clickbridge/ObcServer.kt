package de.clickbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An OpenBikeControl network controller: MyWhoosh finds it via mDNS
 * (`_openbikecontrol._tcp`), connects over TCP and receives button messages
 * `[0x01, buttonId, state]` (0x01 = Shift Up, 0x02 = Shift Down; state 1 = pressed, 0 = released).
 */
class ObcServer(private val context: Context, private val onStatus: (String) -> Unit) {
    companion object {
        const val SERVICE_TYPE = "_openbikecontrol._tcp"
        const val SERVICE_NAME = "Click Bridge"
        // Same UUID as the OpenBikeControl BLE service; apps use it to recognise the device.
        const val SERVICE_UUID = "d273f680-d548-419d-b9d1-fa0472345229"
        private const val MSG_BUTTON_STATE: Byte = 0x01
        private const val BUTTON_SHIFT_UP: Byte = 0x01
        private const val BUTTON_SHIFT_DOWN: Byte = 0x02
    }

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registered = false

    val connectedClients: Int get() = clients.size

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            onStatus("Sichtbar als „${info.serviceName}“ – in MyWhoosh suchen")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            onStatus("Netzwerk-Anmeldung fehlgeschlagen ($errorCode)")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    fun start() {
        val socket = ServerSocket(0).apply { reuseAddress = true }
        server = socket
        thread(name = "obc-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                client.tcpNoDelay = true
                clients += client
                onStatus("MyWhoosh verbunden (${client.inetAddress.hostAddress})")
                thread(name = "obc-read", isDaemon = true) { drain(client) }
            }
        }

        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = socket.localPort
            setAttribute("version", "1")
            setAttribute("id", "clickbridge")
            setAttribute("name", SERVICE_NAME)
            setAttribute("service-uuids", SERVICE_UUID)
            setAttribute("manufacturer", "Click Bridge")
            setAttribute("model", "Zwift Click")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /** MyWhoosh sends app info and haptic messages; we don't need them, but must read them. */
    private fun drain(client: Socket) {
        try {
            val input = client.getInputStream()
            val buffer = ByteArray(1024)
            while (input.read(buffer) >= 0) Unit
        } catch (_: IOException) {
        }
        if (clients.remove(client)) onStatus("MyWhoosh getrennt")
        runCatching { client.close() }
    }

    fun shift(direction: ZwiftProtocol.Shift) {
        val button = if (direction == ZwiftProtocol.Shift.UP) BUTTON_SHIFT_UP else BUTTON_SHIFT_DOWN
        sender.execute { send(byteArrayOf(MSG_BUTTON_STATE, button, 0x01)) }
        sender.schedule({ send(byteArrayOf(MSG_BUTTON_STATE, button, 0x00)) }, 100, TimeUnit.MILLISECONDS)
    }

    private fun send(message: ByteArray) {
        for (client in clients) {
            try {
                client.getOutputStream().apply {
                    write(message)
                    flush()
                }
            } catch (_: IOException) {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    fun stop() {
        if (registered) runCatching { nsd.unregisterService(registrationListener) }
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }
}
