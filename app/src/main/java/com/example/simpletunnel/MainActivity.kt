package com.example.simpletunnel

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import hev.htproxy.TProxyService
import java.io.File

class MainActivity : Activity() {

    companion object {
        var startedAt = 0L
        const val OFF = 0
        const val CONNECTING = 1
        const val ON = 2
        private const val C_OFF = 0xFF2B3A5C.toInt()
        private const val C_CONNECTING = 0xFFFFB020.toInt()
        private const val C_ON = 0xFF00C896.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var powerBtn: ImageView
    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var spinner: View
    private lateinit var tvStatus: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvError: TextView
    private lateinit var settingsBody: View
    private lateinit var tvSettingsHeader: TextView
    private var expanded = false
    private lateinit var crashFile: File

    private lateinit var btnBg: GradientDrawable
    private var curColor = C_OFF
    private var state = -1

    private var pulse1: ObjectAnimator? = null
    private var pulse2: ObjectAnimator? = null
    private var spin: ObjectAnimator? = null

    private var lastTx = 0L
    private var lastRx = 0L
    private var lastT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = 0xFF0B1220.toInt()
        window.navigationBarColor = 0xFF1B1038.toInt()

        crashFile = File(filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        val cf = crashFile
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { cf.writeText(Log.getStackTraceString(e)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        powerBtn = findViewById(R.id.powerBtn)
        ring1 = findViewById(R.id.ring1)
        ring2 = findViewById(R.id.ring2)
        spinner = findViewById(R.id.spinner)
        tvStatus = findViewById(R.id.tvStatus)
        tvSub = findViewById(R.id.tvSub)
        tvTimer = findViewById(R.id.tvTimer)
        tvDown = findViewById(R.id.tvDown)
        tvUp = findViewById(R.id.tvUp)
        tvError = findViewById(R.id.tvError)
        settingsBody = findViewById(R.id.settingsBody)
        tvSettingsHeader = findViewById(R.id.tvSettingsHeader)

        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        etHost.setText(p.getString("host", ""))
        etPort.setText(p.getString("port", ""))
        etUser.setText(p.getString("user", ""))
        etPass.setText(p.getString("pass", ""))

        // pengaturan tersembunyi kalau sudah pernah diisi; tap judul untuk buka/tutup
        setExpanded((p.getString("host", "") ?: "").isBlank())
        tvSettingsHeader.setOnClickListener { setExpanded(!expanded) }

        btnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C_OFF)
        }
        powerBtn.background = btnBg

        // efek tekan pada tombol
        powerBtn.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(OvershootInterpolator()).start()
            }
            false
        }
        powerBtn.setOnClickListener { toggle() }

        // animasi masuk
        val content = findViewById<View>(R.id.content)
        content.alpha = 0f
        content.translationY = 48f
        content.animate().alpha(1f).translationY(0f).setDuration(600)
            .setInterpolator(DecelerateInterpolator()).start()
        powerBtn.scaleX = 0.7f
        powerBtn.scaleY = 0.7f
        powerBtn.animate().scaleX(1f).scaleY(1f).setDuration(700).setStartDelay(150)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    override fun onResume() {
        super.onResume()
        if (TunnelService.running) {
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            setState(ON)
        } else if (state != CONNECTING) {
            setState(OFF)
        }
        tvError.text = if (crashFile.exists()) "ERROR TERAKHIR:\n" + crashFile.readText() else ""
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pulse1?.cancel(); pulse2?.cancel(); spin?.cancel()
        super.onDestroy()
    }

    // ---------- logika tombol ----------

    private fun toggle() {
        if (state == CONNECTING) return
        if (state == ON) {
            startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
            startedAt = 0L
            setState(OFF)
            return
        }
        if (etHost.text.toString().isBlank()) {
            setExpanded(true)
            ObjectAnimator.ofFloat(etHost, "translationX", 0f, -24f, 24f, -16f, 16f, -8f, 8f, 0f)
                .setDuration(450).start()
            etHost.requestFocus()
            return
        }
        crashFile.delete()
        tvError.text = ""
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("host", etHost.text.toString().trim())
            .putString("port", etPort.text.toString().trim().ifEmpty { "8080" })
            .putString("user", etUser.text.toString())
            .putString("pass", etPass.text.toString())
            .apply()

        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, 1) else begin()
    }

    private fun begin() {
        setState(CONNECTING)
        startService(Intent(this, TunnelService::class.java))
        waitConnected(20)
    }

    private fun waitConnected(tries: Int) {
        handler.postDelayed({
            if (state != CONNECTING) return@postDelayed
            if (TunnelService.running) {
                startedAt = SystemClock.elapsedRealtime()
                setState(ON)
            } else if (tries <= 0) {
                setState(OFF)
                tvError.text = if (crashFile.exists()) "ERROR TERAKHIR:\n" + crashFile.readText()
                else "Gagal menyambung."
            } else {
                waitConnected(tries - 1)
            }
        }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) begin()
    }

    // ---------- tampilan & animasi ----------

    private fun setState(s: Int) {
        if (state == s) return
        state = s
        handler.removeCallbacks(ticker)

        if (s != OFF) setExpanded(false)
        val editable = s == OFF
        listOf(etHost, etPort, etUser, etPass).forEach {
            it.isEnabled = editable
            it.animate().alpha(if (editable) 1f else 0.5f).setDuration(300).start()
        }

        when (s) {
            OFF -> {
                stopPulse(); stopSpin()
                animateColor(C_OFF)
                tvStatus.text = "Terputus"
                tvSub.text = "Ketuk tombol untuk menyambung"
                tvTimer.text = "00:00:00"
                tvDown.text = "↓ 0 B/s\n0 B"
                tvUp.text = "↑ 0 B/s\n0 B"
            }
            CONNECTING -> {
                stopPulse(); startSpin()
                animateColor(C_CONNECTING)
                tvStatus.text = "Menghubungkan…"
                tvSub.text = "Menyiapkan tunnel"
            }
            ON -> {
                stopSpin(); startPulse()
                animateColor(C_ON)
                tvStatus.text = "Terhubung"
                tvSub.text = "Trafik lewat server proxy"
                lastTx = 0L; lastRx = 0L; lastT = 0L
                handler.post(ticker)
            }
        }
    }

    private fun setExpanded(open: Boolean) {
        expanded = open
        settingsBody.visibility = if (open) View.VISIBLE else View.GONE
        tvSettingsHeader.text = if (open) "PENGATURAN PROXY  ▴" else "PENGATURAN PROXY  ▾"
    }

    private fun animateColor(to: Int) {
        val from = curColor
        curColor = to
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 450
            addUpdateListener { btnBg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun makePulse(v: View, delay: Long): ObjectAnimator {
        v.scaleX = 0.58f; v.scaleY = 0.58f
        return ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat("scaleX", 0.58f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 0.58f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0.7f, 0f)
        ).apply {
            duration = 2200
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun startPulse() {
        stopPulse()
        pulse1 = makePulse(ring1, 0)
        pulse2 = makePulse(ring2, 1100)
    }

    private fun stopPulse() {
        pulse1?.cancel(); pulse2?.cancel()
        pulse1 = null; pulse2 = null
        ring1.alpha = 0f; ring2.alpha = 0f
    }

    private fun startSpin() {
        stopSpin()
        spinner.visibility = View.VISIBLE
        spin = ObjectAnimator.ofFloat(spinner, "rotation", 0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpin() {
        spin?.cancel(); spin = null
        spinner.visibility = View.INVISIBLE
    }

    // ---------- timer & statistik ----------

    private val ticker = object : Runnable {
        override fun run() {
            if (state != ON) return
            if (!TunnelService.running) { setState(OFF); return }

            val sec = (SystemClock.elapsedRealtime() - startedAt) / 1000
            tvTimer.text = String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)

            try {
                val s = TProxyService.TProxyGetStats()
                if (s != null && s.size >= 4) {
                    val tx = s[1]
                    val rx = s[3]
                    val now = SystemClock.elapsedRealtime()
                    if (lastT != 0L) {
                        val dt = (now - lastT).coerceAtLeast(1) / 1000.0
                        val upS = ((tx - lastTx) / dt).toLong().coerceAtLeast(0)
                        val dnS = ((rx - lastRx) / dt).toLong().coerceAtLeast(0)
                        tvDown.text = "↓ ${fmt(dnS)}/s\n${fmt(rx)}"
                        tvUp.text = "↑ ${fmt(upS)}/s\n${fmt(tx)}"
                    }
                    lastTx = tx; lastRx = rx; lastT = now
                }
            } catch (_: Throwable) {}

            handler.postDelayed(this, 1000)
        }
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
        else -> String.format("%.2f GB", b / 1073741824.0)
    }
}
