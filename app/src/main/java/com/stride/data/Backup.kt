package com.stride.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * With no account there's no cloud copy, so this is how you keep your history safe or move it to
 * a new phone: one .zip with everything (activities, tracks, routes, segments, goals, settings).
 */
object Backup {
    fun write(root: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile && !it.name.startsWith("current.") && !it.name.endsWith(".tmp") }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Replaces all data with the backup. Extracts to a temp folder first and only swaps it in if it
     * looks like a Stride backup, so a wrong file can't wipe your data.
     */
    fun restore(context: Context, uri: Uri): Result<Int> = runCatching {
        val root = Repo.store.root
        val staging = File(root.parentFile, "restore-staging").apply { deleteRecursively(); mkdirs() }
        val count = context.contentResolver.openInputStream(uri)?.use { unzip(it, staging) } ?: error("Couldn't open the file")
        require(File(staging, "activities.json").exists() || File(staging, "routes.json").exists()) {
            "That doesn't look like a Stride backup."
        }
        val old = File(root.parentFile, "stride-old").apply { deleteRecursively() }
        check(root.renameTo(old)) { "Couldn't replace data" }
        if (!staging.renameTo(root)) { old.renameTo(root); error("Couldn't replace data") }
        File(root, "tracks").mkdirs()
        old.deleteRecursively()
        count
    }

    private fun unzip(input: InputStream, dest: File): Int {
        var n = 0
        val canonical = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                // Reject "../" paths (zip-slip) and anything that isn't one of our files.
                require(out.canonicalPath.startsWith(canonical)) { "Bad entry in backup" }
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
                n++
            }
        }
        return n
    }
}
