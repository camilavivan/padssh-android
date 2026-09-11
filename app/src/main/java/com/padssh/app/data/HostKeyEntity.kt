package com.padssh.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** TOFU trusted host keys: hostname:port -> algorithm + base64 key */
@Entity(tableName = "host_keys")
data class HostKeyEntity(
    @PrimaryKey val hostPort: String,
    val algorithm: String,
    val keyBase64: String,
    val fingerprintSha256: String,
    val trustedAt: Long = System.currentTimeMillis(),
)
