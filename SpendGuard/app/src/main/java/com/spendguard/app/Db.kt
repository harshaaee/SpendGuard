package com.spendguard.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Txn(
    val id: Long,
    val amount: Double,
    val merchant: String,
    val time: Long,
    val source: String // "sms" or "manual"
)

class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "spendguard.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx).also { inst = it } }

        /** Same amount within this window from another sender = the same payment (bank SMS + UPI app SMS). */
        private const val DUP_WINDOW_MS = 3 * 60 * 1000L
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE txn(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                merchant TEXT NOT NULL,
                time INTEGER NOT NULL,
                source TEXT NOT NULL,
                sms_key TEXT UNIQUE
            )"""
        )
        db.execSQL("CREATE INDEX idx_time ON txn(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    /** Returns true if a new row was inserted. */
    fun addFromSms(amount: Double, merchant: String, time: Long, smsKey: String): Boolean {
        val db = writableDatabase
        // Skip if this exact SMS was already stored.
        db.rawQuery("SELECT 1 FROM txn WHERE sms_key=?", arrayOf(smsKey)).use { if (it.moveToFirst()) return false }
        // Skip if the same amount was already logged a few minutes either side (duplicate alert for one payment).
        db.rawQuery(
            "SELECT 1 FROM txn WHERE source='sms' AND ABS(amount-?)<0.01 AND time BETWEEN ? AND ?",
            arrayOf(amount.toString(), (time - DUP_WINDOW_MS).toString(), (time + DUP_WINDOW_MS).toString())
        ).use { if (it.moveToFirst()) return false }

        val cv = ContentValues().apply {
            put("amount", amount); put("merchant", merchant); put("time", time)
            put("source", "sms"); put("sms_key", smsKey)
        }
        return db.insertWithOnConflict("txn", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun addManual(amount: Double, merchant: String, time: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("amount", amount); put("merchant", merchant.ifBlank { "Cash / other" })
            put("time", time); put("source", "manual")
        }
        writableDatabase.insert("txn", null, cv)
    }

    fun delete(id: Long) {
        // For SMS rows, keep a hidden zero-amount row so a rescan of the inbox doesn't add it back.
        val db = writableDatabase
        db.rawQuery("SELECT sms_key FROM txn WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) {
                db.execSQL("UPDATE txn SET amount=0, source='deleted' WHERE id=?", arrayOf<Any>(id))
                return
            }
        }
        db.delete("txn", "id=?", arrayOf(id.toString()))
    }

    fun sumBetween(from: Long, to: Long): Double =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM txn WHERE source!='deleted' AND time>=? AND time<?",
            arrayOf(from.toString(), to.toString())
        ).use { it.moveToFirst(); it.getDouble(0) }

    fun recent(limit: Int = 100): List<Txn> =
        readableDatabase.rawQuery(
            "SELECT id,amount,merchant,time,source FROM txn WHERE source!='deleted' ORDER BY time DESC LIMIT $limit",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Txn(c.getLong(0), c.getDouble(1), c.getString(2), c.getLong(3), c.getString(4)))
            }
        }
}
