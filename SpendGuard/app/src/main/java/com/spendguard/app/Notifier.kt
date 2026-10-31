package com.spendguard.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Alerts once at 80% and once at 100% for each day / week / month. */
object Notifier {
    private const val CH = "limits"

    fun checkAndAlert(ctx: Context) {
        val limits = Limits(ctx)
        for (s in limits.allStatus()) {
            if (s.limit <= 0) continue
            val key = "${s.period.name}_${limits.periodStart(s.period)}"
            val level = when {
                s.spent >= s.limit -> 2
                s.spent >= s.limit * 0.8 -> 1
                else -> 0
            }
            if (level > limits.alertLevelSent(key)) {
                limits.setAlertLevelSent(key, level)
                val title = if (level == 2) "${s.period.label}: limit reached" else "${s.period.label}: 80% used"
                val text = "Spent ${rupees(s.spent)} of ${rupees(s.limit)}" +
                    if (level == 2 && limits.blockingEnabled) " — UPI apps are now locked." else ""
                show(ctx, s.period.ordinal, title, text)
            }
        }
    }

    private fun show(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Spending limits", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(100 + id, n)
    }
}

fun rupees(v: Double): String {
    val n = java.text.NumberFormat.getCurrencyInstance(java.util.Locale("en", "IN"))
    n.maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2
    return n.format(v)
}
