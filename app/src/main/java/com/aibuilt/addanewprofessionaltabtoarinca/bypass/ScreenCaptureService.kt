package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Optional per-round screenshots. Started with the MediaProjection grant, then asked to capture.
 * Because MediaProjection may only be re-used once per grant, a fresh grant is requested per session.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var outDir: File? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifyChannel(this, CHANNEL, "Arin Bypass screenshots")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                resultData = intent.getParcelableExtra(EXTRA_DATA)
                outDir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }
                foreground("Screen capture ready")
                ensureProjection()
            }
            ACTION_CAPTURE -> {
                foreground("Capturing a screenshot")
                capture()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun foreground(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Arin Bypass")
            .setContentText(text)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun ensureProjection() {
        if (projection != null) return
        val data = resultData ?: return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: return
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                active = false
            }
        }, handler)
        projection = created
        active = true
    }

    private fun capture() {
        val live = projection
        if (live == null) {
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = live.createVirtualDisplay(
            "bypass-shot",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        handler.postDelayed({
            runCatching {
                reader.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val paddedWidth = width + rowPadding / plane.pixelStride
                    val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val cropped = if (paddedWidth != width) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else bitmap
                    val dir = outDir ?: File(filesDir, "shots").apply { mkdirs() }
                    dir.mkdirs()
                    val file = File(dir, "shot_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    lastShotPath = file.absolutePath
                }
            }
            runCatching { virtualDisplay.release() }
            runCatching { reader.close() }
        }, 700)
    }

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        active = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "bypass_capture"
        const val NOTIFICATION_ID = 4712
        const val ACTION_START = "com.aibuilt.addanewprofessionaltabtoarinca.CAPTURE_START"
        const val ACTION_CAPTURE = "com.aibuilt.addanewprofessionaltabtoarinca.CAPTURE_NOW"
        const val ACTION_STOP = "com.aibuilt.addanewprofessionaltabtoarinca.CAPTURE_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DIR = "dir"

        @Volatile
        var active: Boolean = false

        @Volatile
        var lastShotPath: String? = null

        fun start(context: Context, code: Int, data: Intent, dir: String) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_DIR, dir)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun capture(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_CAPTURE }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}

fun notifyChannel(context: Context, id: String, name: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(id) != null) return
    val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
    manager.createNotificationChannel(channel)
}
