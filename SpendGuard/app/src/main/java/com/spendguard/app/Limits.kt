package com.spendguard.app

import android.content.Context
import java.util.Calendar

enum class Period(val label: String) { DAY("Today"), WEEK("This week"), MONTH("This month") }

data class PeriodStatus(val period: Period, val spent: Double, val limit: Double) {
    val exceeded get() = limit > 0 && spent >= limit
    val fraction get() = if (limit > 0) (spent / limit).coerceAtMost(1.0).toFloat() else 0f
    val remaining get() = (limit - spent).coerceAtLeast(0.0)
}

/** Settings + period math. Week runs Monday–Sunday. 0 means "no limit". */
class Limits(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("limits", Context.MODE_PRIVATE)
    private val db = Db.get(ctx)

    companion object {
        val DEFAULT_UPI_APPS = setOf(
            "com.phonepe.app",                          // PhonePe
            "com.google.android.apps.nbu.paisa.user",   // Google Pay
            "net.one97.paytm",                          // Paytm
            "in.org.npci.upiapp",                       // BHIM
            "com.dreamplug.androidapp",                 // CRED
            "com.mobikwik_new",                         // MobiKwik
            "in.amazon.mShop.android.shopping",         // Amazon (Amazon Pay)
            "com.freecharge.android",                   // Freecharge
            "money.super.payments",                     // super.money
            "com.naviapp"                               // Navi
        )
    }

    fun limit(period: Period): Double = p.getFloat("limit_${period.name}", 0f).toDouble()
    fun setLimit(period: Period, v: Double) = p.edit().putFloat("limit_${period.name}", v.toFloat()).apply()

    var blockingEnabled: Boolean
        get() = p.getBoolean("blocking", true)
        set(v) = p.edit().putBoolean("blocking", v).apply()

    var blockedApps: Set<String>
        get() = p.getStringSet("apps", null) ?: DEFAULT_UPI_APPS
        set(v) = p.edit().putStringSet("apps", v).apply()

    /** Temporary unlock (emergency payment). Epoch ms until which blocking is paused. */
    var pausedUntil: Long
        get() = p.getLong("paused_until", 0)
        set(v) = p.edit().putLong("paused_until", v).apply()

    /** Last alert level sent per period+period-start, so we notify at 80% / 100% only once. */
    fun alertLevelSent(key: String): Int = p.getInt("alert_$key", 0)
    fun setAlertLevelSent(key: String, lvl: Int) = p.edit().putInt("alert_$key", lvl).apply()

    fun periodStart(period: Period, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            Period.DAY -> {}
            Period.WEEK -> {
                val dow = c.get(Calendar.DAY_OF_WEEK) // Sunday=1 … Saturday=7
                val back = (dow + 5) % 7              // days since Monday
                c.add(Calendar.DAY_OF_MONTH, -back)
            }
            Period.MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    fun status(period: Period): PeriodStatus {
        val from = periodStart(period)
        return PeriodStatus(period, db.sumBetween(from, Long.MAX_VALUE), limit(period))
    }

    fun allStatus(): List<PeriodStatus> = Period.values().map { status(it) }

    /** The first period whose limit is crossed, or null. */
    fun breached(): PeriodStatus? = allStatus().firstOrNull { it.exceeded }

    fun shouldBlock(pkg: String): Boolean =
        blockingEnabled &&
            pkg in blockedApps &&
            System.currentTimeMillis() > pausedUntil &&
            breached() != null
}
