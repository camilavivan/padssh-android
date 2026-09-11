package com.padssh.app.data

import kotlinx.coroutines.flow.Flow

class HostRepository(private val db: AppDatabase) {
    private val hostDao = db.hostDao()
    private val keyDao = db.hostKeyDao()

    fun observeHosts(): Flow<List<HostEntity>> = hostDao.observeAll()

    suspend fun getHost(id: Long): HostEntity? = hostDao.getById(id)

    suspend fun saveHost(host: HostEntity): Long = hostDao.upsert(host)

    suspend fun deleteHost(host: HostEntity) = hostDao.delete(host)

    suspend fun getTrustedKey(hostPort: String): HostKeyEntity? = keyDao.get(hostPort)

    suspend fun trustKey(entity: HostKeyEntity) = keyDao.upsert(entity)

    suspend fun forgetKey(hostPort: String) = keyDao.delete(hostPort)
}
