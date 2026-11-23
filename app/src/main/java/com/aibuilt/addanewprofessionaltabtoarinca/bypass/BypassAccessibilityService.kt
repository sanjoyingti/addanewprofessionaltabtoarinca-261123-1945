package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class WatchEvent(val pkg: String, val text: String, val className: String?)

class BypassAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val watched = watchingPackage
        if (watched != null && pkg != watched) return
        val builder = StringBuilder()
        event.text?.forEach { if (!TextUtils.isEmpty(it)) builder.append(it).append(' ') }
        event.contentDescription?.let { builder.append(it).append(' ') }
        val nodeText = rootInActiveWindow?.let { collect(it, 0) }.orEmpty()
        val combined = (builder.toString() + " " + nodeText).trim()
        if (combined.isBlank()) return
        val className = runCatching { event.className?.toString() }.getOrNull()
        events.tryEmit(WatchEvent(pkg, combined.take(1200), className))
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 4) return ""
        val builder = StringBuilder()
        node.text?.let { builder.append(it).append(' ') }
        node.contentDescription?.let { builder.append(it).append(' ') }
        for (index in 0 until minOf(node.childCount, 12)) {
            val child = node.getChild(index) ?: continue
            builder.append(collect(child, depth + 1))
        }
        return builder.toString()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        val events = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64)
            .let { flow -> flow as SharedFlow<WatchEvent> }

        private val backing = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64)

        @Volatile
        var watchingPackage: String? = null

        @Volatile
        var connected: Boolean = false

        fun serviceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.contains(context.packageName + "/" + BypassAccessibilityService::class.java.name) ||
                enabled.contains("bypass.BypassAccessibilityService")
        }
    }
}
