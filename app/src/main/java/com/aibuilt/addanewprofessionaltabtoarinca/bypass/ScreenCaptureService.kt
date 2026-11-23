package com.aibuilt.addanewprofessionaltabtoarinca.bypass

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
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var outDir: File? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifyChannel(this, CHANNEL, "Bypass screenshots")
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
        val active = projection
        if (active == null) {
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = runCatching {
            active.createVirtualDisplay(
                "bypass-shot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
            )
        }.getOrNull()
        handler.postDelayed({
            runCatching {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val padded = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                    val dir = outDir ?: File(filesDir, "shots").apply { mkdirs() }
                    dir.mkdirs()
                    val file = File(dir, "shot_${System.currentTimeMillis()}.png")
                    file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    lastShot.value = file.absolutePath
                    image.close()
                }
                runCatching { reader.close() }
                runCatching { display?.release() }
                stopSelf()
            }
        }, 900)
    }

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        active = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "bypass_screens"
        private const val NOTIFICATION_ID = 4202
        const val ACTION_START = "com.aibuilt.addanewprofessionaltabtoarinca.bypass.SHOT_START"
        const val ACTION_CAPTURE = "com.aibuilt.addanewprofessionaltabtoarinca.bypass.SHOT_CAPTURE"
        const val ACTION_STOP = "com.aibuilt.addanewprofessionaltabtoarinca.bypass.SHOT_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DIR = "dir"

        val lastShot = MutableStateFlow<String?>(null)

        @Volatile
        var active: Boolean = false

        fun start(context: Context, resultCode: Int, data: Intent, dir: File) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_DIR, dir.absolutePath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun capture(context: Context, dir: File) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_DIR, dir.absolutePath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
