package com.kandarp.chatarchive

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Reads a decrypted msgstore.db (WhatsApp's 2022+ schema). Port of indexer/wa_archive/msgstore.py. */
object MsgStore {
    class SchemaException(msg: String) : Exception(msg)

    class Media(
        val path: String?, val mime: String? = null, val size: Long? = null, val name: String? = null,
        val duration: Int? = null, val width: Int? = null, val height: Int? = null,
    ) { var dbThumb: ByteArray? = null }

    class Message(
        val id: Long, val ts: Long, val fromMe: Boolean, val kind: String,
        val text: String?, val sender: String?, val media: Media?,
    )

    class Chat(val jid: String, val user: String, val isGroup: Boolean, val subject: String?) {
        val messages = ArrayList<Message>()
    }

    private val KIND = mapOf(
        0 to "text", 1 to "image", 2 to "audio", 3 to "video", 4 to "contact", 5 to "location",
        7 to "system", 9 to "document", 13 to "gif", 15 to "deleted", 16 to "location",
        20 to "sticker", 42 to "image", 43 to "video", 64 to "deleted", 66 to "poll", 82 to "audio",
    )
    val MEDIA_KINDS = setOf("image", "video", "audio", "document", "gif", "sticker")

    fun normalizeMediaPath(p: String?): String? {
        if (p.isNullOrBlank()) return null
        val s = p.replace('\\', '/')
        val i = s.indexOf("Media/")
        return if (i >= 0) s.substring(i) else s.trimStart('/')
    }

    fun read(dbFile: File): List<Chat> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            if ("message" !in tables || "chat" !in tables) {
                throw SchemaException(
                    if ("messages" in tables) "This backup uses WhatsApp's old (pre-2022) format. Update WhatsApp and back up again."
                    else "This is not a WhatsApp chat database."
                )
            }
            fun cols(t: String) = db.rawQuery("PRAGMA table_info($t)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            fun pick(cols: Set<String>, vararg names: String) = names.firstOrNull { it in cols } ?: "NULL"

            data class Jid(val raw: String, val user: String, val server: String)
            val jc = cols("jid")
            val jids = HashMap<Long, Jid>()
            db.rawQuery("SELECT _id, ${pick(jc, "raw_string")}, ${pick(jc, "user")}, ${pick(jc, "server")} FROM jid", null).use { c ->
                while (c.moveToNext()) {
                    val user = c.getString(2); val server = c.getString(3)
                    val raw = c.getString(1) ?: if (user != null) "$user@$server" else ""
                    jids[c.getLong(0)] = Jid(raw, user ?: raw.substringBefore('@'), server ?: raw.substringAfter('@', ""))
                }
            }
            if ("jid_map" in tables) { // privacy "LID" ids -> real phone jid
                db.rawQuery("SELECT lid_row_id, jid_row_id FROM jid_map", null).use { c ->
                    while (c.moveToNext()) {
                        val real = jids[c.getLong(1)]
                        if (real != null && jids.containsKey(c.getLong(0))) jids[c.getLong(0)] = real
                    }
                }
            }

            val cc = cols("chat")
            val chats = HashMap<Long, Chat>()
            db.rawQuery("SELECT _id, jid_row_id, ${pick(cc, "subject")} FROM chat", null).use { c ->
                while (c.moveToNext()) {
                    val j = jids[c.getLong(1)] ?: continue
                    if (j.server == "broadcast" || j.server == "status" || j.raw.startsWith("status@")) continue
                    chats[c.getLong(0)] = Chat(j.raw, j.user, j.server == "g.us", c.getString(2))
                }
            }

            val media = HashMap<Long, Media>()
            if ("message_media" in tables) {
                val mc = cols("message_media")
                val q = "SELECT message_row_id, ${pick(mc, "file_path")}, ${pick(mc, "mime_type")}, " +
                    "${pick(mc, "file_size", "file_length")}, ${pick(mc, "media_name")}, ${pick(mc, "media_duration")}, " +
                    "${pick(mc, "width")}, ${pick(mc, "height")} FROM message_media"
                db.rawQuery(q, null).use { c ->
                    fun int(i: Int) = if (c.isNull(i)) null else c.getLong(i).takeIf { it > 0 }
                    while (c.moveToNext()) media[c.getLong(0)] = Media(
                        normalizeMediaPath(c.getString(1)), c.getString(2), int(3), c.getString(4),
                        int(5)?.toInt(), int(6)?.toInt(), int(7)?.toInt(),
                    )
                }
            }
            if ("message_thumbnail" in tables) {
                db.rawQuery("SELECT message_row_id, thumbnail FROM message_thumbnail", null).use { c ->
                    while (c.moveToNext()) {
                        if (c.isNull(1)) continue
                        val blob = c.getBlob(1)
                        media.getOrPut(c.getLong(0)) { Media(null) }.dbThumb = blob
                    }
                }
            }

            val mc = cols("message")
            val q = "SELECT _id, chat_row_id, ${pick(mc, "from_me")}, ${pick(mc, "timestamp")}, " +
                "${pick(mc, "message_type")}, ${pick(mc, "text_data")}, ${pick(mc, "sender_jid_row_id")} " +
                "FROM message ORDER BY chat_row_id, timestamp, _id"
            db.rawQuery(q, null).use { c ->
                while (c.moveToNext()) {
                    val chat = chats[c.getLong(1)] ?: continue
                    val ts = if (c.isNull(3)) 0L else c.getLong(3)
                    if (ts <= 0) continue
                    val kind = KIND[if (c.isNull(4)) 0 else c.getInt(4)] ?: "other"
                    if (kind == "system") continue
                    val text = c.getString(5)
                    if (kind == "text" && text.isNullOrEmpty()) continue
                    val id = c.getLong(0)
                    val m = if (kind in MEDIA_KINDS) media[id] ?: Media(null) else null
                    val fromMe = !c.isNull(2) && c.getInt(2) == 1
                    val sender = if (chat.isGroup && !fromMe && !c.isNull(6)) jids[c.getLong(6)]?.user else null
                    chat.messages += Message(id, ts, fromMe, kind, text, sender, m)
                }
            }
            return chats.values.filter { it.messages.isNotEmpty() }
        } finally {
            db.close()
        }
    }
}
