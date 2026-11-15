package com.kandarp.chatarchive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The nightly job: decrypt WhatsApp's newest local backup, rebuild the chat index, upload every
 * new photo/video/document to Drive, then upload the index. Nothing is ever deleted from Drive.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIF_ID = 7

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only: 12 GB on mobile data would hurt
            .setRequiresBatteryNotLow(true).build()

        fun scheduleNightly(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(3, 30))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(24))
                .setInitialDelay(Duration.between(now, next)).setConstraints(constraints()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nightly", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("now", ExistingWorkPolicy.KEEP, req)
            Status.set(ctx, "waiting", "Waiting for Wi-Fi and enough battery…")
        }

        fun mime(f: File): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                ?: when (f.extension.lowercase()) { "opus" -> "audio/ogg"; "crypt15", "crypt14" -> "application/octet-stream"; else -> "application/octet-stream" }
    }

    private val store = Store.get(ctx)
    private lateinit var drive: Drive

    private fun notification(text: String, done: Int = 0, total: Int = 0): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Chat Archive")
            .setContentText(text).setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) }.build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private suspend fun step(phase: String, detail: String, done: Int = 0, total: Int = 0) {
        Status.set(applicationContext, phase, detail, done, total)
        try { setForeground(notification(detail, done, total)) } catch (_: Exception) { }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dbFile = File(ctx.cacheDir, "msgstore.db")
        return try {
            step("starting", "Starting backup…")
            if (!Environment.isExternalStorageManager()) throw IllegalStateException("Allow “All files access” in Setup.")
            val key = KeyVault.load(ctx) ?: throw IllegalStateException("Enter your 64-digit key in Setup.")
            drive = Drive(ctx)
            drive.refreshToken()
            if (!drive.folderStillExists()) { // you deleted the folder in Drive: start fresh
                drive.resetFolderCache()
                store.writableDatabase.delete("uploads", null, null)
            }

            val wa = Paths.whatsAppRoot()
            val backup = File(wa, "Databases").listFiles { f -> f.name.startsWith("msgstore") && f.name.endsWith(".crypt15") }
                ?.maxByOrNull { it.lastModified() }
            if (backup == null) {
                restoreFromDrive()
                return Result.success()
            }

            // 1. decrypt + index (works offline from the files on the phone)
            val free = ctx.cacheDir.usableSpace
            if (free < backup.length() * 4) throw IllegalStateException(
                "Not enough free space to unpack the backup: need ~${backup.length() * 4 / 1_048_576} MB, have ${free / 1_048_576} MB.")
            step("decrypting", "Unlocking backup…")
            Crypt15.decryptToFile({ backup.inputStream() }, key, dbFile)
            step("reading", "Reading chats…")
            val chats = MsgStore.read(dbFile)
            dbFile.delete()
            val names = Contacts.load(ctx)
            val media = File(wa, "Media").takeIf { it.isDirectory }
            buildIndex(chats, wa, names, "Indexing chats")

            // 2. upload new media (newest first, so recent photos are safe soonest)
            val files = media?.walkTopDown()?.onEnter { !it.name.startsWith(".") }
                ?.filter { it.isFile && !it.name.startsWith(".") }?.toMutableList() ?: mutableListOf()
            files.sortByDescending { it.lastModified() }
            val todo = files.filter { f -> store.uploaded(rel(wa, f))?.first != f.length() }
            var failed = 0
            todo.forEachIndexed { i, f ->
                if (isStopped) return Result.retry()
                if (i % 5 == 0) step("uploading", "Uploading ${i + 1} of ${todo.size}", i, todo.size)
                try {
                    val path = rel(wa, f)
                    val id = drive.upload(f, f.name, drive.folder(path.substringBeforeLast('/')), mime(f))
                    store.markUploaded(path, f.length(), id)
                } catch (e: Drive.NeedSignIn) { throw e } catch (e: Exception) { failed++ }
            }
            // the encrypted backup itself (lets a new phone rebuild everything)
            val bkKey = "Databases/msgstore.db.crypt15"
            if (store.uploaded(bkKey)?.first != backup.length() || Status.last(ctx, "bk_mtime") != backup.lastModified().toString()) {
                step("uploading", "Uploading encrypted chat backup…")
                val existing = store.uploaded(bkKey)?.second
                val id = if (existing != null) drive.update(existing, backup, "application/octet-stream")
                         else drive.upload(backup, "msgstore.db.crypt15", drive.folder("Databases"), "application/octet-stream")
                store.markUploaded(bkKey, backup.length(), id)
                Status.setLast(ctx, "bk_mtime", backup.lastModified().toString())
            }

            // 3. index again with Drive ids, then upload it
            buildIndex(chats, wa, names, "Finishing index")
            uploadIndex()

            val total = store.uploadCount()
            Status.setLast(ctx, "last_ok", System.currentTimeMillis().toString())
            Status.set(ctx, "done", if (failed > 0) "Backed up $total files. $failed failed, will retry tonight." else "All $total files are safe in Google Drive.")
            Result.success()
        } catch (e: Drive.NeedSignIn) {
            Status.set(ctx, "error", e.message ?: "Sign in again"); Result.failure()
        } catch (e: Crypt15.DecryptException) {
            Status.set(ctx, "error", e.message ?: "Could not decrypt"); Result.failure()
        } catch (e: MsgStore.SchemaException) {
            Status.set(ctx, "error", e.message ?: "Unsupported backup"); Result.failure()
        } catch (e: IllegalStateException) {
            Status.set(ctx, "error", e.message ?: "Setup incomplete"); Result.failure()
        } catch (e: Exception) {
            Status.set(ctx, "error", "${e.javaClass.simpleName}: ${e.message}. Will retry."); Result.retry()
        } finally {
            dbFile.delete()
        }
    }

    private fun rel(root: File, f: File) = f.relativeTo(root).path.replace('\\', '/')

    private suspend fun buildIndex(chats: List<MsgStore.Chat>, wa: File, names: Map<String, String>, label: String) {
        val cache = object : IndexBuilder.ThumbCache {
            override fun get(k: String) = store.thumb(k)
            override fun put(k: String, v: ByteArray) = store.putThumb(k, v)
        }
        val tmp = File(applicationContext.filesDir, "index.tmp").apply { deleteRecursively(); mkdirs() }
        var last = 0L
        IndexBuilder(wa, names, store.allUploads(), Thumbs.android, cache).build(chats, tmp) { d, t ->
            if (System.currentTimeMillis() - last > 1000) {
                last = System.currentTimeMillis(); Status.set(applicationContext, "indexing", "$label: $d of $t chats", d, t)
            }
        }
        val dest = Paths.indexDir(applicationContext)
        dest.deleteRecursively(); tmp.renameTo(dest)
    }

    private fun md5(f: File) = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private suspend fun uploadIndex() {
        val dir = Paths.indexDir(applicationContext)
        // path -> Drive id map, so a new phone can open files without searching
        val map = JSONObject()
        store.readableDatabase.rawQuery("SELECT path, drive_id, size FROM uploads", null).use { c ->
            while (c.moveToNext()) map.put(c.getString(0), org.json.JSONArray().put(c.getString(1)).put(c.getLong(2)))
        }
        File(dir, "files.json").writeText(map.toString())
        val folder = drive.folder("index")
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.forEachIndexed { i, f ->
            step("uploading", "Saving index ${i + 1} of ${files.size}", i, files.size)
            val hash = md5(f)
            if (store.get("idxhash:${f.name}") == hash) return@forEachIndexed
            val id = store.get("idxid:${f.name}")
            val newId = try { if (id != null) drive.update(id, f, "application/json") else null }
                        catch (e: Drive.HttpError) { if (e.code == 404) null else throw e }
                ?: drive.upload(f, f.name, folder, "application/json")
            store.put("idxid:${f.name}", newId); store.put("idxhash:${f.name}", hash)
        }
    }

    /** New phone (or WhatsApp not restored yet): pull the archive index down from Drive. */
    private suspend fun restoreFromDrive() {
        step("restoring", "Downloading your archive from Google Drive…")
        val folder = drive.folder("index")
        val remote = drive.list("'$folder' in parents and trashed=false")
        if (remote.none { it.getString("name") == "manifest.json" }) {
            Status.set(applicationContext, "error",
                "No WhatsApp backup found on this phone and no archive in Google Drive yet. " +
                    "In WhatsApp: Settings → Chats → Chat backup → Back up, then tap Back up now here.")
            return
        }
        val dir = Paths.indexDir(applicationContext)
        remote.forEachIndexed { i, f ->
            if (i % 10 == 0) step("restoring", "Downloading index ${i + 1} of ${remote.size}", i, remote.size)
            drive.download(f.getString("id"), File(dir, f.getString("name")))
            store.put("idxid:${f.getString("name")}", f.getString("id"))
        }
        File(dir, "files.json").takeIf { it.isFile }?.let { fj ->
            val m = JSONObject(fj.readText())
            for (k in m.keys()) { val v = m.getJSONArray(k); store.markUploaded(k, v.getLong(1), v.getString(0)) }
        }
        Status.setLast(applicationContext, "last_ok", System.currentTimeMillis().toString())
        Status.set(applicationContext, "done", "Archive restored from Google Drive.")
    }
}
