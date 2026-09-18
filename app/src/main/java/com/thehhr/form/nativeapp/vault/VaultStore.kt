package com.thehhr.form.nativeapp.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface VaultStore {
    suspend fun read(fileName: String): VaultReadResult
    suspend fun write(fileName: String, content: String)
    suspend fun list(): List<VaultEntryInfo>
    suspend fun displayName(): String
}

data class VaultReadResult(
    val content: String?,
    val error: String?
) {
    val isError: Boolean get() = error != null
}

data class VaultEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?
)

class VaultLock {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
