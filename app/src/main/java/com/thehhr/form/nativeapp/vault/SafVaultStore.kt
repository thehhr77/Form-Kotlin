package com.thehhr.form.nativeapp.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class SafVaultStore(private val context: Context, private val treeUri: Uri) : VaultStore {
    private val resolver get() = context.contentResolver
    private val rootUri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun children(): List<Pair<VaultEntryInfo, Uri>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Cannot read selected folder")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(VaultEntryInfo(it.getString(1), it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (it.isNull(3)) null else it.getLong(3)) to DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(0)))
                }
            }
        }
    }

    private fun findEntry(fileName: String): Pair<VaultEntryInfo, Uri>? {
        val matches = children().filter { it.first.name == fileName }
        if (matches.size > 1) throw IOException("Duplicate $fileName entries found. Resolve them before continuing.")
        return matches.singleOrNull()
    }

    override suspend fun read(fileName: String): VaultReadResult {
        val entry = findEntry(fileName) ?: return VaultReadResult(null, null)
        if (entry.first.isDirectory) throw IOException("$fileName is a folder, not a Markdown file")
        if ((entry.first.sizeBytes ?: 0) > VaultFiles.MAX_FILE_BYTES) throw IOException("$fileName exceeds the 4 MB safety limit")
        val content = resolver.openInputStream(entry.second)?.use {
            val bytes = it.readNBytes(VaultFiles.MAX_FILE_BYTES.toInt() + 1)
            if (bytes.size > VaultFiles.MAX_FILE_BYTES) throw IOException("$fileName exceeds the 4 MB safety limit")
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } ?: throw IOException("Cannot read $fileName")
        return VaultReadResult(content, null)
    }

    override suspend fun write(fileName: String, content: String) {
        require(VaultFiles.isKnownVaultFile(fileName))
        val existing = findEntry(fileName)
        if (existing?.first?.isDirectory == true) throw IOException("$fileName is a folder")
        val uri = existing?.second ?: DocumentsContract.createDocument(resolver, rootUri, "text/markdown", fileName) ?: throw IOException("Cannot create $fileName")
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: throw IOException("Cannot save $fileName")
    }

    override suspend fun list(): List<VaultEntryInfo> = children().map { it.first }

    override suspend fun displayName(): String {
        return resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "Selected vault"
        } ?: "Selected vault"
    }
}
