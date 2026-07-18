package cn.keevol.keenotes.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object PosterMediaStore {
    fun savePoster(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, ensureExtension(displayName, ".png"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KeeNotes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create image in MediaStore")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Failed to encode PNG")
                }
            } ?: throw IOException("Failed to open image output stream")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun saveVideo(context: Context, sourceFile: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ensureExtension(displayName, ".mp4"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KeeNotes")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create video in MediaStore")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open video output stream")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun cachePosterForShare(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val outputFile = File(shareDir, ensureExtension(displayName, ".png"))
        outputFile.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Failed to encode PNG")
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )
    }

    private fun ensureExtension(displayName: String, extension: String): String {
        return if (displayName.endsWith(extension, ignoreCase = true)) {
            displayName
        } else {
            "$displayName$extension"
        }
    }
}
