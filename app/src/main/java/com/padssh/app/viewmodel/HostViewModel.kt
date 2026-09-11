package com.padssh.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.padssh.app.data.HostEntity
import com.padssh.app.data.HostRepository
import com.padssh.app.ssh.ConnectionState
import com.padssh.app.ssh.HostKeyDecision
import com.padssh.app.ssh.SshManager
import com.padssh.app.ssh.SshSessionService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostViewModel(
    private val app: Application,
    private val repository: HostRepository,
    private val sshManager: SshManager,
) : ViewModel() {

    val hosts: StateFlow<List<HostEntity>> = repository.observeHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = sshManager.connectionState
    val hostKeyPrompt: StateFlow<HostKeyDecision?> = sshManager.hostKeyPrompt
    val terminalOutput = sshManager.terminalOutput
    val terminalBuffer: StateFlow<String> = sshManager.terminalBuffer

    fun saveHost(host: HostEntity, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.saveHost(host)
            onDone(id)
        }
    }

    fun deleteHost(host: HostEntity) {
        viewModelScope.launch { repository.deleteHost(host) }
    }

    fun connect(host: HostEntity, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = sshManager.connect(host)
            if (result.isSuccess) {
                runCatching { sshManager.startShell() }
                SshSessionService.start(app, host.name)
            }
            onResult(result)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sshManager.disconnect()
            SshSessionService.stop(app)
        }
    }

    fun resolveHostKey(trust: Boolean) {
        sshManager.resolveHostKey(trust)
    }

    fun writeTerminal(text: String) = sshManager.writeToShell(text)

    fun writeTerminalBytes(bytes: ByteArray) = sshManager.writeBytesToShell(bytes)

    /** Resume health check: silent shell reattach if TCP still up. Runs on IO. */
    fun ensureSessionHealthy() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sshManager.ensureSessionHealthy()
        }
    }

    fun getSshManager(): SshManager = sshManager

    class Factory(
        private val app: Application,
        private val repository: HostRepository,
        private val sshManager: SshManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HostViewModel(app, repository, sshManager) as T
    }
}
