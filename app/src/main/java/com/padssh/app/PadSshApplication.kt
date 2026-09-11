package com.padssh.app

import android.app.Application
import com.padssh.app.data.AppDatabase
import com.padssh.app.data.HostRepository
import com.padssh.app.ssh.SshManager

class PadSshApplication : Application() {
    lateinit var repository: HostRepository
        private set
    lateinit var sshManager: SshManager
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = HostRepository(db)
        sshManager = SshManager(repository)
    }
}
