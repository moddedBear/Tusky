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

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keylesspalace.tusky.components.compose.ComposeActivity.QueuedMedia
import com.keylesspalace.tusky.components.drafts.DraftHelper
import com.keylesspalace.tusky.components.instanceinfo.InstanceInfo
import com.keylesspalace.tusky.components.instanceinfo.InstanceInfoRepository
import com.keylesspalace.tusky.components.search.SearchType
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.entity.Attachment
import com.keylesspalace.tusky.entity.Emoji
import com.keylesspalace.tusky.entity.NewPoll
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.service.ServiceClient
import com.keylesspalace.tusky.service.StatusToSend
import com.keylesspalace.tusky.util.combineLiveData
import com.keylesspalace.tusky.util.filter
import com.keylesspalace.tusky.util.map
import com.keylesspalace.tusky.util.randomAlphanumericString
import com.keylesspalace.tusky.util.toLiveData
import com.keylesspalace.tusky.util.withoutFirstWhich
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.rxSingle
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

class ComposeViewModel @Inject constructor(
    private val api: MastodonApi,
    private val accountManager: AccountManager,
    private val mediaUploader: MediaUploader,
    private val serviceClient: ServiceClient,
    private val draftHelper: DraftHelper,
    private val instanceInfoRepo: InstanceInfoRepository
) : ViewModel() {

    private var replyingStatusAuthor: String? = null
    private var replyingStatusContent: String? = null
    internal var startingText: String? = null
    private var draftId: Int = 0
    private var scheduledTootId: String? = null
    private var startingContentWarning: String = ""
    private var inReplyToId: String? = null
    private var startingVisibility: Status.Visibility = Status.Visibility.UNKNOWN

    private var contentWarningStateChanged: Boolean = false
    private var modifiedInitialState: Boolean = false

    val instanceInfo: MutableLiveData<InstanceInfo> = MutableLiveData()

    val emoji: MutableLiveData<List<Emoji>?> = MutableLiveData()
    val markMediaAsSensitive =
        mutableLiveData(accountManager.activeAccount?.defaultMediaSensitivity ?: false)

    val statusVisibility = mutableLiveData(Status.Visibility.UNKNOWN)
    val showContentWarning = mutableLiveData(false)
    val setupComplete = mutableLiveData(false)
    val poll: MutableLiveData<NewPoll?> = mutableLiveData(null)
    val scheduledAt: MutableLiveData<String?> = mutableLiveData(null)

    val media = mutableLiveData<List<QueuedMedia>>(listOf())
    val uploadError = MutableLiveData<Throwable>()

    private val mediaToJob = mutableMapOf<Long, Job>()

    private val isEditingScheduledToot get() = !scheduledTootId.isNullOrEmpty()

    init {
        viewModelScope.launch {
            emoji.postValue(instanceInfoRepo.getEmojis())
        }
        viewModelScope.launch {
            instanceInfo.postValue(instanceInfoRepo.getInstanceInfo())
        }
    }

    suspend fun pickMedia(mediaUri: Uri, description: String? = null): Result<QueuedMedia> = withContext(Dispatchers.IO) {
        try {
            val (type, uri, size) = mediaUploader.prepareMedia(mediaUri)
            val mediaItems = media.value!!
            if (type != QueuedMedia.Type.IMAGE &&
                mediaItems.isNotEmpty() &&
                mediaItems[0].type == QueuedMedia.Type.IMAGE
            ) {
                Result.failure(VideoOrImageException())
            } else {
                val queuedMedia = addMediaToQueue(type, uri, size, description)
                Result.success(queuedMedia)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addMediaToQueue(
        type: QueuedMedia.Type,
        uri: Uri,
        mediaSize: Long,
        description: String? = null
    ): QueuedMedia {
        val mediaItem = QueuedMedia(
            localId = System.currentTimeMillis(),
            uri = uri,
            type = type,
            mediaSize = mediaSize,
            description = description
        )
        media.postValue(media.value!! + mediaItem)
        mediaToJob[mediaItem.localId] = viewModelScope.launch {
            mediaUploader
                .uploadMedia(mediaItem)
                .catch { error ->
                    media.postValue(media.value?.filter { it.localId != mediaItem.localId } ?: emptyList())
                    uploadError.postValue(error)
                }
                .collect { event ->
                    val item = media.value?.find { it.localId == mediaItem.localId }
                        ?: return@collect
                    val newMediaItem = when (event) {
                        is UploadEvent.ProgressEvent ->
                            item.copy(uploadPercent = event.percentage)
                        is UploadEvent.FinishedEvent ->
                            item.copy(id = event.mediaId, uploadPercent = -1)
                    }
                    synchronized(media) {
                        val mediaValue = media.value!!
                        val index = mediaValue.indexOfFirst { it.localId == newMediaItem.localId }
                        media.postValue(
                            if (index == -1) {
                                mediaValue + newMediaItem
                            } else {
                                mediaValue.toMutableList().also { it[index] = newMediaItem }
                            }
                        )
                    }
                }
        }
        return mediaItem
    }

    private fun addUploadedMedia(id: String, type: QueuedMedia.Type, uri: Uri, description: String?) {
        val mediaItem = QueuedMedia(System.currentTimeMillis(), uri, type, 0, -1, id, description)
        media.value = media.value!! + mediaItem
    }

    fun removeMediaFromQueue(item: QueuedMedia) {
        mediaToJob[item.localId]?.cancel()
        media.value = media.value!!.withoutFirstWhich { it.localId == item.localId }
    }

    fun toggleMarkSensitive() {
        this.markMediaAsSensitive.value = this.markMediaAsSensitive.value != true
    }

    fun didChange(content: String?, contentWarning: String?): Boolean {

        val textChanged = !(
            content.isNullOrEmpty() ||
                startingText?.startsWith(content.toString()) ?: false
            )

        val contentWarningChanged = showContentWarning.value!! &&
            !contentWarning.isNullOrEmpty() &&
            !startingContentWarning.startsWith(contentWarning.toString())
        val mediaChanged = !media.value.isNullOrEmpty()
        val pollChanged = poll.value != null

        return modifiedInitialState || textChanged || contentWarningChanged || mediaChanged || pollChanged
    }

    fun contentWarningChanged(value: Boolean) {
        showContentWarning.value = value
        contentWarningStateChanged = true
    }

    fun deleteDraft() {
        viewModelScope.launch {
            if (draftId != 0) {
                draftHelper.deleteDraftAndAttachments(draftId)
            }
        }
    }

    fun saveDraft(content: String, contentWarning: String) {
        viewModelScope.launch {
            val mediaUris: MutableList<String> = mutableListOf()
            val mediaDescriptions: MutableList<String?> = mutableListOf()
            media.value?.forEach { item ->
                mediaUris.add(item.uri.toString())
                mediaDescriptions.add(item.description)
            }

            draftHelper.saveDraft(
                draftId = draftId,
                accountId = accountManager.activeAccount?.id!!,
                inReplyToId = inReplyToId,
                content = content,
                contentWarning = contentWarning,
                sensitive = markMediaAsSensitive.value!!,
                visibility = statusVisibility.value!!,
                mediaUris = mediaUris,
                mediaDescriptions = mediaDescriptions,
                poll = poll.value,
                failedToSend = false
            )
        }
    }

    /**
     * Send status to the server.
     * Uses current state plus provided arguments.
     * @return LiveData which will signal once the screen can be closed or null if there are errors
     */
    fun sendStatus(
        content: String,
        spoilerText: String
    ): LiveData<Unit> {

        val deletionObservable = if (isEditingScheduledToot) {
            rxSingle { api.deleteScheduledStatus(scheduledTootId.toString()) }.toObservable().map { }
        } else {
            Observable.just(Unit)
        }.toLiveData()

        val sendObservable = media
            .filter { items -> items.all { it.uploadPercent == -1 } }
            .map {
                val mediaIds = ArrayList<String>()
                val mediaUris = ArrayList<Uri>()
                val mediaDescriptions = ArrayList<String>()
                for (item in media.value!!) {
                    mediaIds.add(item.id!!)
                    mediaUris.add(item.uri)
                    mediaDescriptions.add(item.description ?: "")
                }

                val tootToSend = StatusToSend(
                    text = content,
                    warningText = spoilerText,
                    visibility = statusVisibility.value!!.serverString(),
                    sensitive = mediaUris.isNotEmpty() && (markMediaAsSensitive.value!! || showContentWarning.value!!),
                    mediaIds = mediaIds,
                    mediaUris = mediaUris.map { it.toString() },
                    mediaDescriptions = mediaDescriptions,
                    scheduledAt = scheduledAt.value,
                    inReplyToId = inReplyToId,
                    poll = poll.value,
                    replyingStatusContent = null,
                    replyingStatusAuthorUsername = null,
                    accountId = accountManager.activeAccount!!.id,
                    draftId = draftId,
                    idempotencyKey = randomAlphanumericString(16),
                    retries = 0
                )

                serviceClient.sendToot(tootToSend)
            }

        return combineLiveData(deletionObservable, sendObservable) { _, _ -> }
    }

    suspend fun updateDescription(localId: Long, description: String): Boolean {
        val newList = media.value!!.toMutableList()
        val index = newList.indexOfFirst { it.localId == localId }
        if (index != -1) {
            newList[index] = newList[index].copy(description = description)
        }
        media.value = newList
        val updatedItem = newList.find { it.localId == localId }
        if (updatedItem?.id != null) {
            return api.updateMedia(updatedItem.id, description)
                .fold({
                    true
                }, { throwable ->
                    Log.w(TAG, "failed to update media", throwable)
                    false
                })
        }
        return true
    }

    fun searchAutocompleteSuggestions(token: String): List<ComposeAutoCompleteAdapter.AutocompleteResult> {
        when (token[0]) {
            '@' -> {
                return try {
                    api.searchAccounts(query = token.substring(1), limit = 10)
                        .blockingGet()
                        .map { ComposeAutoCompleteAdapter.AccountResult(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, String.format("Autocomplete search for %s failed.", token), e)
                    emptyList()
                }
            }
            '#' -> {
                return try {
                    api.searchObservable(query = token, type = SearchType.Hashtag.apiParameter, limit = 10)
                        .blockingGet()
                        .hashtags
                        .map { ComposeAutoCompleteAdapter.HashtagResult(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, String.format("Autocomplete search for %s failed.", token), e)
                    emptyList()
                }
            }
            ':' -> {
                val emojiList = emoji.value ?: return emptyList()

                val incomplete = token.substring(1).lowercase(Locale.ROOT)
                val results = ArrayList<ComposeAutoCompleteAdapter.AutocompleteResult>()
                val resultsInside = ArrayList<ComposeAutoCompleteAdapter.AutocompleteResult>()
                for (emoji in emojiList) {
                    val shortcode = emoji.shortcode.lowercase(Locale.ROOT)
                    if (shortcode.startsWith(incomplete)) {
                        results.add(ComposeAutoCompleteAdapter.EmojiResult(emoji))
                    } else if (shortcode.indexOf(incomplete, 1) != -1) {
                        resultsInside.add(ComposeAutoCompleteAdapter.EmojiResult(emoji))
                    }
                }
                if (results.isNotEmpty() && resultsInside.isNotEmpty()) {
                    results.add(ComposeAutoCompleteAdapter.ResultSeparator())
                }
                results.addAll(resultsInside)
                return results
            }
            else -> {
                Log.w(TAG, "Unexpected autocompletion token: $token")
                return emptyList()
            }
        }
    }

    fun setup(composeOptions: ComposeActivity.ComposeOptions?) {

        if (setupComplete.value == true) {
            return
        }

        val preferredVisibility = accountManager.activeAccount!!.defaultPostPrivacy

        val replyVisibility = composeOptions?.replyVisibility ?: Status.Visibility.UNKNOWN
        startingVisibility = Status.Visibility.byNum(
            preferredVisibility.num.coerceAtLeast(replyVisibility.num)
        )

        inReplyToId = composeOptions?.inReplyToId

        modifiedInitialState = composeOptions?.modifiedInitialState == true

        val contentWarning = composeOptions?.contentWarning
        if (contentWarning != null) {
            startingContentWarning = contentWarning
        }
        if (!contentWarningStateChanged) {
            showContentWarning.value = !contentWarning.isNullOrBlank()
        }

        // recreate media list
        val draftAttachments = composeOptions?.draftAttachments
        if (draftAttachments != null) {
            // when coming from DraftActivity
            draftAttachments.forEach { attachment ->
                viewModelScope.launch {
                    pickMedia(attachment.uri, attachment.description)
                }
            }
        } else composeOptions?.mediaAttachments?.forEach { a ->
            // when coming from redraft or ScheduledTootActivity
            val mediaType = when (a.type) {
                Attachment.Type.VIDEO, Attachment.Type.GIFV -> QueuedMedia.Type.VIDEO
                Attachment.Type.UNKNOWN, Attachment.Type.IMAGE -> QueuedMedia.Type.IMAGE
                Attachment.Type.AUDIO -> QueuedMedia.Type.AUDIO
            }
            addUploadedMedia(a.id, mediaType, a.url.toUri(), a.description)
        }

        draftId = composeOptions?.draftId ?: 0
        scheduledTootId = composeOptions?.scheduledTootId
        startingText = composeOptions?.content

        val tootVisibility = composeOptions?.visibility ?: Status.Visibility.UNKNOWN
        if (tootVisibility.num != Status.Visibility.UNKNOWN.num) {
            startingVisibility = tootVisibility
        }
        statusVisibility.value = startingVisibility
        val mentionedUsernames = composeOptions?.mentionedUsernames
        if (mentionedUsernames != null) {
            val builder = StringBuilder()
            for (name in mentionedUsernames) {
                builder.append('@')
                builder.append(name)
                builder.append(' ')
            }
            startingText = builder.toString()
        }

        scheduledAt.value = composeOptions?.scheduledAt

        composeOptions?.sensitive?.let { markMediaAsSensitive.value = it }

        val poll = composeOptions?.poll
        if (poll != null && composeOptions.mediaAttachments.isNullOrEmpty()) {
            this.poll.value = poll
        }
        replyingStatusContent = composeOptions?.replyingStatusContent
        replyingStatusAuthor = composeOptions?.replyingStatusAuthor
    }

    fun updatePoll(newPoll: NewPoll) {
        poll.value = newPoll
    }

    fun updateScheduledAt(newScheduledAt: String?) {
        scheduledAt.value = newScheduledAt
    }

    private companion object {
        const val TAG = "ComposeViewModel"
    }
}

fun <T> mutableLiveData(default: T) = MutableLiveData<T>().apply { value = default }

/**
 * Thrown when trying to add an image when video is already present or the other way around
 */
class VideoOrImageException : Exception()
