package com.calcvault.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import java.io.InputStream
import java.security.MessageDigest

/** Memory-only receipt, never an instruction to delete on startup or after process death. */
data class SourceRemovalCandidate(val uri: Uri, val itemId: String, val name: String, internal val digest: String)
enum class SourceRemovalResult(val help: String) {
    REMOVED("The provider confirmed removal of the original."),
    UNSUPPORTED("Original kept: this provider does not grant deletion. Remove it manually in Gallery or Files after checking the vault copy."),
    VERIFICATION_FAILED("No removal requested: the source changed, the vault copy is unavailable, or verification could not finish. Keep the original and check both copies."),
    INTERRUPTED("No removal requested: the vault locked or the operation was interrupted. Unlock and review again."),
    UNCONFIRMED("The provider did not confirm removal. Check Gallery or Files before trying again; the vault copy was retained.")
}

/** SAF only: never guess MediaStore IDs from photo-picker URIs, file names, or paths. */
class SourceRemoval(private val context: Context, private val vault: VaultRepository) {
    fun supportsRemoval(uri: Uri): Boolean = runCatching {
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) return@runCatching false
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) return@runCatching false
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
        } == true
    }.getOrDefault(false)

    private fun matches(input: InputStream, item: VaultItem, active: () -> Boolean): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        try {
            while (true) {
                check(active())
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > item.size) return false
                hash.update(buffer, 0, count)
            }
            return size == item.size && MessageDigest.isEqual(hash.digest(), item.digest.unbase64())
        } finally { buffer.fill(0) }
    }
    private fun verifiedCopy(item: VaultItem, active: () -> Boolean): Boolean =
        vault.reader(item).stream().use { matches(it, item, active) }

    fun prepare(uri: Uri, copy: ImportedCopy, active: () -> Boolean): SourceRemovalCandidate? {
        if (!supportsRemoval(uri) || !verifiedCopy(copy.item, active)) return null
        return SourceRemovalCandidate(uri, copy.item.id, copy.sourceName, copy.item.digest)
    }

    /** Caller must obtain a separate, explicit confirmation for these exact originals. */
    fun removeConfirmed(candidate: SourceRemovalCandidate, active: () -> Boolean): SourceRemovalResult {
        if (!active()) return SourceRemovalResult.INTERRUPTED
        if (!supportsRemoval(candidate.uri)) return SourceRemovalResult.UNSUPPORTED
        val safe = runCatching {
            val item = vault.snapshot().items.single { it.id == candidate.itemId && it.digest == candidate.digest }
            verifiedCopy(item, active) && context.contentResolver.openInputStream(candidate.uri)?.use { matches(it, item, active) } == true
        }.getOrDefault(false)
        if (!active()) return SourceRemovalResult.INTERRUPTED
        if (!safe) return SourceRemovalResult.VERIFICATION_FAILED
        // No automatic retries after an ambiguous provider result. Another process could
        // change the source after the last read; SAF offers no atomic compare-and-delete.
        return try {
            if (DocumentsContract.deleteDocument(context.contentResolver, candidate.uri)) SourceRemovalResult.REMOVED
            else SourceRemovalResult.UNCONFIRMED
        } catch (_: Exception) { SourceRemovalResult.UNCONFIRMED }
    }
}
