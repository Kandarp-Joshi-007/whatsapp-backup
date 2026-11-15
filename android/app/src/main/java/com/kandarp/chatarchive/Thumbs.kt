package com.kandarp.chatarchive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/** Small JPEG previews (max 240px) shown in chats and the media grid. */
object Thumbs {
    private const val PX = 240

    fun interface Maker { fun make(file: File?, isVideo: Boolean, dbThumb: ByteArray?): ByteArray? }

    val android = Maker { file, isVideo, dbThumb ->
        val bmp = file?.takeIf { it.isFile }?.let { if (isVideo) fromVideo(it) else fromImage(it) }
            ?: dbThumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        bmp?.let { jpeg(it) }
    }

    private fun fromImage(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= PX && o.outHeight / (sample * 2) >= PX) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val rot = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (bmp != null && rot != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true) else bmp
    } catch (_: Throwable) { null }

    private fun fromVideo(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PX * 2, PX * 2)
                ?: r.getFrameAtTime(0)
        } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
    }

    private fun jpeg(src: Bitmap): ByteArray {
        val scale = minOf(1f, PX.toFloat() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt()); val h = maxOf(1, (src.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, null) }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 62, it) }.toByteArray()
    }
}
