package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

fun notifyChannel(context: Context, id: String, name: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(id) != null) return
    manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
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
                publish(state.value.copy(running = false, message = "Cancelled", done = true, success = false))
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

    private fun publish(status: BuildStatus) {
        state.value = status
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (status.mode == MODE_DECODE) "Decoding APK" else "Rebuilding clone")
            .setContentText(status.message.take(80))
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
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }
        stopSelf()
    }

    private fun begin(id: String, mode: String) {
        job?.cancel()
        job = scope.launch {
            val store = ArinApp.store()
            val toolchain = Toolchain(this@BypassBuildService) { line ->
                store.appendLog(id, line)
                publish(state.value.copy(message = line.take(80)))
            }
            publish(BuildStatus(running = true, projectId = id, mode = mode, progress = 0.05f, message = "Starting"))
            try {
                if (mode == MODE_DECODE) decode(id, store, toolchain)
                else rebuild(id, store, toolchain)
            } catch (t: Throwable) {
                store.appendLog(id, "build error: ${t.message}")
                publish(state.value.copy(running = false, done = true, success = false, message = "Failed: ${t.message}"))
            } finally {
                stopForegroundCompat()
            }
        }
    }

    private suspend fun decode(id: String, store: BypassStore, toolchain: Toolchain) {
        val pristine = File(store.pristineDir(id), "base.apk")
        if (!pristine.isFile) {
            publish(state.value.copy(running = false, done = true, success = false, message = "No pristine base.apk"))
            return
        }
        val work = store.workDir(id)
        work.listFiles()?.forEach { it.deleteRecursively() }
        val project = store.load(id)
        if (project == null) {
            publish(state.value.copy(running = false, done = true, success = false, message = "Project missing"))
            return
        }
        publish(state.value.copy(progress = 0.15f, message = "apktool decode"))
        if (toolchain.linuxAvailable()) {
            val decoded = toolchain.sh(
                "apktool d -f -s -o \"${work.absolutePath}\" \"${pristine.absolutePath}\"",
                timeoutMs = 900_000
            )
            publish(state.value.copy(progress = 0.6f, message = "JADX java view"))
            toolchain.sh(
                "jadx -d \"${File(work, "java").absolutePath}\" -r -q \"${pristine.absolutePath}\"",
                timeoutMs = 900_000
            )
            if (!decoded.ok) {
                store.appendLog(id, "apktool failed, falling back to a raw unzip")
                val count = ApkIntake(this@BypassBuildService).extractTree(pristine, work)
                store.appendLog(id, "raw unzip wrote $count file(s)")
            }
        } else {
            store.appendLog(id, "Linux box missing — decoded with a raw unzip, smali is NOT available. Install the toolchain to patch.")
            val count = ApkIntake(this@BypassBuildService).extractTree(pristine, work)
            store.appendLog(id, "raw unzip wrote $count file(s)")
        }
        store.appendLog(id, "decode finished")
        store.save(project.copy(stage = Stage.DECODED))
        publish(state.value.copy(running = false, done = true, success = true, progress = 1f, message = "Decoded"))
    }

    private suspend fun rebuild(id: String, store: BypassStore, toolchain: Toolchain) {
        val project = store.load(id)
        if (project == null) {
            publish(state.value.copy(running = false, done = true, success = false, message = "Project missing"))
            return
        }
        val work = store.workDir(id)
        if (!work.isDirectory || work.listFiles().isNullOrEmpty()) {
            publish(state.value.copy(running = false, done = true, success = false, message = "Nothing decoded yet"))
            return
        }
        val outDir = store.buildOutDir(id)
        outDir.listFiles()?.forEach { it.deleteRecursively() }
        val unsigned = File(outDir, "clone-unsigned.apk")
        publish(state.value.copy(progress = 0.2f, message = "apktool build"))
        var built = false
        if (toolchain.linuxAvailable()) {
            val result = toolchain.sh(
                "apktool b \"${work.absolutePath}\" -o \"${unsigned.absolutePath}\"",
                timeoutMs = 900_000
            )
            built = result.ok && unsigned.isFile
            if (!built) store.appendLog(id, "apktool build failed — trying a plain zip at ${result.code}")
        } else {
            store.appendLog(id, "Linux missing — rebuilding with a plain zip (smali not compiled to dex)")
        }
        if (!built) {
            zipTree(work, unsigned)
            built = unsigned.isFile
        }
        if (!built) {
            publish(state.value.copy(running = false, done = true, success = false, message = "Rebuild failed"))
            return
        }
        publish(state.value.copy(progress = 0.55f, message = "zipalign"))
        val aligned = File(outDir, "clone-aligned.apk")
        toolchain.sh("zipalign -f 4 \"${unsigned.absolutePath}\" \"${aligned.absolutePath}\"", timeoutMs = 300_000)
        val toSign = if (aligned.isFile) aligned else unsigned
        publish(state.value.copy(progress = 0.75f, message = "Signing (v2 + v3)"))
        val keystore = if (project.customKeystorePath != null && File(project.customKeystorePath).isFile) {
            File(project.customKeystorePath)
        } else {
            val generated = store.keystoreFile(id)
            if (!generated.isFile) toolchain.ensureKeystore(project)
            generated
        }
        val password = Toolchain.PASSWORD
        val sign = toolchain.sh(
            "uber-apk-signer --apks \"${toSign.absolutePath}\" --ks \"${keystore.absolutePath}\" " +
                "--ksAlias ${Toolchain.ALIAS} --ksPass $password --ksKeyPass $password --allowResign --overwrite",
            timeoutMs = 600_000
        )
        var signed: File? = outDir.listFiles()
            ?.filter { it.isFile && it.extension == "apk" && it.name.contains("signed") }
            ?.maxByOrNull { it.lastModified() }
        if (signed == null && toolchain.linuxAvailable()) {
            val direct = toolchain.sh(
                "apksigner sign --ks \"${keystore.absolutePath}\" --ks-pass pass:$password --key-pass pass:$password " +
                    "--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \"${toSign.absolutePath}\"",
                timeoutMs = 600_000
            )
            if (direct.ok) signed = toSign
        }
        if (signed == null || !signed.isFile) {
            store.appendLog(id, "signing failed: ${sign.err.take(300)}")
            publish(state.value.copy(running = false, done = true, success = false, message = "Signing failed"))
            return
        }
        val finalApk = File(outDir, "clone-signed.apk")
        runCatching { signed.copyTo(finalApk, overwrite = true) }
        store.appendLog(id, "rebuilt and signed: ${finalApk.name}")
        store.save(project.copy(stage = Stage.REBUILT, rebuiltApkPath = finalApk.absolutePath))
        publish(
            state.value.copy(
                running = false, done = true, success = true, progress = 1f,
                message = "Built and signed", output = finalApk.absolutePath
            )
        )
    }

    private fun zipTree(root: File, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { stream ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath
                if (name == "apktool.yml" || name.startsWith("java/")) return@forEach
                runCatching {
                    stream.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(stream) }
                    stream.closeEntry()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "bypass_build"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.aibuilt.addanewprofessionaltabtoarinca.bypass.BUILD_START"
        const val ACTION_CANCEL = "com.aibuilt.addanewprofessionaltabtoarinca.bypass.BUILD_CANCEL"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_MODE = "mode"
        const val MODE_DECODE = "decode"
        const val MODE_REBUILD = "rebuild"

        val state = MutableStateFlow(BuildStatus())
        val status: StateFlow<BuildStatus> = state

        private fun cancelIntent(context: Context) =
            android.app.PendingIntent.getService(
                context,
                7,
                Intent(context, BypassBuildService::class.java).apply { action = ACTION_CANCEL },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

        fun start(context: Context, projectId: String, mode: String) {
            val intent = Intent(context, BypassBuildService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECT, projectId)
                putExtra(EXTRA_MODE, mode)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BypassBuildService::class.java).apply { action = ACTION_CANCEL }
                )
            }
        }
    }
}
