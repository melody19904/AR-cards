package com.example.cardengine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.SecureRandom

class VaultHelper(context: Context) : SQLiteOpenHelper(context, "CardVault.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE unlocked_cards (card_id TEXT PRIMARY KEY, timestamp INTEGER, token TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: add the bearer token column used by P2P transfer QR codes.
        // Existing rows get token = NULL; tokenFor() lazily backfills them.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE unlocked_cards ADD COLUMN token TEXT")
        }
    }

    /** @return true the first time this card is unlocked, false if it was already in the vault. */
    fun unlockCard(cardId: String): Boolean {
        val values = ContentValues().apply {
            put("card_id", cardId)
            put("timestamp", System.currentTimeMillis())
            put("token", newToken())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "unlocked_cards", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    fun isUnlocked(cardId: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM unlocked_cards WHERE card_id = ?", arrayOf(cardId)).use {
            return it.moveToFirst()
        }
    }

    /** All card IDs the user has ever unlocked, ordered oldest-first. */
    fun allUnlockedIds(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT card_id FROM unlocked_cards ORDER BY timestamp ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /**
     * The bearer token embedded in this card's P2P transfer QR code.
     * Generated lazily so cards unlocked before this column existed still get one
     * the first time a trade QR is requested for them.
     *
     * @return null if the card isn't actually in the vault.
     */
    fun tokenFor(cardId: String): String? {
        readableDatabase.rawQuery(
            "SELECT token FROM unlocked_cards WHERE card_id = ?", arrayOf(cardId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val existing = c.getString(0)
            if (!existing.isNullOrEmpty()) return existing
        }
        val fresh = newToken()
        writableDatabase.update(
            "unlocked_cards",
            ContentValues().apply { put("token", fresh) },
            "card_id = ?", arrayOf(cardId)
        )
        return fresh
    }

    /** Removes a card from the local vault record — used once it's been traded away. */
    fun removeCard(cardId: String): Boolean {
        return writableDatabase.delete("unlocked_cards", "card_id = ?", arrayOf(cardId)) > 0
    }

    /** Wipes the SQLite unlock record. Does NOT touch VaultStore files. */
    fun clearAll() {
        writableDatabase.delete("unlocked_cards", null, null)
    }

    companion object {
        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}