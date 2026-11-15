package com.kandarp.chatarchive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Google Drive v3 client. Uses the drive.file scope: the app can only see files it
 * created itself, which needs no Google verification and can't touch the rest of your Drive.
 */
class Drive(private val ctx: Context) {
    class NeedSignIn : Exception("Sign in to Google Drive again (open the app → Setup).")
    class HttpError(val code: Int, msg: String) : IOException("Google Drive error $code: $msg")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val ROOT = "WhatsAppArchive"
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER = "application/vnd.google-apps.folder"

        fun request(): AuthorizationRequest =
            AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    }

    private var token: String? = null
    private var tokenAt = 0L
    private val store = Store.get(ctx)

    suspend fun refreshToken(): String {
        val r: AuthorizationResult = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        if (r.hasResolution() || r.accessToken == null) throw NeedSignIn()
        token = r.accessToken; tokenAt = System.currentTimeMillis()
        return token!!
    }

    private suspend fun tok(): String =
        if (token == null || System.currentTimeMillis() - tokenAt > 40 * 60_000) refreshToken() else token!!

    /** Runs an HTTP call, refreshing the token once on 401. */
    private suspend fun <T> call(url: String, method: String = "GET", body: ((HttpURLConnection) -> Unit)? = null,
                                 headers: Map<String, String> = emptyMap(), read: (HttpURLConnection) -> T): T {
        repeat(2) { attempt ->
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (method == "PATCH") "POST" else method
                if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connectTimeout = 30_000; readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer ${tok()}")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                body?.invoke(c)
                val code = c.responseCode
                if (code == 401 && attempt == 0) { token = null; return@repeat }
                if (code !in 200..299) {
                    val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                    throw HttpError(code, err)
                }
                return read(c)
            } finally { c.disconnect() }
        }
        throw NeedSignIn()
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun json(c: HttpURLConnection) = JSONObject(c.inputStream.bufferedReader().readText())

    suspend fun list(query: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page: String? = null
        do {
            val url = "$API?q=${q(query)}&pageSize=1000&fields=${q("nextPageToken,files(id,name,size,mimeType)")}" +
                (page?.let { "&pageToken=${q(it)}" } ?: "")
            val j = call(url) { json(it) }
            val files = j.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) out += files!!.getJSONObject(i)
            page = j.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    private suspend fun findOrCreateFolder(name: String, parent: String): String {
        list("name='${esc(name)}' and '$parent' in parents and mimeType='$FOLDER' and trashed=false")
            .firstOrNull()?.let { return it.getString("id") }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", org.json.JSONArray().put(parent))
        return call("$API?fields=id", "POST", { c ->
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(meta.toString().toByteArray()) }
        }) { json(it).getString("id") }
    }

    /** Folder id for "WhatsAppArchive/a/b", created if needed, cached locally. */
    suspend fun folder(relPath: String): String {
        val full = if (relPath.isEmpty()) ROOT else "$ROOT/$relPath"
        store.get("folder:$full")?.let { return it }
        val parentPath = full.substringBeforeLast('/', "")
        val parent = if (parentPath.isEmpty()) "root" else folder(parentPath.removePrefix(ROOT).trimStart('/'))
        val id = findOrCreateFolder(full.substringAfterLast('/'), parent)
        store.put("folder:$full", id)
        return id
    }

    /** Forget cached folder ids (e.g. you deleted the folder in Drive). */
    fun resetFolderCache() {
        store.writableDatabase.delete("kv", "k LIKE 'folder:%'", null)
    }

    suspend fun folderStillExists(): Boolean {
        val id = store.get("folder:$ROOT") ?: return true
        return try { call("$API/$id?fields=trashed") { !json(it).optBoolean("trashed") } }
        catch (e: HttpError) { if (e.code == 404) false else throw e }
    }

    /** Resumable upload (works for big videos). Returns the new file id. */
    suspend fun upload(file: File, name: String, parentId: String, mime: String): String {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId))
        val session = call("$UPLOAD?uploadType=resumable&fields=id", "POST", { c ->
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Type", mime)
            c.setRequestProperty("X-Upload-Content-Length", file.length().toString())
            c.outputStream.use { it.write(meta.toString().toByteArray()) }
        }) { it.getHeaderField("Location") ?: throw IOException("No upload URL from Drive") }
        return putBytes(session, file, mime, "PUT")
    }

    /** Replaces the contents of an existing file. */
    suspend fun update(id: String, file: File, mime: String): String =
        putBytes("$UPLOAD/$id?uploadType=media&fields=id", file, mime, "PATCH")

    private suspend fun putBytes(url: String, file: File, mime: String, method: String): String =
        call(url, method, { c ->
            c.doOutput = true
            c.setFixedLengthStreamingMode(file.length())
            c.setRequestProperty("Content-Type", mime)
            file.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out, 1 shl 16) } }
        }) { json(it).getString("id") }

    suspend fun download(id: String, dest: File) {
        val tmp = File(dest.path + ".part")
        call("$API/$id?alt=media") { c -> c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } }
        tmp.renameTo(dest)
    }

    suspend fun downloadText(id: String): String = call("$API/$id?alt=media") { it.inputStream.bufferedReader().readText() }
}
