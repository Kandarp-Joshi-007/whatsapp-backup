package com.kandarp.chatarchive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-local bookkeeping: which files are already in Drive, thumbnail cache, small key/values. */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "app.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE uploads (path TEXT PRIMARY KEY, size INTEGER, drive_id TEXT)")
        db.execSQL("CREATE TABLE thumbs (k TEXT PRIMARY KEY, v BLOB)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun uploaded(path: String): Pair<Long, String>? =
        readableDatabase.rawQuery("SELECT size, drive_id FROM uploads WHERE path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        }

    fun allUploads(): Map<String, String> =
        readableDatabase.rawQuery("SELECT path, drive_id FROM uploads", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun uploadCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM uploads", null).use { it.moveToFirst(); it.getInt(0) }

    fun markUploaded(path: String, size: Long, id: String) {
        writableDatabase.insertWithOnConflict("uploads", null, ContentValues().apply {
            put("path", path); put("size", size); put("drive_id", id)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun thumb(k: String): ByteArray? =
        readableDatabase.rawQuery("SELECT v FROM thumbs WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun putThumb(k: String, v: ByteArray) {
        writableDatabase.insertWithOnConflict("thumbs", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(k: String, v: String?) {
        if (v == null) writableDatabase.delete("kv", "k=?", arrayOf(k))
        else writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}

object Paths {
    /** WhatsApp's folder on Android 11+. Older layout kept as fallback. */
    fun whatsAppRoot(): File {
        val ext = Environment.getExternalStorageDirectory()
        val modern = File(ext, "Android/media/com.whatsapp/WhatsApp")
        return if (modern.isDirectory) modern else File(ext, "WhatsApp").takeIf { it.isDirectory } ?: modern
    }
    fun indexDir(ctx: Context) = File(ctx.filesDir, "index").apply { mkdirs() }
}

/** Keeps the 64-digit key encrypted with a key that never leaves the phone's secure hardware. */
object KeyVault {
    private const val ALIAS = "wa-backup-key"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(ctx: Context, keyText: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val enc = c.doFinal(keyText.toByteArray())
        prefs(ctx).edit().putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("key", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun load(ctx: Context): String? {
        val p = prefs(ctx)
        val iv = p.getString("iv", null) ?: return null
        val enc = p.getString("key", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
        } catch (e: Exception) { null }
    }

    fun has(ctx: Context) = prefs(ctx).contains("key")
}

/** Progress/status shared between the background sync and the screens. */
object Status {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)
    fun set(ctx: Context, phase: String, detail: String = "", done: Int = 0, total: Int = 0) {
        prefs(ctx).edit().putString("s", JSONObject().apply {
            put("phase", phase); put("detail", detail); put("done", done); put("total", total); put("at", System.currentTimeMillis())
        }.toString()).apply()
    }
    fun get(ctx: Context): JSONObject = JSONObject(prefs(ctx).getString("s", null) ?: "{\"phase\":\"never\"}")
    fun setLast(ctx: Context, key: String, value: String) = prefs(ctx).edit().putString(key, value).apply()
    fun last(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)
}
