package com.aeibi.design.data.securestore

interface SecureStore {
    suspend fun put(key: String, value: String)

    suspend fun get(key: String): String?

    suspend fun contains(key: String): Boolean

    suspend fun delete(key: String)
}
