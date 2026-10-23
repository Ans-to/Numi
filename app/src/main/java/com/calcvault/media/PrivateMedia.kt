package com.calcvault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.calcvault.data.VaultItem
import com.calcvault.data.VaultRepository
import java.io.Closeable

object PrivateMedia {
    fun image(repository: VaultRepository, item: VaultItem, edge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        repository.reader(item).stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
        return repository.reader(item).stream().use {
            checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        }
    }
    data class VideoPreview(val bitmap: Bitmap?, val durationMs: Long)
    fun video(repository: VaultRepository, item: VaultItem): VideoPreview {
        val retriever = MediaMetadataRetriever()
        val reader = repository.reader(item)
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun getSize() = item.size
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = reader.read(position, buffer, offset, size)
                override fun close() = reader.close()
            })
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 320)
            } else {
                // Android 8.0 lacks bounded thumbnail decoding. Avoid an unbounded full-resolution frame.
                null
            }
            return VideoPreview(frame, duration)
        } finally { retriever.release(); reader.close() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSource(private val repository: VaultRepository, private val item: VaultItem) : BaseDataSource(false) {
    private var reader: VaultRepository.SecureReader? = null
    private var position = 0L
    private var remaining = 0L
    private var spec: DataSpec? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        require(dataSpec.position <= item.size)
        reader = repository.reader(item)
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) item.size - position else minOf(dataSpec.length, item.size - position)
        spec = dataSpec
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = checkNotNull(reader).read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n > 0) { position += n; remaining -= n; bytesTransferred(n) }
        return n
    }
    override fun getUri() = spec?.uri
    override fun close() {
        reader?.close(); reader = null
        if (spec != null) { spec = null; transferEnded() }
    }
}

/** Seekable decrypted descriptor backed by authenticated memory reads, never a plaintext temp file. */
class PrivateDescriptor(context: Context, repository: VaultRepository, item: VaultItem) : Closeable {
    private val thread = HandlerThread("private-document").apply { start() }
    private val reader = repository.reader(item)
    val descriptor: ParcelFileDescriptor
    init {
        try {
            descriptor = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = item.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        try {
                            var total = 0
                            while (total < size) {
                                val n = reader.read(offset + total, data, total, size - total)
                                if (n < 0) break
                                total += n
                            }
                            return total
                        } catch (_: Exception) { throw android.system.ErrnoException("read", android.system.OsConstants.EIO) }
                    }
                    override fun onRelease() { reader.close() }
                }, Handler(thread.looper))
        } catch (e: Exception) { reader.close(); thread.quitSafely(); throw e }
    }
    override fun close() { descriptor.close(); reader.close(); thread.quitSafely() }
}
