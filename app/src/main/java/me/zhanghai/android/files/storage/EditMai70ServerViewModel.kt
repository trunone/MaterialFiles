/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.mai70.Mai70FileSystemProvider
import me.zhanghai.android.files.util.ActionState

class EditMai70ServerViewModel : ViewModel() {
    private val _connectState = MutableStateFlow<ActionState<Mai70Server, Unit>>(ActionState.Ready())
    val connectState: StateFlow<ActionState<Mai70Server, Unit>> = _connectState

    fun connect(server: Mai70Server) {
        if (_connectState.value !is ActionState.Ready) {
            return
        }
        _connectState.value = ActionState.Running(server)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Mai70ServerAuthenticator.addTransientServer(server)
                    try {
                        val client = Mai70FileSystemProvider.getMai70Client(server.authority)
                        client.prepareCameraAccess()
                    } finally {
                        Mai70ServerAuthenticator.removeTransientServer(server)
                    }
                }
                _connectState.value = ActionState.Success(server, Unit)
            } catch (e: Exception) {
                _connectState.value = ActionState.Error(server, e)
            }
        }
    }

    fun finishConnecting() {
        _connectState.value = ActionState.Ready()
    }
}
