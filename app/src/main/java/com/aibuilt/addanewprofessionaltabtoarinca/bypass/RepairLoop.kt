package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class WatchResult(
    val outcome: String,
    val detail: String,
    val text: String = "",
    val className: String? = null
) {
    val ok: Boolean get() = outcome == OK
    companion object {
        const val OK = "OK"
        const val CRASH = "CRASH"
        const val ANR = "ANR"
        const val DEAD = "DEAD"
        const val STUCK = "STUCK"
        const val EMPTY = "EMPTY"
        const val PAYWALL = "PAYWALL"
        const val TIMEOUT = "TIMEOUT"
        const val INSTALL_FAILED = "INSTALL_FAILED"
        const val SKIPPED = "SKIPPED"
    }
}

object Installer {

    suspend fun install(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        if (!apk.isFile) return@withContext false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val result = CompletableDeferred<Boolean>()
        val action = "com.aibuilt.addanewprofessionaltabtoarinca.INSTALL_RESULT"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                ) ?: PackageInstaller.STATUS_FAILURE
                result.complete(status == PackageInstaller.STATUS_SUCCESS)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(action).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            withTimeoutOrNull(240_000) { result.await() } ?: false
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun installed(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}

class RepairLoop(
    private val context: Context,
    private val patcher: Patcher,
    private val log: suspend (String) -> Unit
) {

    private val crashWords = listOf("has stopped", "keeps stopping", "keeps crashing", "fatal exception", "nullpointer")
    private val anrWords = listOf("isn't responding", "isn’t responding", "not responding")
    private val paywallWords = listOf("subscribe", "subscription", "premium", "upgrade to", "free trial", "restore purchase", "paywall", "go pro")

    private fun alive(packageName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.any { it.processName == packageName || it.processName.startsWith("$packageName:") } == true
    }.getOrDefault(true)

    private fun classFrom(text: String): String? =
        Regex("([a-z][a-z0-9_]*(\\.[a-z0-9_]+)+\\.[A-Z][A-Za-z0-9_]*)").find(text)?.value

    /** Watches the clone for one round. One second tick: process, dialogs, screen changes, paywall. */
    suspend fun watch(
        packageName: String,
        timeoutMs: Long,
        stuckThreshold: Int,
        onStatus: suspend (String) -> Unit
    ): WatchResult = coroutineScope {
        BypassAccessibilityService.watchingPackage = packageName
        val channel = Channel<WatchEvent>(Channel.UNLIMITED)
        val collector = launch {
            BypassAccessibilityService.events.collect { event ->
                if (event.pkg == packageName) channel.send(event)
            }
        }
        val started = System.currentTimeMillis()
        var lastText = ""
        var stuck = 0
        var good = 0
        var emptySeconds = 0
        var result: WatchResult? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                while (isActive) {
                    delay(1000)
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed > 2500 && !alive(packageName)) {
                        result = WatchResult(WatchResult.DEAD, "The process died after ${elapsed / 1000}s.")
                        return@withTimeoutOrNull
                    }
                    val event = channel.tryReceive().getOrNull()
                    if (event != null) {
                        lastText = event.text
                        stuck = 0
                        val lower = event.text.lowercase()
                        when {
                            crashWords.any { lower.contains(it) } -> {
                                result = WatchResult(WatchResult.CRASH, event.text.take(400), event.text, event.className ?: classFrom(event.text))
                                return@withTimeoutOrNull
                            }
                            anrWords.any { lower.contains(it) } -> {
                                result = WatchResult(WatchResult.ANR, event.text.take(400), event.text, event.className)
                                return@withTimeoutOrNull
                            }
                            paywallWords.any { lower.contains(it) } -> {
                                result = WatchResult(WatchResult.PAYWALL, "Paywall text seen: ${event.text.take(160)}", event.text, event.className)
                                return@withTimeoutOrNull
                            }
                            event.text.isBlank() -> emptySeconds++
                            else -> {
                                emptySeconds = 0
                                good++
                            }
                        }
                        if (emptySeconds >= 10) {
                            result = WatchResult(WatchResult.EMPTY, "Nothing drawn for 10 seconds.")
                            return@withTimeoutOrNull
                        }
                        if (good >= 3) {
                            onStatus("usable screen reached")
                            result = WatchResult(WatchResult.OK, event.text.take(200))
                            return@withTimeoutOrNull
                        }
                    } else {
                        stuck++
                        onStatus("screen unchanged for ${stuck}s")
                        if (stuck >= stuckThreshold) {
                            result = WatchResult(WatchResult.STUCK, "Screen unchanged for ${stuck}s (stuck spinner).", lastText, classFrom(lastText))
                            return@withTimeoutOrNull
                        }
                    }
                }
            }
            result ?: WatchResult(WatchResult.TIMEOUT, "Round timed out after ${timeoutMs / 1000}s.", lastText, classFrom(lastText))
        } finally {
            collector.cancel()
            BypassAccessibilityService.watchingPackage = null
        }
    }

    /**
     * Maps a failure back to the smali class that produced it and proposes the smallest fix:
     * stub the crashing method (default object, const true, empty string) or force the boolean.
     */
    suspend fun generateFix(root: File, className: String?, text: String): PatchPreview? = withContext(Dispatchers.IO) {
        if (className != null) {
            val rel = className.replace('.', '/') + ".smali"
            val matches = patcher.smaliFiles(root).filter { it.invariantSeparatorsPath.endsWith(rel) }
            for (file in matches.take(2)) {
                val relPath = file.relativeTo(root).invariantSeparatorsPath
                val preview = patcher.stubClass(root, relPath)
                if (preview.hasChanges) {
                    log("fix candidate: default object for $className")
                    return@withContext preview.copy(summary = "Round fix: default object for $className")
                }
            }
            val hits = patcher.findUsages(root, "L${className.replace('.', '/')};", 20).map { it.relPath }.distinct()
            for (relPath in hits.take(2)) {
                val preview = patcher.stubClass(root, relPath)
                if (preview.hasChanges) {
                    log("fix candidate: stub $relPath")
                    return@withContext preview.copy(summary = "Round fix: stub $relPath")
                }
            }
        }
        val words = text.split(Regex("[^A-Za-z]+")).filter { it.length in 4..24 }.distinct().take(3)
        for (word in words) {
            val preview = patcher.forceBooleanMethods(root, Regex("(?i)$word"), true, "Round fix: force $word to true")
            if (preview.hasChanges) {
                log("fix candidate: force $word true")
                return@withContext preview
            }
        }
        null
    }

    fun accessibilityEnabled(): Boolean = BypassAccessibilityService.connected
}
