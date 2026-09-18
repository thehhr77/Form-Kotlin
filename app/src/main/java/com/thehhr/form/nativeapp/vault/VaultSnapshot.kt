package com.thehhr.form.nativeapp.vault

data class VaultSnapshot(
    val folderName: String,
    val uri: String,
    val files: Map<String, String>,
    val warnings: List<String>,
    val counts: Map<String, Int> = emptyMap(),
    val info: Map<String, String> = emptyMap()
) {
    fun file(fileName: String): String? = files[fileName]

    fun isEmptyFolderSnapshot(): Boolean = files.values.all { it.isEmpty() }
}
