package com.bytedance.zgx.pocketmind.storage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZvecNativeStoreDeviceTest {
    @Test
    fun nativeVectorIndexRoundTripsAndPersistsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "zvec-native-device-test").apply {
            deleteRecursively()
            mkdirs()
        }

        val status = ZvecNativeStore.probe()
        assertTrue(status.detail, status.available)

        ZvecNativeStore.openVectorIndex(root).getOrThrow().use { index ->
            index.upsert(record("large-magnitude-bad-angle", vector(0, 1f, 1, 10f)))
            index.upsert(record("best-angle", vector(0, 1f)))
            index.flush()

            val firstHit = index.query(LocalVectorQuery(embedding = vector(0, 1f), topK = 1)).single()
            assertEquals("best-angle", firstHit.record.id)
            assertEquals(2, index.count())
        }

        ZvecNativeStore.openVectorIndex(root).getOrThrow().use { reopened ->
            assertEquals(2, reopened.count())
            assertEquals(
                "best-angle",
                reopened.query(LocalVectorQuery(embedding = vector(0, 1f), topK = 1)).single().record.id,
            )
            assertTrue(reopened.delete("memory", "best-angle"))
            assertEquals(1, reopened.count())
        }

        root.deleteRecursively()
    }

    private fun record(id: String, embedding: FloatArray): LocalVectorRecord =
        LocalVectorRecord(
            domain = "memory",
            id = id,
            modelId = "gemma-embedding",
            sourceHash = "hash-$id",
            dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
            privacy = "LocalOnly",
            type = "Preference",
            updatedAtMillis = 1L,
            embedding = embedding,
        )

    private fun vector(
        index: Int,
        value: Float,
        secondIndex: Int? = null,
        secondValue: Float = 0f,
    ): FloatArray =
        FloatArray(LocalStorageCollections.EMBEDDING_DIMENSION).also {
            it[index] = value
            if (secondIndex != null) it[secondIndex] = secondValue
        }
}
