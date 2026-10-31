package com.spendguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Fires for every incoming SMS; stores debits and re-checks limits. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val sender = parts[0].originatingAddress
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val time = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        if (SmsIngest.ingest(ctx, sender, body, time)) {
            Notifier.checkAndAlert(ctx)
            BlockerService.instance?.recheckNow()
        }
    }
}

object SmsIngest {
    /** Returns true if a new spend was recorded. */
    fun ingest(ctx: Context, sender: String?, body: String, time: Long): Boolean {
        if (!SmsParser.isLikelyBankSender(sender)) return false
        val parsed = SmsParser.parse(body) ?: return false
        val key = "${sender}|${time / 1000}|${body.hashCode()}"
        return Db.get(ctx).addFromSms(parsed.amount, parsed.merchant, time, key)
    }

    /** One-time scan of this month's inbox so totals are right from day one. */
    fun scanInbox(ctx: Context): Int {
        val from = Limits(ctx).periodStart(Period.MONTH).coerceAtMost(Limits(ctx).periodStart(Period.WEEK))
        var added = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(from.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                if (ingest(ctx, c.getString(0), c.getString(1) ?: "", c.getLong(2))) added++
            }
        }
        return added
    }
}
