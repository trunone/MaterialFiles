/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import com.github.trunone.mai70protocol.TokenStore
import com.github.trunone.mai70protocol.models.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object Mai70ServerAuthenticator : TokenStore {
    private val transientServers = mutableSetOf<Mai70Server>()

    override fun getToken(authority: Authority): String? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is Mai70Server && it.authority == authority
        } as Mai70Server?
        return server?.authority?.token
    }

    override fun saveToken(authority: Authority, token: String) {}

    fun addTransientServer(server: Mai70Server) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: Mai70Server) {
        synchronized(transientServers) { transientServers -= server }
    }
}
