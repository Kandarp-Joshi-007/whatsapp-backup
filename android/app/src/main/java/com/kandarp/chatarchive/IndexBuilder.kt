package com.kandarp.chatarchive

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes the archive index the viewer reads (same format as indexer/wa_archive/build.py):
 *   manifest.json, chat-<id>-<YYYY-MM>.json
 */
class IndexBuilder(
    private val mediaRoot: File?,
    private val names: Map<String, String>,
    private val driveIds: Map<String, String>,
    private val thumbs: Thumbs.Maker,
    private val cache: ThumbCache?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    interface ThumbCache { fun get(k: String): ByteArray?; fun put(k: String, v: ByteArray) }

    private val urlRe = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE)
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")

    companion object {
        fun chatId(jid: String): String =
            MessageDigest.getInstance("SHA-1").digest(jid.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        private val VIDEO_EXT = setOf("mp4", "3gp", "mov", "mkv", "webm")
    }

    private fun preview(m: MsgStore.Message): String {
        val label = mapOf("image" to "📷 Photo", "video" to "🎥 Video", "audio" to "🎤 Audio", "document" to "📄 Document",
            "sticker" to "Sticker", "gif" to "GIF", "contact" to "👤 Contact", "location" to "📍 Location",
            "deleted" to "🚫 This message was deleted", "poll" to "📊 Poll")[m.kind]
        if (m.kind == "document" && m.media?.name != null) return "📄 " + m.media.name
        if (!m.text.isNullOrEmpty()) return (if (label != null && m.kind != "text") "$label " else "") + m.text.take(80)
        return label ?: ""
    }

    private fun thumbFor(m: MsgStore.Media, kind: String): Pair<ByteArray?, Boolean> {
        val f = if (mediaRoot != null && m.path != null) File(mediaRoot, m.path) else null
        val exists = f?.isFile == true
        if (exists && kind in setOf("image", "sticker", "gif", "video")) {
            val key = "${m.path}|${f!!.length()}"
            val hit = cache?.get(key)
            if (hit != null) return (hit.takeIf { it.isNotEmpty() }) to true
            val isVideo = m.mime?.startsWith("video/") == true || f.extension.lowercase() in VIDEO_EXT
            val t = thumbs.make(f, isVideo, null)
            cache?.put(key, t ?: ByteArray(0))
            if (t != null) return t to true
        }
        return (m.dbThumb?.let { thumbs.make(null, false, it) }) to exists
    }

    fun build(chats: List<MsgStore.Chat>, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): JSONObject {
        out.mkdirs()
        val written = hashSetOf("manifest.json")
        val manifestChats = JSONArray()
        val sorted = chats.sortedByDescending { it.messages.last().ts }
        sorted.forEachIndexed { ci, chat ->
            val cid = chatId(chat.jid)
            val display = (if (chat.isGroup) chat.subject else names[chat.user])
                ?: if (chat.user.all { it.isDigit() }) "+${chat.user}" else chat.user
            val months = sortedMapOf<String, JSONArray>()
            val counts = HashMap<String, Int>()
            val mediaMonths = HashMap<String, Int>()
            for (msg in chat.messages) {
                val rec = JSONObject().put("i", msg.id).put("t", msg.ts).put("k", msg.kind)
                if (msg.fromMe) rec.put("f", 1)
                if (!msg.text.isNullOrEmpty()) {
                    rec.put("x", msg.text)
                    if (urlRe.containsMatchIn(msg.text)) { rec.put("l", 1); counts.merge("links", 1, Int::plus) }
                }
                msg.sender?.let { s -> rec.put("s", names[s] ?: if (s.all { it.isDigit() }) "+$s" else s) }
                val month = Instant.ofEpochMilli(msg.ts).atZone(zone).format(monthFmt)
                msg.media?.let { m ->
                    val (thumb, exists) = thumbFor(m, msg.kind)
                    val md = JSONObject()
                    m.path?.let { p -> md.put("p", p); driveIds[p]?.let { md.put("d", it) } }
                    m.mime?.let { md.put("mt", it) }; m.size?.let { md.put("z", it) }; m.name?.let { md.put("n", it) }
                    m.duration?.let { md.put("du", it) }; m.width?.let { md.put("w", it) }; m.height?.let { md.put("h", it) }
                    thumb?.let { md.put("th", Base64.encodeToString(it, Base64.NO_WRAP)) }
                    if (!exists && !md.has("d")) md.put("miss", 1)
                    rec.put("m", md)
                    counts.merge(msg.kind, 1, Int::plus)
                    mediaMonths.merge(month, 1, Int::plus)
                }
                months.getOrPut(month) { JSONArray() }.put(rec)
            }
            for ((mon, recs) in months) {
                val fn = "chat-$cid-$mon.json"
                File(out, fn).writeText(JSONObject().put("v", 1).put("chat", cid).put("month", mon).put("messages", recs).toString())
                written += fn
            }
            val last = chat.messages.last()
            manifestChats.put(JSONObject()
                .put("id", cid).put("name", display).put("jid", chat.jid).put("g", if (chat.isGroup) 1 else 0)
                .put("phone", if (chat.isGroup) JSONObject.NULL else chat.user)
                .put("last_t", last.ts).put("last_x", preview(last)).put("last_f", if (last.fromMe) 1 else 0)
                .put("n", chat.messages.size).put("counts", JSONObject(counts as Map<*, *>))
                .put("months", JSONArray(months.keys.toList())).put("media_months", JSONObject(mediaMonths as Map<*, *>)))
            onProgress(ci + 1, sorted.size)
        }
        val manifest = JSONObject().put("v", 1).put("generated_at", System.currentTimeMillis())
            .put("tz", zone.id).put("chats", manifestChats)
        File(out, "manifest.json").writeText(manifest.toString())
        out.listFiles()?.filter { it.name.startsWith("chat-") && it.name !in written }?.forEach { it.delete() }
        return manifest
    }
}
