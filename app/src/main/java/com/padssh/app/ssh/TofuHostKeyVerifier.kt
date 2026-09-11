package com.padssh.app.ssh

import com.padssh.app.data.HostKeyEntity
import com.padssh.app.data.HostRepository
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed class HostKeyDecision {
    data class Unknown(
        val hostPort: String,
        val algorithm: String,
        val fingerprint: String,
        val keyBase64: String,
    ) : HostKeyDecision()

    data class Changed(
        val hostPort: String,
        val algorithm: String,
        val fingerprint: String,
        val keyBase64: String,
        val oldFingerprint: String,
    ) : HostKeyDecision()
}

/**
 * Trust-On-First-Use host key verifier backed by Room.
 * When a prompt is needed, [onPrompt] is invoked on a background thread;
 * call [resolvePrompt] from the UI to accept/reject.
 */
class TofuHostKeyVerifier(
    private val repository: HostRepository,
    private val onPrompt: (HostKeyDecision) -> Unit,
) : HostKeyVerifier {

    private val latch = AtomicReference<CountDownLatch?>(null)
    private val accepted = AtomicReference<Boolean?>(null)

    fun resolvePrompt(trust: Boolean) {
        accepted.set(trust)
        latch.getAndSet(null)?.countDown()
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val hostPort = "$hostname:$port"
        val type = KeyType.fromKey(key)
        val encoded = key.encoded
        val keyB64 = Base64.getEncoder().encodeToString(encoded)
        val fp = sha256Fingerprint(encoded)

        val existing = runBlocking { repository.getTrustedKey(hostPort) }
        if (existing == null) {
            return promptAndMaybeTrust(
                HostKeyDecision.Unknown(hostPort, type.toString(), fp, keyB64),
                hostPort, type.toString(), keyB64, fp,
            )
        }
        if (existing.keyBase64 == keyB64 && existing.algorithm == type.toString()) {
            return true
        }
        return promptAndMaybeTrust(
            HostKeyDecision.Changed(
                hostPort, type.toString(), fp, keyB64, existing.fingerprintSha256,
            ),
            hostPort, type.toString(), keyB64, fp,
        )
    }

    private fun promptAndMaybeTrust(
        decision: HostKeyDecision,
        hostPort: String,
        algorithm: String,
        keyB64: String,
        fp: String,
    ): Boolean {
        accepted.set(null)
        val cd = CountDownLatch(1)
        latch.set(cd)
        onPrompt(decision)
        val ok = cd.await(120, TimeUnit.SECONDS) && accepted.get() == true
        if (ok) {
            runBlocking {
                repository.trustKey(
                    HostKeyEntity(
                        hostPort = hostPort,
                        algorithm = algorithm,
                        keyBase64 = keyB64,
                        fingerprintSha256 = fp,
                    ),
                )
            }
        }
        return ok
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
        mutableListOf()

    companion object {
        fun sha256Fingerprint(keyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            return "SHA256:" + Base64.getEncoder().encodeToString(digest).trimEnd('=')
        }
    }
}
