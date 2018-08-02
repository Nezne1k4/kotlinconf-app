package org.jetbrains.kotlinconf.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.jetbrains.kotlinconf.data.*
import org.jetbrains.kotlinconf.utils.*

private val END_POINT = "api.kotlinconf.com"
private typealias Config = HttpRequestBuilder.() -> Unit

class KotlinConfApi(private val userId: String) {
    val client = HttpClient() {
        install(JsonFeature) {
            // add mappers
        }
        install(ExpectSuccess)
    }

    suspend fun createUser(): Boolean {
        val response = client.call {
            url.protocol = URLProtocol.HTTPS
            method = HttpMethod.Post
            url.host = END_POINT
            url.encodedPath = "/users"
            body = userId
        }.response

        return response.status.isSuccess()
    }

    suspend fun getAll(): AllData = client.get {
        url("all")
    }

    suspend fun postFavorite(favorite: Favorite): Unit = client.post {
        url("favorites")
        body = favorite
    }

    suspend fun deleteFavorite(favorite: Favorite): Unit = client.request {
        method = HttpMethod.Delete
        url("favorites")
        body = favorite
    }

    suspend fun postVote(vote: Vote): Unit = client.post {
        url("votes")
        body = vote
    }

    suspend fun deleteVote(vote: Vote): Unit = client.request {
    }

    private fun HttpRequestBuilder.setupDefault() {
        url.protocol = URLProtocol.HTTPS
        url.host = END_POINT
    }

    fun HttpRequestBuilder.url(path: String) {
        header("Authorization", "Bearer $userId")
        url {
            host = END_POINT
            protocol = URLProtocol.HTTPS
            encodedPath = path
        }
    }
}
