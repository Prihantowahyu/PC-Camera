package com.wahyu.pccam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CamService : LifecycleService() {

    companion object {
        @Volatile var running = false
        @Volatile var frame: ByteArray? = null
        @Volatile var frameId = 0L
        @Volatile var pin = "1234"
        @Volatile var instance: CamService? = null
        @Volatile var extraRot = 0
        val lock = Object()
    }

    private var server: CamServer? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var useBack = true
    @Volatile private var zoom = 1f
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var lastLive = 0L

    // rekaman
    private val recLock = Any()
    @Volatile var recording = false
        private set
    private var writer: AviWriter? = null
    private var recStart = 0L
    private var fileStart = 0L
    private var lastRec = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        pin = intent?.getStringExtra("pin") ?: "1234"
        instance = this
        showNotification()

        synchronized(lock) { frame = null; frameId = 0; running = true }

        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pccam:wl").also { it.acquire() }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pccam:wifi")
                .also { it.acquire() }
        }
        if (server == null) {
            server = CamServer(8080).also { it.start(30000, false) }
        }
        bindCamera()
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("cam", "Kamera aktif", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, "cam")
            .setContentTitle("PC Cam aktif")
            .setContentText("Kamera dibagikan ke PC lewat Wi-Fi. Buka aplikasi lalu STOP untuk mematikan.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { img ->
                try { process(img) } finally { img.close() }
            }
            val selector = if (useBack) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
            p.unbindAll()
            camera = p.bindToLifecycle(this, selector, analysis)
            camera?.cameraControl?.setZoomRatio(zoom)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(img: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastLive < 100) return   // batasi ~10 fps
        lastLive = now

        var bmp = img.toBitmap()
        val rot = (img.imageInfo.rotationDegrees + extraRot) % 360
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        stamp(bmp)

        val bos = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        val jpg = bos.toByteArray()
        synchronized(lock) {
            frame = jpg
            frameId++
            lock.notifyAll()
        }
        record(jpg, bmp.width, bmp.height, now)
    }

    private fun stamp(bmp: Bitmap) {
        val c = Canvas(bmp)
        stampPaint.textSize = bmp.height / 30f
        val txt = fmt.format(Date()) + if (recording) "  [REC]" else ""
        val m = bmp.height / 60f
        c.drawText(txt, m, bmp.height - m, stampPaint)
    }

    // ---------- rekaman ----------
    private fun dir(): File {
        val d = getExternalFilesDir("rekaman") ?: File(filesDir, "rekaman")
        d.mkdirs()
        return d
    }

    fun startRecording() {
        synchronized(recLock) {
            if (recording) return
            recording = true
            recStart = System.currentTimeMillis()
            lastRec = 0L
        }
    }

    fun stopRecording() {
        synchronized(recLock) { stopRecordingLocked() }
    }

    private fun stopRecordingLocked() {
        recording = false
        writer?.finish()
        writer = null
    }

    private fun record(jpg: ByteArray, w: Int, h: Int, now: Long) {
        synchronized(recLock) {
            if (!recording) return
            if (now - lastRec < 200) return   // 5 fps
            lastRec = now
            try {
                var wr = writer
                if (wr != null && (wr.w != w || wr.h != h || wr.bytes > 1_500_000_000L ||
                            now - fileStart > 30 * 60 * 1000L)) {
                    wr.finish(); writer = null; wr = null
                }
                if (wr == null) {
                    if (dir().usableSpace < 300L * 1024 * 1024) { stopRecordingLocked(); return }
                    val name = "rekam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date()) + ".avi"
                    wr = AviWriter(File(dir(), name))
                    writer = wr
                    fileStart = now
                }
                wr.add(jpg, w, h)
                if (wr.frames % 600 == 0 && dir().usableSpace < 200L * 1024 * 1024) stopRecordingLocked()
            } catch (e: Exception) {
                stopRecordingLocked()
            }
        }
    }

    fun filesJson(): String {
        val list = dir().listFiles { f -> f.name.endsWith(".avi") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        return list.joinToString(",", "[", "]") {
            "{\"name\":\"" + it.name + "\",\"mb\":" + (it.length() / 1048576) + "}"
        }
    }

    fun fileFor(name: String?): File? {
        if (name == null) return null
        val n = File(name).name
        if (!n.endsWith(".avi")) return null
        return File(dir(), n)
    }

    fun deleteFile(name: String?): Boolean {
        val f = fileFor(name) ?: return false
        return f.exists() && f.delete()
    }

    // ---------- kontrol ----------
    fun setZoom(v: Float) {
        val st = camera?.cameraInfo?.zoomState?.value
        val mx = st?.maxZoomRatio ?: 1f
        val mn = st?.minZoomRatio ?: 1f
        zoom = v.coerceIn(mn, mx)
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    fun switchCamera() {
        useBack = !useBack
        zoom = 1f
        Handler(Looper.getMainLooper()).post { bindCamera() }
    }

    fun statusJson(): String {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val maxZ = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        val recSec = if (recording) (System.currentTimeMillis() - recStart) / 1000 else 0L
        val freeMb = dir().usableSpace / 1048576
        return "{\"battery\":$pct,\"charging\":$plugged,\"back\":$useBack,\"rec\":$recording," +
                "\"recSec\":$recSec,\"zoom\":$zoom,\"maxZoom\":$maxZ,\"rot\":$extraRot,\"freeMb\":$freeMb}"
    }

    override fun onDestroy() {
        synchronized(lock) { running = false; lock.notifyAll() }
        stopRecording()
        server?.stop()
        server = null
        provider?.unbindAll()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        executor.shutdown()
        instance = null
        super.onDestroy()
    }
}
