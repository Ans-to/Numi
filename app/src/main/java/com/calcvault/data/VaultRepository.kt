package com.calcvault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.calcvault.core.ChunkCipher
import com.calcvault.core.ImportTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import javax.crypto.spec.SecretKeySpec

/** Names, folders, hashes, media sizes, and per-file keys are all in the encrypted atomic catalog. */
data class VaultItem(val id: String, val name: String, val mime: String, val size: Long, val added: Long,
                     val folder: String?, internal val key: String, internal val digest: String) {
    val category get() = when { mime.startsWith("image/") -> "Photos"; mime.startsWith("video/") -> "Videos"; else -> "Files" }
}
data class Folder(val id: String, val name: String)
data class Catalog(val items: List<VaultItem> = emptyList(), val folders: List<Folder> = emptyList())
class VaultRepository(private val context: Context, private val secrets: SecretStore) {
    private val root = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val guard = Any()
    @Volatile private var generation = 0
    @Volatile var unlocked = false; private set
    private var catalog = Catalog()
    var recoveryWarning: String? = null; private set
    private val readers = mutableSetOf<SecureReader>()
    fun lock() {
        val close: List<SecureReader>
        synchronized(guard) { unlocked = false; generation++; catalog = Catalog(); close = readers.toList(); readers.clear() }
        close.forEach { runCatching { it.close() } }
    }
    fun unlock(): Catalog = synchronized(guard) {
        val bytes = secrets.read("catalog")
        val loaded = if (bytes == null) {
            check(root.listFiles().orEmpty().none { it.extension == "cv" }) { "Vault index is missing; encrypted files were preserved. Do not clear app storage." }
            // Persist the empty index before any import can start. A crash during the first
            // import must leave an empty committed catalog, not look like a lost index.
            Catalog().also { save(it) }
        } else try { decode(JSONObject(String(bytes, Charsets.UTF_8))) } finally { bytes.fill(0) }
        catalog = loaded; unlocked = true; generation++
        val ids = loaded.items.map { it.id }.toSet()
        val orphaned = root.listFiles().orEmpty().count { it.extension == "cv" && it.nameWithoutExtension !in ids }
        val missing = loaded.items.count { !File(root, "${it.id}.cv").isFile }
        recoveryWarning = if (orphaned > 0 || missing > 0) {
            "$orphaned unindexed encrypted files and $missing unavailable indexed files were found. " +
                "An interrupted operation or damaged storage may be responsible. No indexed data was discarded. " +
                "Unindexed files may lack recoverable keys. Keep your originals; do not clear app data while other vault copies are needed."
        } else null
        // Only uncommitted encrypted scratch files are discarded. Orphan .cv files are preserved.
        root.listFiles()?.filter { it.extension == "partial" }?.forEach { it.delete() }
        loaded
    }
    fun snapshot(): Catalog = synchronized(guard) { check(unlocked); catalog }
    private fun save(next: Catalog) {
        val bytes = encode(next).toString().toByteArray()
        try { secrets.write("catalog", bytes); catalog = next } finally { bytes.fill(0) }
    }
    fun import(uri: Uri, folder: String?): Boolean {
        val ticket = synchronized(guard) { check(unlocked); generation }
        val resolver = context.contentResolver
        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported file"
        val sanitized = rawName.filterNot { it.isISOControl() || it == '/' || it == '\\' }.take(160).trim()
        val name = sanitized.takeIf(ImportTransaction::validName) ?: "Imported file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val prepared = checkNotNull(resolver.openInputStream(uri)) { "Cannot read the selected file" }.use {
            ImportTransaction.prepare(root, it) { !unlocked || generation != ticket || Thread.currentThread().isInterrupted }
        }
        try {
            // Persist the ciphertext directory entry before publishing it in the catalog.
            val fd = android.system.Os.open(root.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
            synchronized(guard) {
                check(unlocked && generation == ticket) { "Vault locked during import. Original is unchanged." }
                if (catalog.items.any { it.digest == prepared.digest.b64() }) { prepared.file.delete(); return false }
                val item = VaultItem(prepared.id, name, mime, prepared.size, System.currentTimeMillis(),
                    folder?.takeIf { f -> catalog.folders.any { it.id == f } }, prepared.key.encoded.b64(), prepared.digest.b64())
                save(catalog.copy(items = catalog.items + item))
            }
        } catch (e: Exception) {
            // A failed metadata commit can have an ambiguous durability outcome. Preserve ciphertext,
            // rather than risk deleting a file referenced by a successfully committed catalog.
            throw e
        }
        return true
    }
    fun rename(id: String, name: String) = synchronized(guard) {
        check(unlocked); require(ImportTransaction.validName(name)) { "Use a valid name (1–160 characters, no slashes)" }
        require(catalog.items.any { it.id == id })
        save(catalog.copy(items = catalog.items.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }
    fun createFolder(name: String) = synchronized(guard) {
        check(unlocked); require(ImportTransaction.validName(name)) { "Use a valid folder name" }
        require(catalog.folders.none { it.name.equals(name.trim(), true) }) { "Folder already exists" }
        save(catalog.copy(folders = catalog.folders + Folder(java.util.UUID.randomUUID().toString(), name.trim())))
    }
    fun move(ids: Set<String>, folder: String?) = synchronized(guard) {
        check(unlocked); require(folder == null || catalog.folders.any { it.id == folder })
        save(catalog.copy(items = catalog.items.map { if (it.id in ids) it.copy(folder = folder) else it }))
    }
    fun delete(ids: Set<String>) = synchronized(guard) {
        check(unlocked)
        val deleting = catalog.items.filter { it.id in ids }
        // Commit metadata/key removal first. A crash leaves inaccessible ciphertext, never a valid missing entry.
        save(catalog.copy(items = catalog.items.filterNot { it.id in ids }))
        readers.filter { it.item.id in ids }.toList().forEach { it.close() }
        val failures = deleting.count { val file = File(root, "${it.id}.cv"); file.exists() && !file.delete() }
        failures
    }
    fun reader(item: VaultItem): SecureReader = synchronized(guard) {
        check(unlocked && catalog.items.any { it.id == item.id }) { "Vault is locked" }
        val reader = ChunkCipher.Reader(File(root, "${item.id}.cv"), SecretKeySpec(item.key.unbase64(), "AES"), item.id, item.size)
        SecureReader(item, reader, generation).also { readers.add(it) }
    }
    inner class SecureReader(val item: VaultItem, private val delegate: ChunkCipher.Reader, private val ticket: Int) : Closeable {
        fun read(position: Long, target: ByteArray, offset: Int, size: Int): Int {
            check(unlocked && generation == ticket) { "Vault is locked" }
            val count = delegate.read(position, target, offset, size)
            if (!unlocked || generation != ticket) {
                if (count > 0) target.fill(0, offset, offset + count)
                throw java.io.IOException("Vault is locked")
            }
            return count
        }
        fun stream(): InputStream = object : InputStream() {
            private var position = 0L
            override fun read(): Int { val b = ByteArray(1); return if (read(b) == -1) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int = this@SecureReader.read(position, b, off, len).also { if (it > 0) position += it }
            override fun close() = this@SecureReader.close()
        }
        override fun close() { delegate.close(); synchronized(guard) { readers.remove(this) } }
    }
    private fun encode(c: Catalog) = JSONObject().put("version", 1)
        .put("items", JSONArray().apply { c.items.forEach { i -> put(JSONObject().put("id", i.id).put("name", i.name)
            .put("mime", i.mime).put("size", i.size).put("added", i.added).put("folder", i.folder)
            .put("key", i.key).put("digest", i.digest)) } })
        .put("folders", JSONArray().apply { c.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    private fun decode(json: JSONObject): Catalog {
        require(json.getInt("version") == 1)
        fun validId(id: String): String = java.util.UUID.fromString(id).toString().also { require(it == id) }
        val array = json.getJSONArray("items")
        val folders = json.getJSONArray("folders")
        return Catalog((0 until array.length()).map { n -> array.getJSONObject(n).let {
            VaultItem(validId(it.getString("id")), it.getString("name"), it.getString("mime"), it.getLong("size"), it.getLong("added"),
                if (it.isNull("folder")) null else validId(it.getString("folder")), it.getString("key"), it.getString("digest"))
        } }, (0 until folders.length()).map { folders.getJSONObject(it).let { f -> Folder(validId(f.getString("id")), f.getString("name")) } })
    }
}
