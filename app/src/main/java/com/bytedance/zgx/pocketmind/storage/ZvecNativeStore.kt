package com.bytedance.zgx.pocketmind.storage

import java.io.Closeable
import java.io.File

data class ZvecNativeStatus(
    val available: Boolean,
    val detail: String,
    val version: String = "",
)

object ZvecNativeStore {
    private const val LIBRARY_NAME = "zvec_bridge"
    private const val DEFAULT_VECTOR_FILE = "${LocalStorageCollections.VECTORS}.zvec"

    private val loadResult: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { System.loadLibrary(LIBRARY_NAME) }
    }

    fun probe(): ZvecNativeStatus =
        loadResult.fold(
            onSuccess = {
                runCatching {
                    val version = nativeVersion()
                    ZvecNativeStatus(
                        available = true,
                        detail = "loaded $LIBRARY_NAME",
                        version = version,
                    )
                }.getOrElse { error ->
                    ZvecNativeStatus(
                        available = false,
                        detail = error.message ?: error::class.java.name,
                    )
                }
            },
            onFailure = { error ->
                ZvecNativeStatus(
                    available = false,
                    detail = error.message ?: error::class.java.name,
                )
            },
        )

    fun openVectorIndex(
        rootDir: File,
        fileName: String = DEFAULT_VECTOR_FILE,
        flushOnMutation: Boolean = false,
    ): Result<ZvecNativeVectorIndex> =
        runCatching {
            ensureLoaded()
            if (!rootDir.exists()) {
                check(rootDir.mkdirs()) { "failed to create zvec root: ${rootDir.absolutePath}" }
            }
            ZvecNativeVectorIndex(
                storageFile = File(rootDir, fileName),
                flushOnMutation = flushOnMutation,
            )
        }

    internal fun createVectorIndex(storageFile: File, dimension: Int): Long {
        ensureLoaded()
        return nativeCreate(storageFile.absolutePath, dimension)
    }

    internal fun close(handle: Long) {
        if (handle != 0L) nativeClose(handle)
    }

    internal fun flush(handle: Long) = nativeFlush(handle)

    internal fun upsert(handle: Long, record: LocalVectorRecord) {
        nativeUpsert(
            handle = handle,
            domain = record.domain,
            id = record.id,
            modelId = record.modelId,
            sourceHash = record.sourceHash,
            privacy = record.privacy,
            type = record.type,
            updatedAtMillis = record.updatedAtMillis,
            embedding = record.embedding,
        )
    }

    internal fun fetch(handle: Long, domain: String, id: String, modelId: String): ZvecNativeRecordRow? =
        nativeFetch(handle, domain, id, modelId)

    internal fun query(
        handle: Long,
        domain: String?,
        modelId: String?,
        type: String?,
        embedding: FloatArray,
        topK: Int,
    ): Array<ZvecNativeHitRow> =
        nativeQuery(handle, domain, modelId, type, embedding, topK)

    internal fun delete(handle: Long, domain: String, id: String, modelId: String?): Int =
        nativeDelete(handle, domain, id, modelId)

    internal fun deleteForModel(handle: Long, modelId: String, domain: String?): Int =
        nativeDeleteForModel(handle, modelId, domain)

    internal fun clear(handle: Long, domain: String?): Int =
        nativeClear(handle, domain)

    internal fun count(handle: Long, domain: String?): Int =
        nativeCount(handle, domain)

    private fun ensureLoaded() {
        loadResult.getOrElse { error ->
            throw UnsatisfiedLinkError(
                "Unable to load $LIBRARY_NAME: ${error.message ?: error::class.java.name}",
            ).also { it.initCause(error) }
        }
    }

    private external fun nativeVersion(): String
    private external fun nativeCreate(storagePath: String, dimension: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativeUpsert(
        handle: Long,
        domain: String,
        id: String,
        modelId: String,
        sourceHash: String,
        privacy: String,
        type: String,
        updatedAtMillis: Long,
        embedding: FloatArray,
    )
    private external fun nativeFetch(handle: Long, domain: String, id: String, modelId: String): ZvecNativeRecordRow?
    private external fun nativeQuery(
        handle: Long,
        domain: String?,
        modelId: String?,
        type: String?,
        embedding: FloatArray,
        topK: Int,
    ): Array<ZvecNativeHitRow>
    private external fun nativeDelete(handle: Long, domain: String, id: String, modelId: String?): Int
    private external fun nativeDeleteForModel(handle: Long, modelId: String, domain: String?): Int
    private external fun nativeClear(handle: Long, domain: String?): Int
    private external fun nativeCount(handle: Long, domain: String?): Int
}

class ZvecNativeVectorIndex(
    private val storageFile: File,
    private val flushOnMutation: Boolean = false,
) : LocalVectorIndex, Closeable {
    val rootDir: File = storageFile.parentFile ?: File(".")
    private val lock = Any()
    private var handle: Long = ZvecNativeStore.createVectorIndex(
        storageFile = storageFile,
        dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
    )
    private var dirty = false
    private var closed = false

    override fun upsert(record: LocalVectorRecord) {
        withHandle { nativeHandle ->
            ZvecNativeStore.upsert(nativeHandle, record)
            markDirty(nativeHandle)
        }
    }

    override fun fetch(domain: String, id: String, modelId: String): LocalVectorRecord? =
        withHandle { nativeHandle ->
            ZvecNativeStore.fetch(nativeHandle, domain, id, modelId)?.toLocalVectorRecord()
        }

    override fun query(query: LocalVectorQuery): List<LocalVectorHit> =
        withHandle { nativeHandle ->
            ZvecNativeStore.query(
                handle = nativeHandle,
                domain = query.domain,
                modelId = query.modelId,
                type = query.type,
                embedding = query.embedding,
                topK = query.topK.coerceAtLeast(0),
            ).map { hit ->
                LocalVectorHit(
                    record = hit.record.toLocalVectorRecord(),
                    score = hit.score,
                )
            }
        }

    override fun delete(domain: String, id: String, modelId: String?): Boolean =
        withHandle { nativeHandle ->
            val deleted = ZvecNativeStore.delete(nativeHandle, domain, id, modelId)
            if (deleted > 0) markDirty(nativeHandle)
            deleted > 0
        }

    override fun deleteForModel(modelId: String, domain: String?): Int =
        withHandle { nativeHandle ->
            val deleted = ZvecNativeStore.deleteForModel(nativeHandle, modelId, domain)
            if (deleted > 0) markDirty(nativeHandle)
            deleted
        }

    override fun clear(domain: String?): Int =
        withHandle { nativeHandle ->
            val deleted = ZvecNativeStore.clear(nativeHandle, domain)
            if (deleted > 0) markDirty(nativeHandle)
            deleted
        }

    fun count(domain: String? = null): Int =
        withHandle { nativeHandle -> ZvecNativeStore.count(nativeHandle, domain) }

    fun flush() {
        withHandle { nativeHandle ->
            if (dirty) {
                ZvecNativeStore.flush(nativeHandle)
                dirty = false
            }
        }
    }

    override fun close() {
        val closeHandle: Long
        val shouldFlush: Boolean
        synchronized(lock) {
            if (closed) return
            closeHandle = handle
            shouldFlush = dirty
            handle = 0L
            dirty = false
            closed = true
        }
        try {
            if (shouldFlush) {
                ZvecNativeStore.flush(closeHandle)
            }
        } finally {
            ZvecNativeStore.close(closeHandle)
        }
    }

    private inline fun <T> withHandle(block: (Long) -> T): T =
        synchronized(lock) {
            check(!closed && handle != 0L) { "zvec native vector index is closed" }
            block(handle)
        }

    private fun markDirty(nativeHandle: Long) {
        dirty = true
        if (flushOnMutation) {
            ZvecNativeStore.flush(nativeHandle)
            dirty = false
        }
    }
}

internal data class ZvecNativeRecordRow(
    val domain: String,
    val id: String,
    val modelId: String,
    val sourceHash: String,
    val privacy: String,
    val type: String,
    val updatedAtMillis: Long,
    val embedding: FloatArray,
) {
    fun toLocalVectorRecord(): LocalVectorRecord =
        LocalVectorRecord(
            domain = domain,
            id = id,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
            privacy = privacy,
            type = type,
            updatedAtMillis = updatedAtMillis,
            embedding = embedding.copyOf(),
        )
}

internal data class ZvecNativeHitRow(
    val record: ZvecNativeRecordRow,
    val score: Float,
)
