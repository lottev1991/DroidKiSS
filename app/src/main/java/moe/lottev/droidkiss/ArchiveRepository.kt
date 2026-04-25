package moe.lottev.droidkiss

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/** The archive repository. */
class ArchiveRepository(private val context: Context) {

    /** Loads LZH file. */
    @SuppressLint("Recycle")
    fun loadLzh(uri: Uri): LhaFile? {
        val cr = context.contentResolver
        val inputStream = try {
            cr.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (inputStream == null) {
            return null
        }

        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        if (bytes.isEmpty()) {
            return null
        }

        return try {
            val fileName = uri.lastPathSegment ?: "doll.lzh"
            val memFile = MemFile(bytes)
            return LhaFile(fileName, memFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SuppressLint("Recycle")
    internal fun loadZip(uri: Uri): PkzFile? {
        val cr = context.contentResolver
        val inputStream = try {
            cr.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (inputStream == null) {
            return null
        }

        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        if (bytes.isEmpty()) {
            return null
        }

        return try {
            val fileName = uri.lastPathSegment ?: "doll.zip"
            val memFile = MemFile(bytes)
            return PkzFile(fileName, memFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    }

    /** Extracts LZH files. */
    fun extractLhaEntry(archive: LhaFile, entry: ArchiveEntry?): ByteArray? {
        return try {
            archive.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                if (input == null) return null
                input.copyTo(output)
                output.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } as ByteArray?
    }

    /** Extracts ZIP files. */
    internal fun extractZipEntry(archive: PkzFile, entry: ArchiveEntry?): ByteArray? {
        return try {
            archive.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                if (input == null) return null
                input.copyTo(output)
                output.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } as ByteArray?
    }
}