package org.jetbrains.kotlinconf

import android.app.*
import kotlinx.coroutines.experimental.*
import org.jetbrains.anko.*
import org.jetbrains.kotlinconf.model.*
import java.util.*

class KotlinConfApplication : Application(), AnkoLogger {
    lateinit var repository: DataRepository

    override fun onCreate() {
        super.onCreate()
        val userId = getUserId()

        repository = DataRepository(
            this, userId
        ) { action ->
            when (action) {
                DataRepository.Error.FAILED_TO_DELETE_RATING -> toast(R.string.msg_failed_to_delete_vote)
                DataRepository.Error.FAILED_TO_POST_RATING -> toast(R.string.msg_failed_to_post_vote)
                DataRepository.Error.FAILED_TO_GET_DATA -> toast(R.string.msg_failed_to_get_data)
                DataRepository.Error.EARLY_TO_VOTE -> toast(R.string.msg_early_vote)
                DataRepository.Error.LATE_TO_VOTE -> toast(R.string.msg_late_vote)
            }
        }

        launch {
            val dataLoaded = repository.loadLocalData()
            if (!dataLoaded) repository.update()
        }
    }

    private fun getUserId(): String {
        defaultSharedPreferences.getString(USER_ID_KEY, null)?.let { return it }

        val userId = "android-" + UUID.randomUUID().toString()
        defaultSharedPreferences
            .edit()
            .putString(USER_ID_KEY, userId)
            .apply()

        return userId
    }

    companion object {
        const val USER_ID_KEY = "UserId"
    }
}