package de.clickbridge

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Pretends to be the "MyWhoosh Link" companion app: MyWhoosh connects to TCP
 * port 21587 on this phone, and we send it JSON lines with in-game controls.
 */
class WhooshLinkServer(private val onStatus: (String) -> Unit) {
    companion object {
        const val PORT = 21587
    }

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val sender = Executors.newSingleThreadExecutor()

    val connectedClients: Int get() = clients.size

    fun start() {
        val socket = ServerSocket(PORT).apply { reuseAddress = true }
        server = socket
        thread(name = "whoosh-link-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                clients += client
                onStatus("MyWhoosh verbunden (${client.inetAddress.hostAddress})")
                thread(name = "whoosh-link-read", isDaemon = true) { drain(client) }
            }
        }
    }

    /** MyWhoosh also sends us messages; we don't need them, but must read them. */
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
        val value = if (direction == ZwiftProtocol.Shift.UP) "1" else "-1"
        send("""{"MessageType":"Controls","InGameControls":{"GearShifting":"$value"}}""")
    }

    private fun send(line: String) {
        val bytes = (line + "\n").toByteArray()
        sender.execute {
            for (client in clients) {
                try {
                    val out: OutputStream = client.getOutputStream()
                    out.write(bytes)
                    out.flush()
                } catch (_: IOException) {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }
}
