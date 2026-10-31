package com.spendguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Android doesn't let one app kill another, so "closing" a UPI app works like this:
 * when a blocked app comes to the foreground and a limit is crossed, we press Home
 * and show a full-screen "limit reached" page.
 */
class BlockerService : AccessibilityService() {

    companion object {
        @Volatile var instance: BlockerService? = null
            private set
    }

    private var lastPkg: String? = null
    private var lastBlockAt = 0L

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        lastPkg = pkg
        maybeBlock(pkg)
    }

    /** Called when a new spend SMS arrives, in case a UPI app is already open. */
    fun recheckNow() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: lastPkg ?: return
        maybeBlock(pkg)
    }

    private fun maybeBlock(pkg: String) {
        val limits = Limits(this)
        if (!limits.shouldBlock(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 800) return
        lastBlockAt = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pkg", pkg)
        )
    }

    override fun onInterrupt() {}
}
