package com.spendguard.app

/**
 * Turns a bank / UPI SMS into a spending entry, or null if it isn't a debit.
 * Pure Kotlin so it can be unit-tested without a phone.
 */
object SmsParser {

    data class Parsed(val amount: Double, val merchant: String)

    private val DEBIT_WORDS = listOf(
        "debited", "debit", "spent", "paid", "sent", "withdrawn", "withdrawal",
        "purchase", "transferred", "txn of", "payment of", "dr."
    )

    // Messages that mention money but are not an actual spend.
    private val IGNORE_WORDS = listOf(
        "otp", "one time password", "will be debited", "to be debited", "requested",
        "request of", "is due", "due on", "due date", "min amt", "minimum amount",
        "statement", "e-mandate", "mandate", "autopay set", "reversed", "refund",
        "cashback", "declined", "failed", "unsuccessful", "offer", "win ", "loan of",
        "pre-approved", "eligible"
    )

    private val AMOUNT = Regex(
        """(?:rs\.?|inr|₹)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?:to|at|towards)\s+(?:vpa\s+)?([A-Za-z0-9@._&\- ]{3,40}?)(?:\s+on\b|\s+via\b|\s+ref\b|\s+upi\b|\.|,|;|\s+avl|\s+-|$)""", RegexOption.IGNORE_CASE),
        Regex("""info[:\s]+([A-Za-z0-9@._&/\- ]{3,40})""", RegexOption.IGNORE_CASE)
    )

    /** Bank / wallet SMS come from alphanumeric senders like "AX-HDFCBK"; personal numbers are ignored. */
    fun isLikelyBankSender(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return address.any { it.isLetter() }
    }

    fun parse(body: String): Parsed? {
        val text = body.lowercase()
        if (IGNORE_WORDS.any { text.contains(it) }) return null

        val isDebit = DEBIT_WORDS.any { text.contains(it) }
        if (!isDebit) return null

        // "credited" alone means money came in. Keep it only if the SMS also clearly says debited/sent/paid
        // (e.g. "debited from your a/c ... credited to merchant").
        if (text.contains("credited") &&
            listOf("debited", "sent", "paid", "spent", "withdrawn").none { text.contains(it) }
        ) return null

        val amount = firstTxnAmount(body) ?: return null
        if (amount <= 0.0 || amount > 10_000_000) return null

        return Parsed(amount, extractMerchant(body))
    }

    /** First amount that isn't the "available balance" / "limit" figure. */
    private fun firstTxnAmount(body: String): Double? {
        for (m in AMOUNT.findAll(body)) {
            val before = body.substring(maxOf(0, m.range.first - 20), m.range.first).lowercase()
            if (before.contains("bal") || before.contains("limit") || before.contains("avl")) continue
            return m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return null
    }

    private fun extractMerchant(body: String): String {
        for (p in MERCHANT_PATTERNS) {
            val m = p.find(body) ?: continue
            val name = m.groupValues[1].trim().trimEnd('.', ',')
            if (name.isNotBlank() && !name.lowercase().startsWith("your")) return name.take(40)
        }
        return "Payment"
    }
}
