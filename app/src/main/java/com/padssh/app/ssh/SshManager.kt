package com.padssh.app.ssh

import com.padssh.app.data.HostEntity
import com.padssh.app.data.HostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val hostId: Long, val label: String) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

class SshManager(private val repository: HostRepository) {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private var client: SSHClient? = null
    private var shell: Session.Shell? = null
    private var session: Session? = null
    private var sftp: SFTPClient? = null
    private val reading = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminalOutput = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val terminalOutput: SharedFlow<String> = _terminalOutput.asSharedFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyDecision?>(null)
    val hostKeyPrompt: StateFlow<HostKeyDecision?> = _hostKeyPrompt.asStateFlow()

    private var tofuVerifier: TofuHostKeyVerifier? = null

    fun resolveHostKey(trust: Boolean) {
        tofuVerifier?.resolvePrompt(trust)
        _hostKeyPrompt.value = null
    }

    suspend fun connect(host: HostEntity): Result<Unit> = withContext(Dispatchers.IO) {
        disconnectInternal()
        _connectionState.value = ConnectionState.Connecting
        try {
            val ssh = SSHClient()
            val verifier = TofuHostKeyVerifier(repository) { decision ->
                _hostKeyPrompt.value = decision
            }
            tofuVerifier = verifier
            ssh.addHostKeyVerifier(verifier)
            ssh.connectTimeout = 15_000
            ssh.timeout = 30_000
            ssh.connect(host.host, host.port)

            if (host.useKeyAuth && !host.privateKey.isNullOrBlank()) {
                val tmp = File.createTempFile("padssh_key", ".pem")
                try {
                    tmp.writeText(host.privateKey)
                    val keys: KeyProvider = if (!host.keyPassphrase.isNullOrBlank()) {
                        ssh.loadKeys(tmp.absolutePath, host.keyPassphrase)
                    } else {
                        ssh.loadKeys(tmp.absolutePath)
                    }
                    ssh.authPublickey(host.username, keys)
                } finally {
                    tmp.delete()
                }
            } else {
                ssh.authPassword(host.username, host.password ?: "")
            }

            client = ssh
            _connectionState.value = ConnectionState.Connected(host.id, host.name)
            Result.success(Unit)
        } catch (e: Exception) {
            disconnectInternal()
            val msg = e.message ?: e.javaClass.simpleName
            _connectionState.value = ConnectionState.Failed(msg)
            Result.failure(e)
        }
    }

    suspend fun startShell(cols: Int = 80, rows: Int = 24) = withContext(Dispatchers.IO) {
        val ssh = client ?: error("未连接")
        stopShellInternal()
        val sess = ssh.startSession()
        sess.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>())
        val sh = sess.startShell()
        session = sess
        shell = sh
        reading.set(true)
        Thread({
            val buf = ByteArray(4096)
            try {
                val input: InputStream = sh.inputStream
                while (reading.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        _terminalOutput.tryEmit(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {
            }
        }, "padssh-shell-reader").also { it.isDaemon = true; it.start() }
    }

    fun writeToShell(data: String) {
        val out: OutputStream = shell?.outputStream ?: return
        try {
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun writeBytesToShell(bytes: ByteArray) {
        val out: OutputStream = shell?.outputStream ?: return
        try {
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        }
    }

    suspend fun changeWindowSize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        try {
            shell?.changeWindowDimensions(cols, rows, 0, 0)
        } catch (_: Exception) {
        }
    }

    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) {
        sftp?.closeQuietly()
        val ssh = client ?: error("未连接")
        ssh.newSFTPClient().also { sftp = it }
    }

    fun getSftp(): SFTPClient? = sftp

    suspend fun listRemote(path: String): List<RemoteResourceInfo> = withContext(Dispatchers.IO) {
        val client = sftp ?: openSftp()
        client.ls(path).sortedWith(
            compareBy<RemoteResourceInfo> { !it.isDirectory }.thenBy { it.name.lowercase() },
        )
    }

    suspend fun downloadFile(remotePath: String, localFile: File) = withContext(Dispatchers.IO) {
        val client = sftp ?: openSftp()
        client.get(remotePath, localFile.absolutePath)
    }

    suspend fun uploadFile(localFile: File, remotePath: String) = withContext(Dispatchers.IO) {
        val client = sftp ?: openSftp()
        client.put(localFile.absolutePath, remotePath)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun disconnectInternal() {
        reading.set(false)
        stopShellInternal()
        sftp?.closeQuietly()
        sftp = null
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        tofuVerifier = null
        _hostKeyPrompt.value = null
    }

    private fun stopShellInternal() {
        try {
            shell?.close()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        shell = null
        session = null
    }

    private fun SFTPClient.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }
}
