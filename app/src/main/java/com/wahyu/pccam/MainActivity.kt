package com.wahyu.pccam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var pinBox: EditText
    private lateinit var btn: Button
    private lateinit var blackBtn: Button
    private lateinit var normalRoot: View
    private var blackMode = false
    private var lastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply { text = "PC Cam - Pengawas"; textSize = 26f }
        pinBox = EditText(this).apply {
            hint = "PIN (huruf/angka)"
            setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("pin", "1234"))
        }
        btn = Button(this)
        blackBtn = Button(this).apply { text = "Mode layar hitam" }
        info = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, 0) }
        val tips = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "Tips ujian:\n" +
                    "- Cas HP dan letakkan di dudukan.\n" +
                    "- Pakai Mode layar hitam agar kamera tidak dimatikan sistem.\n" +
                    "- Di Pengaturan > Aplikasi > PC Cam: Mulai otomatis ON, Penghemat baterai = Tanpa batasan.\n" +
                    "- Rekaman bisa diunduh dari halaman PC (tombol Rekaman)."
        }
        root.addView(title)
        root.addView(pinBox)
        root.addView(btn)
        root.addView(blackBtn)
        root.addView(info)
        root.addView(tips)
        normalRoot = root
        setContentView(root)

        btn.setOnClickListener {
            if (CamService.running) stopCam() else requestAndStart()
        }
        blackBtn.setOnClickListener {
            if (CamService.running) enterBlack()
            else Toast.makeText(this, "Tekan START dulu", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (blackMode) exitBlack() else super.onBackPressed()
    }

    private fun refreshUi() {
        if (CamService.running) {
            btn.text = "STOP"
            val ip = getIp() ?: "IP-HP"
            info.text = "Aktif.\nBuka di browser PC (satu Wi-Fi):\nhttp://$ip:8080"
        } else {
            btn.text = "START"
            info.text = "Belum aktif."
        }
    }

    // ---------- mode layar hitam ----------
    private fun enterBlack() {
        blackMode = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val v = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@MainActivity).apply {
                text = "Mode layar hitam aktif\nKetuk 2x untuk keluar"
                setTextColor(Color.rgb(40, 40, 40))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener {
                val n = System.currentTimeMillis()
                if (n - lastTap < 500) exitBlack()
                lastTap = n
            }
        }
        setContentView(v)
    }

    private fun exitBlack() {
        blackMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setContentView(normalRoot)
        refreshUi()
    }

    // ---------- start / stop ----------
    private fun requestAndStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) startCam() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam()
        else info.text = "Izin kamera diperlukan."
    }

    private fun startCam() {
        val pin = pinBox.text.toString().ifBlank { "1234" }
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("pin", pin).apply()
        val i = Intent(this, CamService::class.java).putExtra("pin", pin)
        ContextCompat.startForegroundService(this, i)
        CamService.running = true
        refreshUi()
        askBatteryOptimization()
    }

    private fun askBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBat", false)) {
            prefs.edit().putBoolean("askedBat", true).apply()
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) { }
        }
    }

    private fun stopCam() {
        stopService(Intent(this, CamService::class.java))
        CamService.running = false
        refreshUi()
    }

    private fun getIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }
}
