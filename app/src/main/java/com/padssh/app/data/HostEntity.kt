package com.padssh.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** null when using private key */
    val password: String? = null,
    /** OpenSSH/PEM private key text */
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
    val useKeyAuth: Boolean = false,
)
