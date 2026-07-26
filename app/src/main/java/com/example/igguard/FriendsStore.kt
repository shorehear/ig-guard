package com.example.igguard

import android.content.Context

/**
 * Простое хранилище whitelist'а username'ов друзей.
 * Сравнение регистронезависимое, без учёта ведущего '@'.
 */
object FriendsStore {

    private const val PREFS = "ig_guard_prefs"
    private const val KEY_FRIENDS = "friends_set"

    fun getFriends(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FRIENDS, emptySet()) ?: emptySet()
    }

    fun setFriends(context: Context, friends: Set<String>) {
        val normalized = friends.map { normalize(it) }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FRIENDS, normalized)
            .apply()
    }

    fun isFriend(context: Context, username: String): Boolean {
        return normalize(username) in getFriends(context)
    }

    private fun normalize(username: String): String {
        return username.trim().removePrefix("@").lowercase()
    }
}
