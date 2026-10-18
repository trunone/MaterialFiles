package com.github.trunone.mai70protocol

import com.github.trunone.mai70protocol.models.Authority

/**
 * Persistence abstraction for 70mai session tokens.
 */
interface TokenStore {
    /**
     * Retrieves stored session token for given authority.
     */
    fun getToken(authority: Authority): String?

    /**
     * Stores session token for given authority.
     */
    fun saveToken(authority: Authority, token: String)
}

/**
 * In-memory fallback implementation of [TokenStore].
 */
class InMemoryTokenStore : TokenStore {
    private val tokens = mutableMapOf<String, String>()

    override fun getToken(authority: Authority): String? {
        val key = "${authority.host}:${authority.port}"
        return tokens[key] ?: authority.token.takeIf { it.isNotEmpty() }
    }

    override fun saveToken(authority: Authority, token: String) {
        val key = "${authority.host}:${authority.port}"
        tokens[key] = token
    }
}
