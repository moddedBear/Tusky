/* Copyright 2019 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.compose

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.keylesspalace.tusky.BuildConfig
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.components.compose.ComposeActivity.QueuedMedia
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.network.ProgressRequestBody
import com.keylesspalace.tusky.util.MEDIA_SIZE_UNKNOWN
import com.keylesspalace.tusky.util.getImageSquarePixels
import com.keylesspalace.tusky.util.getMediaSize
import com.keylesspalace.tusky.util.randomAlphanumericString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import javax.inject.Inject

sealed class UploadEvent {
    data class ProgressEvent(val percentage: Int) : UploadEvent()
    data class FinishedEvent(val mediaId: String) : UploadEvent()
}

fun createNewImageFile(context: Context): File {
    // Create an image file name
    val randomId = randomAlphanumericString(12)
    val imageFileName = "Tusky_${randomId}_"
    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    return File.createTempFile(
        imageFileName, /* prefix */
        ".jpg", /* suffix */
        storageDir /* directory */
    )
}

data class PreparedMedia(val type: QueuedMedia.Type, val uri: Uri, val size: Long)

class AudioSizeException : Exception()
class VideoSizeException : Exception()
class MediaTypeException : Exception()
class CouldNotOpenFileException : Exception()

class MediaUploader @Inject constructor(
    private val context: Context,
    private val mastodonApi: MastodonApi
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun uploadMedia(media: QueuedMedia): Flow<UploadEvent> {
        return flow {
            if (shouldResizeMedia(media)) {
                emit(downsize(media))
            } else {
                emit(media)
            }
        }
            .flatMapLatest { upload(it) }
            .flowOn(Dispatchers.IO)
    }

    fun prepareMedia(inUri: Uri): PreparedMedia {
        var mediaSize = MEDIA_SIZE_UNKNOWN
        var uri = inUri
        val mimeType: String?

        try {
            when (inUri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {

                    mimeType = contentResolver.getType(uri)

                    val suffix = "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType ?: "tmp")

                    contentResolver.openInputStream(inUri).use { input ->
                        if (input == null) {
                            Log.w(TAG, "Media input is null")
                            uri = inUri
                            return@use
                        }
                        val file = File.createTempFile("randomTemp1", suffix, context.cacheDir)
                        FileOutputStream(file.absoluteFile).use { out ->
                            input.copyTo(out)
                            uri = FileProvider.getUriForFile(
                                context,
                                BuildConfig.APPLICATION_ID + ".fileprovider",
                                file
                            )
                            mediaSize = getMediaSize(contentResolver, uri)
                        }
                    }
                }
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path
                    if (path == null) {
                        Log.w(TAG, "empty uri path $uri")
                        throw CouldNotOpenFileException()
                    }
                    val inputFile = File(path)
                    val suffix = inputFile.name.substringAfterLast('.', "tmp")
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix)
                    val file = File.createTempFile("randomTemp1", ".$suffix", context.cacheDir)
                    val input = FileInputStream(inputFile)

                    FileOutputStream(file.absoluteFile).use { out ->
                        input.copyTo(out)
                        uri = FileProvider.getUriForFile(
                            context,
                            BuildConfig.APPLICATION_ID + ".fileprovider",
                            file
                        )
                        mediaSize = getMediaSize(contentResolver, uri)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown uri scheme $uri")
                    throw CouldNotOpenFileException()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, e)
            throw CouldNotOpenFileException()
        }
        if (mediaSize == MEDIA_SIZE_UNKNOWN) {
            Log.w(TAG, "Could not determine file size of upload")
            throw MediaTypeException()
        }

        if (mimeType != null) {
            return when (mimeType.substring(0, mimeType.indexOf('/'))) {
                "video" -> {
                    if (mediaSize > STATUS_VIDEO_SIZE_LIMIT) {
                        throw VideoSizeException()
                    }
                    PreparedMedia(QueuedMedia.Type.VIDEO, uri, mediaSize)
                }
                "image" -> {
                    PreparedMedia(QueuedMedia.Type.IMAGE, uri, mediaSize)
                }
                "audio" -> {
                    if (mediaSize > STATUS_AUDIO_SIZE_LIMIT) {
                        throw AudioSizeException()
                    }
                    PreparedMedia(QueuedMedia.Type.AUDIO, uri, mediaSize)
                }
                else -> {
                    throw MediaTypeException()
                }
            }
        } else {
            Log.w(TAG, "Could not determine mime type of upload")
            throw MediaTypeException()
        }
    }

    private val contentResolver = context.contentResolver

    private suspend fun upload(media: QueuedMedia): Flow<UploadEvent> {
        return callbackFlow {
            var mimeType = contentResolver.getType(media.uri)
            val map = MimeTypeMap.getSingleton()
            val fileExtension = map.getExtensionFromMimeType(mimeType)
            val filename = "%s_%s_%s.%s".format(
                context.getString(R.string.app_name),
                Date().time.toString(),
                randomAlphanumericString(10),
                fileExtension
            )

            val stream = contentResolver.openInputStream(media.uri)

            if (mimeType == null) mimeType = "multipart/form-data"

            var lastProgress = -1
            val fileBody = ProgressRequestBody(
                stream!!, media.mediaSize,
                mimeType.toMediaTypeOrNull()!!
            ) { percentage ->
                if (percentage != lastProgress) {
                    trySend(UploadEvent.ProgressEvent(percentage))
                }
                lastProgress = percentage
            }

            val body = MultipartBody.Part.createFormData("file", filename, fileBody)

            val description = if (media.description != null) {
                MultipartBody.Part.createFormData("description", media.description)
            } else {
                null
            }

            val result = mastodonApi.uploadMedia(body, description).getOrThrow()
            send(UploadEvent.FinishedEvent(result.id))
            awaitClose()
        }
    }

    private fun downsize(media: QueuedMedia): QueuedMedia {
        val file = createNewImageFile(context)
        downsizeImage(media.uri, STATUS_IMAGE_SIZE_LIMIT, contentResolver, file)
        return media.copy(uri = file.toUri(), mediaSize = file.length())
    }

    private fun shouldResizeMedia(media: QueuedMedia): Boolean {
        return media.type == QueuedMedia.Type.IMAGE &&
            (media.mediaSize > STATUS_IMAGE_SIZE_LIMIT || getImageSquarePixels(context.contentResolver, media.uri) > STATUS_IMAGE_PIXEL_SIZE_LIMIT)
    }

    private companion object {
        private const val TAG = "MediaUploaderImpl"
        private const val STATUS_VIDEO_SIZE_LIMIT = 41943040 // 40MiB
        private const val STATUS_AUDIO_SIZE_LIMIT = 41943040 // 40MiB
        private const val STATUS_IMAGE_SIZE_LIMIT = 8388608 // 8MiB
        private const val STATUS_IMAGE_PIXEL_SIZE_LIMIT = 16777216 // 4096^2 Pixels
    }
}
