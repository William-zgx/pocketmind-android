package com.bytedance.zgx.pocketmind.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRepositoryTest {
    @Test
    fun customDownloadSourceAcceptsHttpsPublicUrl() {
        val source = createCustomModelDownloadSource(
            " https://models.example.com/releases/pocketmind-chat.litertlm?token=abc ",
        )

        assertNotNull(source)
        requireNotNull(source)
        assertEquals("自定义模型", source.title)
        assertEquals("pocketmind-chat.litertlm", source.fileName)
        assertEquals("https://models.example.com/releases/pocketmind-chat.litertlm?token=abc", source.downloadUrl)
        assertEquals(null, source.expectedSha256)
        assertEquals(null, source.modelId)
    }

    @Test
    fun customDownloadSourceAllowsHttpOnlyForLocalDebugHosts() {
        val localHosts = listOf(
            "http://localhost:8000/model.litertlm",
            "http://127.0.0.1:8000/model.litertlm",
            "http://[::1]:8000/model.litertlm",
            "http://10.0.2.2:8000/model.litertlm",
        )

        localHosts.forEach { url ->
            assertNotNull(url, createCustomModelDownloadSource(url))
        }
    }

    @Test
    fun customDownloadSourceRejectsPlainHttpPublicUrl() {
        assertNull(createCustomModelDownloadSource("http://models.example.com/model.litertlm"))
        assertNull(createCustomModelDownloadSource("http://192.168.1.12/model.litertlm"))
        assertNull(createCustomModelDownloadSource("http://example.com/model.bin"))
    }

    @Test
    fun customDownloadSourceRejectsMalformedUnsupportedOrCredentialUrls() {
        assertNull(createCustomModelDownloadSource(""))
        assertNull(createCustomModelDownloadSource("https:foo"))
        assertNull(createCustomModelDownloadSource("ftp://models.example.com/model.litertlm"))
        assertNull(createCustomModelDownloadSource("https://user:pass@models.example.com/model.litertlm"))
    }
}
