package com.example.simpletunnel

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * SOCKS5 lokal (hanya CONNECT) -> HTTP proxy (metode CONNECT) di VPS.
 * Engine tun2socks bicara SOCKS5 ke sini, lalu diteruskan ke HTTP proxy.
 */
class HttpBridge(
    private val host: String,
    private val port: Int,
    user: String,
    pass: String
) {
    private val auth: String? =
        if (user.isNotEmpty()) "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        else null

    private var server: ServerSocket? = null

    fun start(): Int {
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return s.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun readFully(i: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(b, off, n - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
        return b
    }

    private fun pipe(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val r = i.read(buf)
                if (r < 0) break
                o.write(buf, 0, r)
                o.flush()
            }
        } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            c.tcpNoDelay = true
            val ci = c.getInputStream()
            val co = c.getOutputStream()

            // SOCKS5 greeting: tanpa autentikasi
            if (ci.read() != 5) return
            val nm = ci.read()
            if (nm < 0) return
            readFully(ci, nm)
            co.write(byteArrayOf(5, 0)); co.flush()

            // SOCKS5 request
            val h = readFully(ci, 4)
            val cmd = h[1].toInt()
            val dest = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(readFully(ci, 4)).hostAddress
                3 -> { val l = ci.read(); if (l < 0) return; String(readFully(ci, l)) }
                4 -> "[" + InetAddress.getByAddress(readFully(ci, 16)).hostAddress + "]"
                else -> return
            }
            val pb = readFully(ci, 2)
            val dport = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)

            if (cmd != 1) { // hanya CONNECT (HTTP proxy tidak mendukung UDP)
                co.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); co.flush()
                return
            }

            val u = Socket()
            up = u
            u.connect(InetSocketAddress(host, port), 10_000)
            u.tcpNoDelay = true
            val uo = u.getOutputStream()
            val ui = u.getInputStream()

            val req = StringBuilder("CONNECT $dest:$dport HTTP/1.1\r\nHost: $dest:$dport\r\n")
            if (auth != null) req.append("Proxy-Authorization: ").append(auth).append("\r\n")
            req.append("\r\n")
            uo.write(req.toString().toByteArray()); uo.flush()

            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = ui.read()
                if (b < 0 || sb.length > 8192) {
                    co.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)); co.flush()
                    return
                }
                sb.append(b.toChar())
            }
            val code = sb.substring(0, sb.indexOf("\r\n")).split(" ").getOrNull(1)
            if (code != "200") {
                co.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); co.flush()
                return
            }
            co.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); co.flush()

            val t = thread(isDaemon = true) {
                pipe(ui, co)
                try { c.shutdownOutput() } catch (_: Exception) {}
            }
            pipe(ci, uo)
            try { u.shutdownOutput() } catch (_: Exception) {}
            t.join()
        } catch (_: Exception) {
        } finally {
            try { up?.close() } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }
}
