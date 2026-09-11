package com.padssh.app.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships a stripped/old JCA provider named "BC". sshj and Curve25519/X25519
 * need the full classpath [BouncyCastleProvider] instance instead.
 */
object SecurityProviders {
    @JvmStatic
    fun install() {
        val name = BouncyCastleProvider.PROVIDER_NAME
        val existing = Security.getProvider(name)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(name)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
