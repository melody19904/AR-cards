package com.example.cardengine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class VaultStore(context: Context) : SQLiteOpenHelper(context, "vault.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cards (" +
                    "id TEXT PRIMARY KEY, " +
                    "name TEXT, " +
                    "rarity TEXT, " +
                    "description TEXT, " +
                    "render BLOB, " +
                    "ref BLOB, " +
                    "physical_redeemed INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE animations (" +
                    "card_id TEXT, " +
                    "anim_name TEXT, " +
                    "bundle TEXT, " +
                    "PRIMARY KEY(card_id, anim_name))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cards ADD COLUMN physical_redeemed INTEGER DEFAULT 0")
        }
    }

    fun saveCard(
        id: String,
        name: String,
        rarity: String,
        desc: String,
        render: ByteArray,
        ref: ByteArray
    ) {
        val db = writableDatabase

        val insert = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("rarity", rarity)
            put("description", desc)
            put("render", render)
            put("ref", ref)
        }

        db.insertWithOnConflict(
            "cards", null, insert, SQLiteDatabase.CONFLICT_IGNORE
        )

        // Always refresh metadata, but never replace a good image with an empty blob.
        val update = ContentValues().apply {
            put("name", name)
            put("rarity", rarity)
            put("description", desc)
            if (render.isNotEmpty()) put("render", render)
            if (ref.isNotEmpty()) put("ref", ref)
        }

        db.update("cards", update, "id = ?", arrayOf(id))
    }

    fun hasCard(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM cards WHERE id = ?", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    fun listCards(): JSONArray {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, name, rarity, description, physical_redeemed FROM cards", null)
        val arr = JSONArray()
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getString(0))
            obj.put("name", cursor.getString(1))
            obj.put("rarity", cursor.getString(2))
            obj.put("description", cursor.getString(3))
            obj.put("physical_redeemed", cursor.getInt(4) == 1)
            arr.put(obj)
        }
        cursor.close()
        return arr
    }

    fun getRender(id: String): ByteArray? {
        readableDatabase.rawQuery("SELECT render FROM cards WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) return it.getBlob(0)
        }
        return null
    }

    fun getReference(id: String): ByteArray? {
        readableDatabase.rawQuery("SELECT ref FROM cards WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) return it.getBlob(0)
        }
        return null
    }

    fun deleteCard(id: String) {
        writableDatabase.delete("cards", "id = ?", arrayOf(id))
        writableDatabase.delete("animations", "card_id = ?", arrayOf(id))
    }

    fun saveAnimation(cardId: String, animName: String, bundle: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("card_id", cardId)
            put("anim_name", animName)
            put("bundle", bundle)
        }
        db.insertWithOnConflict("animations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAnimation(cardId: String, animName: String): String? {
        val db = readableDatabase

        // Use rawQueryWithFactory to override the default 2MB CursorWindow limit to 4MB
        val cursor = db.rawQueryWithFactory(
            { _, masterQuery, editTable, query ->
                val c = android.database.sqlite.SQLiteCursor(masterQuery, editTable, query)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    c.window = android.database.CursorWindow("large_window", 4L * 1024L * 1024L) // 4 MB
                }
                c
            },
            "SELECT bundle FROM animations WHERE card_id = ? AND anim_name = ?",
            arrayOf(cardId, animName),
            null,
            null
        )

        var bundle: String? = null
        if (cursor.moveToFirst()) {
            bundle = cursor.getString(0)
        }
        cursor.close()
        return bundle
    }

    fun listAnimations(cardId: String): List<String> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT anim_name FROM animations WHERE card_id = ?", arrayOf(cardId))
        val list = mutableListOf<String>()
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        return list
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM cards")
        writableDatabase.execSQL("DELETE FROM animations")
    }

    fun markCardPhysicallyRedeemed(cardId: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("physical_redeemed", 1)
        }
        val rows = db.update("cards", values, "id = ?", arrayOf(cardId))
        return rows > 0
    }
}