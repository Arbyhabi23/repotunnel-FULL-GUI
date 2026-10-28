package com.example.simpletunnel

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

class TunnelService : VpnService() {

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var bridge: HttpBridge? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopTunnel() else startTunnel()
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (running) return
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val host = p.getString("host", "") ?: ""
        val port = (p.getString("port", "") ?: "").toIntOrNull() ?: 8080
        val user = p.getString("user", "") ?: ""
        val pass = p.getString("pass", "") ?: ""
        if (host.isBlank()) { stopSelf(); return }

        // Jembatan lokal SOCKS5 -> HTTP proxy VPS
        val b = HttpBridge(host, port, user, pass)
        val localPort = b.start()
        bridge = b

        tun = Builder()
            .setSession("Simple Tunnel")
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("198.18.0.2") // dijawab engine (mapdns), resolusi nama terjadi di sisi proxy
            .addDisallowedApplication(packageName) // koneksi ke VPS tidak boleh masuk loop
            .establish()

        if (tun == null) { stopTunnel(); return }

        val cfg = """
            tunnel:
              mtu: 8500
              ipv4: 198.18.0.1
            socks5:
              port: $localPort
              address: 127.0.0.1
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: error
        """.trimIndent() + "\n"

        val f = File(filesDir, "tproxy.yml")
        f.writeText(cfg)

        TProxyService.TProxyStartService(f.absolutePath, tun!!.fd)
        running = true
    }

    private fun stopTunnel() {
        if (running) {
            TProxyService.TProxyStopService()
            running = false
        }
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        bridge?.stop()
        bridge = null
        stopSelf()
    }

    override fun onRevoke() = stopTunnel()

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
