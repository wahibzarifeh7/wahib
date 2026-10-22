package com.wz.rasid.ussd

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Reads the carrier's USSD reply dialog for requests sent through the dialer. It only looks at
 * screens while a request from this app is in flight, and ignores everything else.
 */
class UssdCaptureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!armed) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString()?.lowercase() ?: return
        if (PHONE_PACKAGE_HINTS.none { pkg.contains(it) }) return

        val root = rootInActiveWindow ?: event.source ?: return
        val texts = mutableListOf<String>()
        var hasProgress = false
        collect(root) { node ->
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("ProgressBar")) hasProgress = true
            if (!cls.contains("Button")) node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        }
        // The "USSD code running…" dialog comes first; wait for the real reply.
        if (hasProgress || texts.isEmpty()) return

        armed = false
        _replies.tryEmit(texts.joinToString("\n"))
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun collect(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, visit) }
    }

    override fun onInterrupt() = Unit

    companion object {
        private val PHONE_PACKAGE_HINTS = listOf("phone", "telephony", "telecom", "stk", "dialer")

        @Volatile
        private var armed = false
        private val _replies = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val replies: SharedFlow<String> = _replies

        fun arm() {
            armed = true
        }

        fun disarm() {
            armed = false
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, UssdCaptureService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
