package com.padssh.app.ssh

import com.hierynomus.sshj.transport.kex.DHGroups
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory
import com.hierynomus.sshj.transport.kex.ExtendedDHGroups
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.transport.kex.DHGexSHA1
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP

/**
 * SSH config for Android that omits Curve25519/X25519 key exchange.
 *
 * sshj's default KEX prefers curve25519-sha256, which uses JCA KeyAgreement("X25519")
 * via provider BC. Android's platform BC often lacks that algorithm, producing
 * "no such algorithm: X25519 for provider BC". Excluding those KEXes lets negotiation
 * use ECDH NIST curves and DH group exchange instead.
 */
class AndroidSshConfig : DefaultConfig() {
    override fun initKeyExchangeFactories() {
        // Same list as DefaultConfig, minus Curve25519SHA256.Factory / FactoryLibSsh.
        setKeyExchangeFactories(
            DHGexSHA256.Factory(),
            ECDHNistP.Factory521(),
            ECDHNistP.Factory384(),
            ECDHNistP.Factory256(),
            DHGexSHA1.Factory(),
            DHGroups.Group1SHA1(),
            DHGroups.Group14SHA1(),
            DHGroups.Group14SHA256(),
            DHGroups.Group15SHA512(),
            DHGroups.Group16SHA512(),
            DHGroups.Group17SHA512(),
            DHGroups.Group18SHA512(),
            ExtendedDHGroups.Group14SHA256AtSSH(),
            ExtendedDHGroups.Group15SHA256(),
            ExtendedDHGroups.Group15SHA256AtSSH(),
            ExtendedDHGroups.Group15SHA384AtSSH(),
            ExtendedDHGroups.Group16SHA256(),
            ExtendedDHGroups.Group16SHA384AtSSH(),
            ExtendedDHGroups.Group16SHA512AtSSH(),
            ExtendedDHGroups.Group18SHA512AtSSH(),
            ExtInfoClientFactory(),
        )
    }
}
