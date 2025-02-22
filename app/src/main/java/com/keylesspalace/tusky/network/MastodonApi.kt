/* Copyright 2017 Andrew Dawson
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

package com.keylesspalace.tusky.network

import com.keylesspalace.tusky.entity.AccessToken
import com.keylesspalace.tusky.entity.Account
import com.keylesspalace.tusky.entity.Announcement
import com.keylesspalace.tusky.entity.AppCredentials
import com.keylesspalace.tusky.entity.Attachment
import com.keylesspalace.tusky.entity.Conversation
import com.keylesspalace.tusky.entity.DeletedStatus
import com.keylesspalace.tusky.entity.Emoji
import com.keylesspalace.tusky.entity.Filter
import com.keylesspalace.tusky.entity.IdentityProof
import com.keylesspalace.tusky.entity.Instance
import com.keylesspalace.tusky.entity.Marker
import com.keylesspalace.tusky.entity.MastoList
import com.keylesspalace.tusky.entity.MediaUploadResult
import com.keylesspalace.tusky.entity.NewStatus
import com.keylesspalace.tusky.entity.Notification
import com.keylesspalace.tusky.entity.Poll
import com.keylesspalace.tusky.entity.Relationship
import com.keylesspalace.tusky.entity.ScheduledStatus
import com.keylesspalace.tusky.entity.SearchResult
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.entity.StatusContext
import com.keylesspalace.tusky.entity.TimelineAccount
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * for documentation of the Mastodon REST API see https://docs.joinmastodon.org/api/
 */

@JvmSuppressWildcards
interface MastodonApi {

    companion object {
        const val ENDPOINT_AUTHORIZE = "oauth/authorize"
        const val DOMAIN_HEADER = "domain"
        const val PLACEHOLDER_DOMAIN = "dummy.placeholder"
    }

    @GET("/api/v1/lists")
    fun getLists(): Single<List<MastoList>>

    @GET("/api/v1/custom_emojis")
    suspend fun getCustomEmojis(): Result<List<Emoji>>

    @GET("api/v1/instance")
    suspend fun getInstance(): Result<Instance>

    @GET("api/v1/filters")
    fun getFilters(): Single<List<Filter>>

    @GET("api/v1/timelines/home")
    fun homeTimeline(
        @Query("max_id") maxId: String? = null,
        @Query("since_id") sinceId: String? = null,
        @Query("limit") limit: Int? = null
    ): Single<Response<List<Status>>>

    @GET("api/v1/timelines/public")
    fun publicTimeline(
        @Query("local") local: Boolean? = null,
        @Query("max_id") maxId: String? = null,
        @Query("since_id") sinceId: String? = null,
        @Query("limit") limit: Int? = null
    ): Single<Response<List<Status>>>

    @GET("api/v1/timelines/tag/{hashtag}")
    fun hashtagTimeline(
        @Path("hashtag") hashtag: String,
        @Query("any[]") any: List<String>?,
        @Query("local") local: Boolean?,
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?
    ): Single<Response<List<Status>>>

    @GET("api/v1/timelines/list/{listId}")
    fun listTimeline(
        @Path("listId") listId: String,
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?
    ): Single<Response<List<Status>>>

    @GET("api/v1/notifications")
    fun notifications(
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?,
        @Query("exclude_types[]") excludes: Set<Notification.Type>?
    ): Single<Response<List<Notification>>>

    @GET("api/v1/markers")
    fun markersWithAuth(
        @Header("Authorization") auth: String,
        @Header(DOMAIN_HEADER) domain: String,
        @Query("timeline[]") timelines: List<String>
    ): Single<Map<String, Marker>>

    @GET("api/v1/notifications")
    fun notificationsWithAuth(
        @Header("Authorization") auth: String,
        @Header(DOMAIN_HEADER) domain: String,
        @Query("since_id") sinceId: String?
    ): Single<List<Notification>>

    @POST("api/v1/notifications/clear")
    fun clearNotifications(): Single<ResponseBody>

    @Multipart
    @POST("api/v2/media")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part description: MultipartBody.Part? = null
    ): Result<MediaUploadResult>

    @FormUrlEncoded
    @PUT("api/v1/media/{mediaId}")
    suspend fun updateMedia(
        @Path("mediaId") mediaId: String,
        @Field("description") description: String
    ): Result<Attachment>

    @POST("api/v1/statuses")
    fun createStatus(
        @Header("Authorization") auth: String,
        @Header(DOMAIN_HEADER) domain: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body status: NewStatus
    ): Call<Status>

    @GET("api/v1/statuses/{id}")
    fun status(
        @Path("id") statusId: String
    ): Single<Status>

    @GET("api/v1/statuses/{id}/context")
    fun statusContext(
        @Path("id") statusId: String
    ): Single<StatusContext>

    @GET("api/v1/statuses/{id}/reblogged_by")
    fun statusRebloggedBy(
        @Path("id") statusId: String,
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @GET("api/v1/statuses/{id}/favourited_by")
    fun statusFavouritedBy(
        @Path("id") statusId: String,
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @DELETE("api/v1/statuses/{id}")
    fun deleteStatus(
        @Path("id") statusId: String
    ): Single<DeletedStatus>

    @POST("api/v1/statuses/{id}/reblog")
    fun reblogStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/unreblog")
    fun unreblogStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/favourite")
    fun favouriteStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/unfavourite")
    fun unfavouriteStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/bookmark")
    fun bookmarkStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/unbookmark")
    fun unbookmarkStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/pin")
    fun pinStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/unpin")
    fun unpinStatus(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/mute")
    fun muteConversation(
        @Path("id") statusId: String
    ): Single<Status>

    @POST("api/v1/statuses/{id}/unmute")
    fun unmuteConversation(
        @Path("id") statusId: String
    ): Single<Status>

    @GET("api/v1/scheduled_statuses")
    fun scheduledStatuses(
        @Query("limit") limit: Int? = null,
        @Query("max_id") maxId: String? = null
    ): Single<List<ScheduledStatus>>

    @DELETE("api/v1/scheduled_statuses/{id}")
    suspend fun deleteScheduledStatus(
        @Path("id") scheduledStatusId: String
    ): Result<ResponseBody>

    @GET("api/v1/accounts/verify_credentials")
    suspend fun accountVerifyCredentials(): Result<Account>

    @FormUrlEncoded
    @PATCH("api/v1/accounts/update_credentials")
    fun accountUpdateSource(
        @Field("source[privacy]") privacy: String?,
        @Field("source[sensitive]") sensitive: Boolean?
    ): Call<Account>

    @Multipart
    @PATCH("api/v1/accounts/update_credentials")
    suspend fun accountUpdateCredentials(
        @Part(value = "display_name") displayName: RequestBody?,
        @Part(value = "note") note: RequestBody?,
        @Part(value = "locked") locked: RequestBody?,
        @Part avatar: MultipartBody.Part?,
        @Part header: MultipartBody.Part?,
        @Part(value = "fields_attributes[0][name]") fieldName0: RequestBody?,
        @Part(value = "fields_attributes[0][value]") fieldValue0: RequestBody?,
        @Part(value = "fields_attributes[1][name]") fieldName1: RequestBody?,
        @Part(value = "fields_attributes[1][value]") fieldValue1: RequestBody?,
        @Part(value = "fields_attributes[2][name]") fieldName2: RequestBody?,
        @Part(value = "fields_attributes[2][value]") fieldValue2: RequestBody?,
        @Part(value = "fields_attributes[3][name]") fieldName3: RequestBody?,
        @Part(value = "fields_attributes[3][value]") fieldValue3: RequestBody?
    ): Result<Account>

    @GET("api/v1/accounts/search")
    fun searchAccounts(
        @Query("q") query: String,
        @Query("resolve") resolve: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("following") following: Boolean? = null
    ): Single<List<TimelineAccount>>

    @GET("api/v1/accounts/{id}")
    fun account(
        @Path("id") accountId: String
    ): Single<Account>

    /**
     * Method to fetch statuses for the specified account.
     * @param accountId ID for account for which statuses will be requested
     * @param maxId Only statuses with ID less than maxID will be returned
     * @param sinceId Only statuses with ID bigger than sinceID will be returned
     * @param limit Limit returned statuses (current API limits: default - 20, max - 40)
     * @param excludeReplies only return statuses that are no replies
     * @param onlyMedia only return statuses that have media attached
     */
    @GET("api/v1/accounts/{id}/statuses")
    fun accountStatuses(
        @Path("id") accountId: String,
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?,
        @Query("exclude_replies") excludeReplies: Boolean?,
        @Query("only_media") onlyMedia: Boolean?,
        @Query("pinned") pinned: Boolean?
    ): Single<Response<List<Status>>>

    @GET("api/v1/accounts/{id}/followers")
    fun accountFollowers(
        @Path("id") accountId: String,
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @GET("api/v1/accounts/{id}/following")
    fun accountFollowing(
        @Path("id") accountId: String,
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @FormUrlEncoded
    @POST("api/v1/accounts/{id}/follow")
    fun followAccount(
        @Path("id") accountId: String,
        @Field("reblogs") showReblogs: Boolean? = null,
        @Field("notify") notify: Boolean? = null
    ): Single<Relationship>

    @POST("api/v1/accounts/{id}/unfollow")
    fun unfollowAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @POST("api/v1/accounts/{id}/block")
    fun blockAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @POST("api/v1/accounts/{id}/unblock")
    fun unblockAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @FormUrlEncoded
    @POST("api/v1/accounts/{id}/mute")
    fun muteAccount(
        @Path("id") accountId: String,
        @Field("notifications") notifications: Boolean? = null,
        @Field("duration") duration: Int? = null
    ): Single<Relationship>

    @POST("api/v1/accounts/{id}/unmute")
    fun unmuteAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @GET("api/v1/accounts/relationships")
    fun relationships(
        @Query("id[]") accountIds: List<String>
    ): Single<List<Relationship>>

    @GET("api/v1/accounts/{id}/identity_proofs")
    fun identityProofs(
        @Path("id") accountId: String
    ): Single<List<IdentityProof>>

    @POST("api/v1/pleroma/accounts/{id}/subscribe")
    fun subscribeAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @POST("api/v1/pleroma/accounts/{id}/unsubscribe")
    fun unsubscribeAccount(
        @Path("id") accountId: String
    ): Single<Relationship>

    @GET("api/v1/blocks")
    fun blocks(
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @GET("api/v1/mutes")
    fun mutes(
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @GET("api/v1/domain_blocks")
    fun domainBlocks(
        @Query("max_id") maxId: String? = null,
        @Query("since_id") sinceId: String? = null,
        @Query("limit") limit: Int? = null
    ): Single<Response<List<String>>>

    @FormUrlEncoded
    @POST("api/v1/domain_blocks")
    fun blockDomain(
        @Field("domain") domain: String
    ): Call<Any>

    @FormUrlEncoded
    // @DELETE doesn't support fields
    @HTTP(method = "DELETE", path = "api/v1/domain_blocks", hasBody = true)
    fun unblockDomain(@Field("domain") domain: String): Call<Any>

    @GET("api/v1/favourites")
    fun favourites(
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?
    ): Single<Response<List<Status>>>

    @GET("api/v1/bookmarks")
    fun bookmarks(
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("limit") limit: Int?
    ): Single<Response<List<Status>>>

    @GET("api/v1/follow_requests")
    fun followRequests(
        @Query("max_id") maxId: String?
    ): Single<Response<List<TimelineAccount>>>

    @POST("api/v1/follow_requests/{id}/authorize")
    fun authorizeFollowRequest(
        @Path("id") accountId: String
    ): Single<Relationship>

    @POST("api/v1/follow_requests/{id}/reject")
    fun rejectFollowRequest(
        @Path("id") accountId: String
    ): Single<Relationship>

    @FormUrlEncoded
    @POST("api/v1/apps")
    suspend fun authenticateApp(
        @Header(DOMAIN_HEADER) domain: String,
        @Field("client_name") clientName: String,
        @Field("redirect_uris") redirectUris: String,
        @Field("scopes") scopes: String,
        @Field("website") website: String
    ): Result<AppCredentials>

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun fetchOAuthToken(
        @Header(DOMAIN_HEADER) domain: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String
    ): Result<AccessToken>

    @FormUrlEncoded
    @POST("api/v1/lists")
    fun createList(
        @Field("title") title: String
    ): Single<MastoList>

    @FormUrlEncoded
    @PUT("api/v1/lists/{listId}")
    fun updateList(
        @Path("listId") listId: String,
        @Field("title") title: String
    ): Single<MastoList>

    @DELETE("api/v1/lists/{listId}")
    fun deleteList(
        @Path("listId") listId: String
    ): Completable

    @GET("api/v1/lists/{listId}/accounts")
    fun getAccountsInList(
        @Path("listId") listId: String,
        @Query("limit") limit: Int
    ): Single<List<TimelineAccount>>

    @FormUrlEncoded
    // @DELETE doesn't support fields
    @HTTP(method = "DELETE", path = "api/v1/lists/{listId}/accounts", hasBody = true)
    fun deleteAccountFromList(
        @Path("listId") listId: String,
        @Field("account_ids[]") accountIds: List<String>
    ): Completable

    @FormUrlEncoded
    @POST("api/v1/lists/{listId}/accounts")
    fun addCountToList(
        @Path("listId") listId: String,
        @Field("account_ids[]") accountIds: List<String>
    ): Completable

    @GET("/api/v1/conversations")
    suspend fun getConversations(
        @Query("max_id") maxId: String? = null,
        @Query("limit") limit: Int
    ): List<Conversation>

    @DELETE("/api/v1/conversations/{id}")
    suspend fun deleteConversation(
        @Path("id") conversationId: String
    )

    @FormUrlEncoded
    @POST("api/v1/filters")
    fun createFilter(
        @Field("phrase") phrase: String,
        @Field("context[]") context: List<String>,
        @Field("irreversible") irreversible: Boolean?,
        @Field("whole_word") wholeWord: Boolean?,
        @Field("expires_in") expiresIn: String?
    ): Call<Filter>

    @FormUrlEncoded
    @PUT("api/v1/filters/{id}")
    fun updateFilter(
        @Path("id") id: String,
        @Field("phrase") phrase: String,
        @Field("context[]") context: List<String>,
        @Field("irreversible") irreversible: Boolean?,
        @Field("whole_word") wholeWord: Boolean?,
        @Field("expires_in") expiresIn: String?
    ): Call<Filter>

    @DELETE("api/v1/filters/{id}")
    fun deleteFilter(
        @Path("id") id: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("api/v1/polls/{id}/votes")
    fun voteInPoll(
        @Path("id") id: String,
        @Field("choices[]") choices: List<Int>
    ): Single<Poll>

    @GET("api/v1/announcements")
    suspend fun listAnnouncements(
        @Query("with_dismissed") withDismissed: Boolean = true
    ): Result<List<Announcement>>

    @POST("api/v1/announcements/{id}/dismiss")
    suspend fun dismissAnnouncement(
        @Path("id") announcementId: String
    ): Result<ResponseBody>

    @PUT("api/v1/announcements/{id}/reactions/{name}")
    suspend fun addAnnouncementReaction(
        @Path("id") announcementId: String,
        @Path("name") name: String
    ): Result<ResponseBody>

    @DELETE("api/v1/announcements/{id}/reactions/{name}")
    suspend fun removeAnnouncementReaction(
        @Path("id") announcementId: String,
        @Path("name") name: String
    ): Result<ResponseBody>

    @FormUrlEncoded
    @POST("api/v1/reports")
    fun reportObservable(
        @Field("account_id") accountId: String,
        @Field("status_ids[]") statusIds: List<String>,
        @Field("comment") comment: String,
        @Field("forward") isNotifyRemote: Boolean?
    ): Single<ResponseBody>

    @GET("api/v1/accounts/{id}/statuses")
    fun accountStatusesObservable(
        @Path("id") accountId: String,
        @Query("max_id") maxId: String?,
        @Query("since_id") sinceId: String?,
        @Query("min_id") minId: String?,
        @Query("limit") limit: Int?,
        @Query("exclude_reblogs") excludeReblogs: Boolean?
    ): Single<List<Status>>

    @GET("api/v1/statuses/{id}")
    fun statusObservable(
        @Path("id") statusId: String
    ): Single<Status>

    @GET("api/v2/search")
    fun searchObservable(
        @Query("q") query: String?,
        @Query("type") type: String? = null,
        @Query("resolve") resolve: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("following") following: Boolean? = null
    ): Single<SearchResult>

    @FormUrlEncoded
    @POST("api/v1/accounts/{id}/note")
    fun updateAccountNote(
        @Path("id") accountId: String,
        @Field("comment") note: String
    ): Single<Relationship>
}
