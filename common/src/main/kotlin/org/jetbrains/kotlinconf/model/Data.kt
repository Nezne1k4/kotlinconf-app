package org.jetbrains.kotlinconf.model

import org.jetbrains.kotlinconf.*
import org.jetbrains.kotlinconf.api.*
import org.jetbrains.kotlinconf.data.*
import org.jetbrains.kotlinconf.presentation.*

class Data(
    val user: String
) : DataRepository {
    private val api = KotlinConfApi(user)
    private var signed: Boolean = false
    private var state = AllData()

    override var sessions: List<SessionModel> = listOf()
        private set

    override var favorites: List<SessionModel> = listOf()
        private set

    override fun getSessionById(id: String): SessionModel = SessionModel.forSession(state, id)!!

    override suspend fun update() {
        if (!signed) {
            api.createUser()
            signed = true
        }

        state = api.getAll()
        sessions = state.allSessions()
        favorites = state.favoriteSessions()
    }

    override fun getRating(sessionId: String): SessionRating {
        
    }

    override suspend fun addRating(sessionId: String, rating: SessionRating) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun removeRating(sessionId: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun setFavorite(sessionId: String, isFavorite: Boolean) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun isFavorite(sessionId: String): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}