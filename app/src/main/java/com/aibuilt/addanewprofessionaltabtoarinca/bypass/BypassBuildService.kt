package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aibuilt.addanewprofessionaltabtoarinca.ArinApp
import com.aibuilt.addanewprofessionaltabtoarinca.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BuildStatus(
    val running: Boolean = false,
    val projectId: String = "",
    val mode: String = "",
    val progress: Float = 0f,
    val message: String = "",
    val done: Boolean = false,
    val success: Boolean = false,
    val output: String? = null
)

/** Shared progress so the screen and the notification see the same numbers. */
object BuildBus {
    private val _state = MutableStateFlow(BuildStatus())
    val state: StateFlow<BuildStatus> = _state.asStateFlow()
    fun publish(status: BuildStatus) {
        _state.value = status
    }
}

class BypassBuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifyChannel(this, CHANNEL, "Bypass builds")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                job = null
                BuildBus.publish(
                    BuildBus.state.value.copy(running = false, done = true, success = false, message = "Cancelled")
                )
                stopForegroundCompat()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_REBUILD
                if (id.isNotBlank()) {
                    foreground("Starting")
                    begin(id, mode)
                }
            }
        }
        return START_STICKY
    }

    private fun begin(projectId: String, mode: String) {
        if (job?.isActive == true) return
        val app = application as? ArinApp ?: return
        val store = app.store
        val service = this
        job = scope.launch {
            val project = store.load(projectId)
            if (project == null) {
                BuildBus.publish(BuildStatus(projectId = projectId, done = true, message = "Project missing"))
                stopForegroundCompat()
                stopSelf()
                return@launch
            }
            val toolchain = Toolchain(service) { line -> store.appendLog(projectId, line) }
            val patcher = Patcher(toolchain) { line -> store.appendLog(projectId, line) }
            store.appendLog(projectId, "rebuild started")
            BuildBus.publish(BuildStatus(running = true, projectId = projectId, mode = mode, message = "Starting"))
            val (ok, text) = Rebuilder.run(service, store, toolchain, patcher, projectId) { progress, message ->
                publish(BuildBus.state.value.copy(progress = progress, message = message), message)
            }
            store.appendLog(projectId, if (ok) "rebuild ok: $text" else "rebuild failed: $text")
            BuildBus.publish(
                BuildBus.state.value.copy(
                    running = false,
                    done = true,
                    success = ok,
                    output = if (ok) text else null,
                    message = if (ok) "Clone signed and ready" else text.take(300)
                )
            )
            notifyChannel(service, CHANNEL, "Bypass builds")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun publish(status: BuildStatus, text: String) {
        BuildBus.publish(status)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rebuilding the clone")
            .setContentText(text.take(80))
            .setProgress(100, (status.progress.coerceIn(0f, 1f) * 100).toInt(), false)
            .setOngoing(status.running)
            .addAction(0, "Cancel", cancelIntent(this))
            .build()
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType()) }
    }

    private fun foreground(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Arin Bypass")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Cancel", cancelIntent(this))
            .build()
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType()) }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "bypass_build"
        const val NOTIFICATION_ID = 4711
        const val ACTION_START = "com.aibuilt.addanewprofessionaltabtoarinca.BUILD_START"
        const val ACTION_CANCEL = "com.aibuilt.addanewprofessionaltabtoarinca.BUILD_CANCEL"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_MODE = "mode"
        const val MODE_REBUILD = "rebuild"
        const val MODE_DECODE = "decode"

        fun start(context: Context, projectId: String) {
            val intent = Intent(context, BypassBuildService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECT, projectId)
                putExtra(EXTRA_MODE, MODE_REBUILD)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BypassBuildService::class.java).apply { action = ACTION_CANCEL }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        private fun cancelIntent(context: Context): PendingIntent {
            val intent = Intent(context, BypassBuildService::class.java).apply { action = ACTION_CANCEL }
            return PendingIntent.getForegroundService(
                context,
                99,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

@Suppress("unused")
private fun openArin(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Suppress("unused")
private fun notificationManager(context: Context): NotificationManager? =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
