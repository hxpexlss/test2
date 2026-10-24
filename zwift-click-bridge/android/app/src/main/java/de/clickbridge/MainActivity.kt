package de.clickbridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var startButton: Button
    private lateinit var clickStatus: TextView
    private lateinit var whooshStatus: TextView
    private lateinit var ipAddress: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startButton = findViewById(R.id.start)
        clickStatus = findViewById(R.id.click_status)
        whooshStatus = findViewById(R.id.whoosh_status)
        ipAddress = findViewById(R.id.ip_address)
        log = findViewById(R.id.log)

        startButton.setOnClickListener {
            if (BridgeState.running) BridgeService.stop(this) else startWithPermissions()
        }
        findViewById<Button>(R.id.test_up).setOnClickListener { testShift(ZwiftProtocol.Shift.UP) }
        findViewById<Button>(R.id.test_down).setOnClickListener { testShift(ZwiftProtocol.Shift.DOWN) }
    }

    override fun onResume() {
        super.onResume()
        BridgeState.listener = ::render
        render()
    }

    override fun onPause() {
        BridgeState.listener = null
        super.onPause()
    }

    private fun render() {
        startButton.text = if (BridgeState.running) "Stoppen" else "Starten"
        clickStatus.text = "Click: ${BridgeState.clickStatus}"
        whooshStatus.text = "MyWhoosh: ${BridgeState.whooshStatus}"
        ipAddress.text = "IP dieses Handys: ${localIpAddress() ?: "kein WLAN"}"
        log.text = BridgeState.log.joinToString("\n")
    }

    private fun testShift(direction: ZwiftProtocol.Shift) {
        val service = BridgeState.service
        if (service == null) {
            Toast.makeText(this, "Erst starten", Toast.LENGTH_SHORT).show()
        } else {
            service.shift(direction)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startWithPermissions() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            BridgeService.start(this)
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val bluetoothMissing = permissions.indices.any {
            grantResults[it] != PackageManager.PERMISSION_GRANTED &&
                permissions[it] != Manifest.permission.POST_NOTIFICATIONS
        }
        if (bluetoothMissing) {
            Toast.makeText(this, "Ohne Bluetooth-Berechtigung geht es leider nicht", Toast.LENGTH_LONG).show()
        } else {
            BridgeService.start(this)
        }
    }

    private fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }
}
