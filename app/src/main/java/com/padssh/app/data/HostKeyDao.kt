package com.padssh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HostKeyDao {
    @Query("SELECT * FROM host_keys WHERE hostPort = :hostPort LIMIT 1")
    suspend fun get(hostPort: String): HostKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: HostKeyEntity)

    @Query("DELETE FROM host_keys WHERE hostPort = :hostPort")
    suspend fun delete(hostPort: String)
}
