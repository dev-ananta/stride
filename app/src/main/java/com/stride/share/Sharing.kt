package com.stride.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Writes export files and hands them to other apps (Instagram, Strava, Garmin, Komoot, Files, email…). */
object Sharing {
    const val INSTAGRAM = "com.instagram.android"

    fun dir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }

    /** Safe file name from an activity title. */
    fun fileName(title: String, ext: String): String {
        val base = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "_").take(60).ifEmpty { "Stride" }
        return "$base.$ext"
    }

    fun writeText(ctx: Context, name: String, text: String): File = File(dir(ctx), name).apply { writeText(text) }

    fun writePng(ctx: Context, name: String, bmp: Bitmap): File =
        File(dir(ctx), name).apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** System share sheet. */
    fun share(ctx: Context, f: File, mime: String, title: String) {
        val u = uri(ctx, f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
            .putExtra(Intent.EXTRA_TITLE, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isInstagramInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(INSTAGRAM, 0); true
    } catch (_: Exception) { false }

    /**
     * Opens Instagram with the image/video; Instagram then asks Story, Reel, Post or Message.
     * (Instagram's direct "add to Story" hook needs a registered Facebook developer app ID, which
     * this account-free app deliberately doesn't have, so this uses the standard share route.)
     */
    fun toInstagram(ctx: Context, f: File, mime: String): Boolean {
        val u = uri(ctx, f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
            .setPackage(INSTAGRAM).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: Exception) { false }
    }

    /** Copies a file into the phone's gallery (Pictures/Stride or Movies/Stride). */
    fun saveToGallery(ctx: Context, f: File, mime: String): Uri? {
        val isVideo = mime.startsWith("video/")
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Stride")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = ctx.contentResolver
            val item = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(item)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (e: Exception) {
                resolver.delete(item, null, null); throw e
            }
            return item
        } else {
            @Suppress("DEPRECATION")
            val dest = File(Environment.getExternalStoragePublicDirectory(folder), "Stride").apply { mkdirs() }
            val target = File(dest, f.name)
            f.copyTo(target, overwrite = true)
            @Suppress("DEPRECATION")
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)))
            return Uri.fromFile(target)
        }
    }
}
