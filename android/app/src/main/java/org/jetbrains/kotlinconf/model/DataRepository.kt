package org.jetbrains.kotlinconf.model

import android.arch.lifecycle.*
import android.content.*
import android.content.Context.*
import android.provider.Settings.System.*
import android.widget.*
import com.google.gson.*
import kotlinx.coroutines.experimental.*
import org.jetbrains.anko.*
import org.jetbrains.kotlinconf.*
import org.jetbrains.kotlinconf.api.*
import org.jetbrains.kotlinconf.data.*
import org.jetbrains.kotlinconf.utils.*
import java.io.*

class DataRepository(
    private val context: Context,
    private val userId: String,
    private val onError: (Error) -> Toast
) : AnkoLogger {
    private val api: KotlinConfApi by lazy { KotlinConfApi(userId) }

    private val _data: MutableLiveData<AllData> = MutableLiveData()

    private val gson: Gson by lazy { GsonBuilder().setDateFormat(DATE_FORMAT).create() }

    private val favoritePreferences: SharedPreferences by lazy {
        context.getSharedPreferences(FAVORITES_PREFERENCES_NAME, MODE_PRIVATE)
    }

    private val ratingPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(VOTES_PREFERENCES_NAME, MODE_PRIVATE)
    }

    private val _ratings: MutableLiveData<Map<String, SessionRating>> = MutableLiveData()
    private val _isUpdating = MutableLiveData<Boolean>()

    val sessions: LiveData<List<SessionModel>> = map(_data) { data ->
        data?.sessions?.mapNotNull {
            createSessionModel(it)
        } ?: emptyList()
    }

    private val _favorites = MediatorLiveData<List<SessionModel>>().apply {
        addSource(sessions) { sessions ->
            val favorites = favoritePreferences.getStringSet(FAVORITES_KEY, emptySet())
            value = sessions?.filter { session -> favorites.contains(session.id) }
        }
    }

    val isUpdating: LiveData<Boolean> = _isUpdating
    val favorites: LiveData<List<SessionModel>> = _favorites
    val ratings: LiveData<Map<String, SessionRating>> = _ratings

    suspend fun setFavorite(sessionId: String, isFavorite: Boolean) {
        if (isFavorite) {
            addLocalFavorite(sessionId)
            api.postFavorite(Favorite(sessionId))
        } else {
            deleteLocalFavorite(sessionId)
            api.deleteFavorite(Favorite(sessionId))
        }
    }

    suspend fun addRating(sessionId: String, rating: SessionRating) {
        _ratings.value = getAllLocalRatings() + (sessionId to rating)

        try {
            api.postVote(Vote(sessionId = sessionId, rating = rating.value))
            saveLocalRating(sessionId, rating)
        } catch (cause: ApiException) {
            _ratings.value = getAllLocalRatings()
            val code = cause.response.status
            val error = when (code.value) {
                HTTP_COME_BACK_LATER -> Error.EARLY_TO_VOTE
                HTTP_TOO_LATE -> Error.LATE_TO_VOTE
                else -> Error.FAILED_TO_POST_RATING
            }

            onError(error)
        } catch (cause: Throwable) {
            _ratings.value = getAllLocalRatings()
            onError(Error.FAILED_TO_POST_RATING)
        }
    }

    suspend fun removeRating(sessionId: String) {
        _ratings.value = getAllLocalRatings() - sessionId
        try {
            api.deleteVote(Vote(sessionId = sessionId))
            deleteLocalRating(sessionId)
        } catch (cause: Throwable) {
            _ratings.value = getAllLocalRatings()
            onError(Error.FAILED_TO_DELETE_RATING)
        }
    }

    fun loadLocalData(): Boolean {
        val allDataFile = File(context.filesDir, CACHED_DATA_FILE_NAME)
        if (!allDataFile.exists()) {
            return false
        }

        val allData = gson.fromJson<AllData>(
            allDataFile.readText(),
            AllData::class.java
        ) ?: return false

        _data.value = allData

        val favorites = favoritePreferences.getStringSet(FAVORITES_KEY, mutableSetOf())
        _favorites.value = sessions.value?.filter { session -> favorites.contains(session.id) }

        _ratings.value = ratingPreferences.all.mapNotNull {
            SessionRating.valueOf(it.value as Int)?.let { rating -> it.key to rating }
        }.toMap()

        return true
    }

    suspend fun update() {
        if (_isUpdating.value == true) {
            return
        }

        _isUpdating.value = true

        try {
            val data = api.getAll()
            syncLocalFavorites(data)
            syncLocalRatings(data)
            updateLocalData(data)
        } catch (cause: Throwable) {
            warn("Failed to get data from server")
            onError.invoke(Error.FAILED_TO_GET_DATA)
        }

        _isUpdating.value = false
    }

    private fun createSessionModel(session: Session): SessionModel? = SessionModel.forSession(
        session,
        speakerProvider = this::getSpeaker,
        categoryProvider = this::getCategoryItem,
        roomProvider = this::getRoom
    )

    private fun getRoom(roomId: Int): Room? = _data.value?.rooms?.find { it.id == roomId }

    private fun getSpeaker(speakerId: String): Speaker? = _data.value?.speakers?.find { it.id == speakerId }

    private fun getCategoryItem(categoryItemId: Int): CategoryItem? {
        return _data.value?.categories
            ?.flatMap { it.items ?: emptyList() }
            ?.find { it?.id == categoryItemId }
    }

    private fun addLocalFavorite(sessionId: String) {
        val favorites = favoritePreferences.getStringSet(FAVORITES_KEY, setOf()).toMutableSet()
        favorites.add(sessionId)
        favoritePreferences
            .edit()
            .putStringSet(FAVORITES_KEY, favorites)
            .apply()

        _favorites.value = sessions.value?.filter { session -> favorites.contains(session.id) }
    }

    private fun deleteLocalFavorite(sessionId: String) {
        val favorites = favoritePreferences.getStringSet(FAVORITES_KEY, setOf()).toMutableSet()
        favorites.remove(sessionId)
        favoritePreferences
            .edit()
            .putStringSet(FAVORITES_KEY, favorites)
            .apply()

        _favorites.value = sessions.value?.filter { session -> favorites.contains(session.id) }
    }

    private fun getAllLocalRatings(): Map<String, SessionRating> {
        return ratingPreferences.all.mapNotNull { entry ->
            SessionRating.valueOf(entry.value as Int)?.let { rating -> entry.key to rating }
        }.toMap()
    }

    private fun saveLocalRating(sessionId: String, rating: SessionRating) {
        ratingPreferences.edit().putInt(sessionId, rating.value).apply()
        _ratings.value = getAllLocalRatings()
    }

    private fun deleteLocalRating(sessionId: String) {
        ratingPreferences.edit().remove(sessionId).apply()
        _ratings.value = getAllLocalRatings()
    }

    private fun syncLocalFavorites(allData: AllData) {
        val sessionIds = allData.favorites?.map { it.sessionId } ?: return
        val favorites = favoritePreferences
            .getStringSet(FAVORITES_KEY, mutableSetOf()).toMutableSet()

        val missingOnServer = favorites - sessionIds
        launch(CommonPool) {
            missingOnServer.forEach { api.postFavorite(Favorite(it)) }
        }

        favorites.addAll(sessionIds)
        favoritePreferences
            .edit()
            .putStringSet(FAVORITES_KEY, favorites)
            .apply()

        _favorites.value = sessions.value?.filter { session -> favorites.contains(session.id) }
    }

    private fun syncLocalRatings(allData: AllData) {
        val ratings = allData.votes?.mapNotNull {
            val sessionId = it.sessionId
            val rating = it.rating?.let { SessionRating.valueOf(it) }
            if (sessionId != null && rating != null) sessionId to rating else null
        }?.toMap()

        ratingPreferences.edit().apply {
            clear()
            ratings?.forEach { putInt(it.key, it.value.value) }
        }.apply()

        _ratings.value = ratings
    }

    private fun updateLocalData(allData: AllData) {
        val allDataFile = File(context.filesDir, CACHED_DATA_FILE_NAME)
        allDataFile.delete()
        allDataFile.createNewFile()
        allDataFile.writeText(gson.toJson(allData))
        _data.value = allData
    }

    companion object {
        const val FAVORITES_PREFERENCES_NAME = "favorites"
        const val VOTES_PREFERENCES_NAME = "votes"
        const val FAVORITES_KEY = "favorites"
        const val CACHED_DATA_FILE_NAME = "data.json"

        const val HTTP_COME_BACK_LATER = 477
        const val HTTP_TOO_LATE = 478
    }

    enum class Error {
        FAILED_TO_POST_RATING,
        FAILED_TO_DELETE_RATING,
        FAILED_TO_GET_DATA,
        EARLY_TO_VOTE,
        LATE_TO_VOTE
    }
}