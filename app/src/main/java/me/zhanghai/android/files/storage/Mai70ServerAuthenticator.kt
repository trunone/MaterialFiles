/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.mai70.client.Authenticator
import me.zhanghai.android.files.provider.mai70.client.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object Mai70ServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<Mai70Server>()

    override fun getToken(authority: Authority): String? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is Mai70Server && it.authority == authority
        } as Mai70Server?
        return server?.authority?.token
    }

    fun addTransientServer(server: Mai70Server) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: Mai70Server) {
        synchronized(transientServers) { transientServers -= server }
    }
}
