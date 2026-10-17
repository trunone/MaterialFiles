/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditMai70ServerFragmentBinding
import me.zhanghai.android.files.provider.mai70.client.Authority
import me.zhanghai.android.files.provider.mai70.client.Protocol
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.setResult
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.viewModels
import java.net.URI

class EditMai70ServerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { EditMai70ServerViewModel() } }

    private lateinit var binding: EditMai70ServerFragmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenStarted {
            launch { viewModel.connectState.collect { onConnectStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        EditMai70ServerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            activity.setTitle(
                if (args.server != null) {
                    R.string.storage_edit_mai70_server_title_edit
                } else {
                    R.string.storage_edit_mai70_server_title_add
                }
            )
        }

        binding.hostEdit.hideTextInputLayoutErrorOnTextChange(binding.hostLayout)
        binding.hostEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.portEdit.hideTextInputLayoutErrorOnTextChange(binding.portLayout)
        binding.portEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.tokenEdit.hideTextInputLayoutErrorOnTextChange(binding.tokenLayout)
        binding.pathEdit.doAfterTextChanged { updateNamePlaceholder() }

        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_mai70_server_connect_and_add
            }
        )
        binding.saveOrConnectAndAddButton.setOnClickListener {
            if (args.server != null) {
                saveOrAdd()
            } else {
                connectAndAdd()
            }
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.removeOrAddButton.setText(
            if (args.server != null) R.string.remove else R.string.storage_edit_mai70_server_add
        )
        binding.removeOrAddButton.setOnClickListener {
            if (args.server != null) {
                remove()
            } else {
                saveOrAdd()
            }
        }

        if (savedInstanceState == null) {
            val server = args.server
            if (server != null) {
                val authority = server.authority
                binding.hostEdit.setText(authority.host)
                if (authority.port != Protocol.MAI70.defaultPort) {
                    binding.portEdit.setText(authority.port.toString())
                }
                binding.tokenEdit.setText(authority.token)
                binding.pathEdit.setText(server.relativePath)
                binding.nameEdit.setText(server.customName)
            } else {
                val host = args.host
                if (host != null) {
                    binding.hostEdit.setText(host)
                }
            }
        }
    }

    private fun updateNamePlaceholder() {
        val host = binding.hostEdit.text.toString().takeIfNotEmpty() ?: DEFAULT_HOST
        val port = binding.portEdit.text.toString().takeIfNotEmpty()?.toIntOrNull()
            ?: Protocol.MAI70.defaultPort
        val path = binding.pathEdit.text.toString().trim()
        val token = binding.tokenEdit.text.toString().trim()
        val authority = Authority(Protocol.MAI70, host, port, token)
        binding.nameLayout.placeholderText = if (path.isNotEmpty()) "$authority/$path" else authority.toString()
    }

    private fun saveOrAdd() {
        val server = getServerOrSetError() ?: return
        Storages.addOrReplace(server)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun connectAndAdd() {
        if (!viewModel.connectState.value.isReady) {
            return
        }
        val server = getServerOrSetError() ?: return
        viewModel.connect(server)
    }

    private fun onConnectStateChanged(state: ActionState<Mai70Server, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {
                val isConnecting = state is ActionState.Running
                binding.progress.fadeToVisibilityUnsafe(isConnecting)
                binding.scrollView.fadeToVisibilityUnsafe(!isConnecting)
                binding.saveOrConnectAndAddButton.isEnabled = !isConnecting
                binding.removeOrAddButton.isEnabled = !isConnecting
            }
            is ActionState.Success -> {
                Storages.addOrReplace(state.argument)
                setResult(Activity.RESULT_OK)
                finish()
            }
            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.printStackTrace()
                showToast(throwable.toString())
                viewModel.finishConnecting()
            }
        }
    }

    private fun remove() {
        Storages.remove(args.server!!)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun getServerOrSetError(): Mai70Server? {
        var errorEdit: TextInputEditText? = null
        val hostInput = binding.hostEdit.text.toString().takeIfNotEmpty() ?: DEFAULT_HOST
        val host = URI::class.canonicalizeHost(hostInput)
        if (host == null || !URI::class.isValidHost(host)) {
            binding.hostLayout.error =
                getString(R.string.storage_edit_mai70_server_host_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.hostEdit
            }
        }
        val port = binding.portEdit.text.toString().takeIfNotEmpty()
            .let { if (it != null) it.toIntOrNull() else Protocol.MAI70.defaultPort }
        if (port == null) {
            binding.portLayout.error =
                getString(R.string.storage_edit_mai70_server_port_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.portEdit
            }
        }
        val token = binding.tokenEdit.text.toString().trim()
        val path = binding.pathEdit.text.toString().trim()
        val name = binding.nameEdit.text.toString().takeIfNotEmpty()

        if (errorEdit != null || host == null || port == null) {
            errorEdit?.requestFocus()
            return null
        }
        val authority = Authority(Protocol.MAI70, host, port, token)
        return Mai70Server(args.server?.id, name, authority, path)
    }

    @Parcelize
    class Args(
        val server: Mai70Server? = null,
        val host: String? = null
    ) : ParcelableArgs

    companion object {
        private const val DEFAULT_HOST = "192.168.0.1"
    }
}
