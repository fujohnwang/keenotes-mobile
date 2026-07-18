@file:OptIn(UnstableApi::class)

package cn.keevol.keenotes.share

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import cn.keevol.keenotes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

object PosterVideoExporter {
    private const val FRAME_RATE = 30
    private val bgmTracks = intArrayOf(
        R.raw.loop_lepr,
        R.raw.win_battle,
        R.raw.defend_castle
    )

    suspend fun exportToCache(context: Context, videoFrame: Bitmap, displayNameBase: String): File {
        val appContext = context.applicationContext
        val work = withContext(Dispatchers.IO) {
            val workDir = File(appContext.cacheDir, "poster-video").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val frameFile = File(workDir, "poster-frame-$id.png")
            val audioFile = File(workDir, "poster-bgm-$id.mp3")
            val outputFile = File(workDir, sanitizeBaseName(displayNameBase) + "-$id.mp4")

            frameFile.outputStream().use { output ->
                if (!videoFrame.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Failed to encode video frame")
                }
            }
            copyRandomBgm(appContext, audioFile)
            val durationMs = readAudioDurationMs(audioFile).coerceAtLeast(1_000L)
            WorkFiles(frameFile, audioFile, outputFile, durationMs)
        }

        try {
            runTransformer(appContext, work)
            return work.outputFile
        } finally {
            work.frameFile.delete()
            work.audioFile.delete()
        }
    }

    private suspend fun runTransformer(context: Context, work: WorkFiles): File {
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val imageMediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(work.frameFile))
                    .setImageDurationMs(work.durationMs)
                    .build()
                val editedImageItem = EditedMediaItem.Builder(imageMediaItem)
                    .setFrameRate(FRAME_RATE)
                    .build()
                val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(editedImageItem))

                val audioMediaItem = MediaItem.fromUri(Uri.fromFile(work.audioFile))
                val editedAudioItem = EditedMediaItem.Builder(audioMediaItem).build()
                val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(editedAudioItem))
                val composition = Composition.Builder(videoSequence, audioSequence).build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) {
                                continuation.resume(work.outputFile)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    transformer.cancel()
                }
                transformer.start(composition, work.outputFile.absolutePath)
            }
        }
    }

    private fun copyRandomBgm(context: Context, target: File) {
        val track = bgmTracks[Random.nextInt(bgmTracks.size)]
        context.resources.openRawResource(track).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun readAudioDurationMs(audioFile: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 8_000L
        } finally {
            retriever.release()
        }
    }

    private fun sanitizeBaseName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "keenotes-poster" }
    }

    private data class WorkFiles(
        val frameFile: File,
        val audioFile: File,
        val outputFile: File,
        val durationMs: Long
    )
}
