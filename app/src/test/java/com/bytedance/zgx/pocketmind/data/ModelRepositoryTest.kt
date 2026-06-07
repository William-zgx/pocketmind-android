package com.bytedance.zgx.pocketmind.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun customDownloadSourceRejectsHttpsNonLiteRtLmPath() {
        assertNull(createCustomModelDownloadSource("https://models.example.com/model.bin"))
        assertNull(createCustomModelDownloadSource("https://models.example.com/model.gguf"))
        assertNull(createCustomModelDownloadSource("https://models.example.com/download/"))
        assertNull(createCustomModelDownloadSource("https://models.example.com"))
    }

    @Test
    fun modelDownloadSourceVerifiedSha256RejectsWrongSize() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length() + 1L,
                expectedSha256 = null,
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isFailure)
            assertEquals("模型文件大小不匹配", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun modelDownloadSourceVerifiedSha256RejectsHashMismatch() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length(),
                expectedSha256 = "0".repeat(64),
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isFailure)
            assertEquals("模型校验失败，请重新下载", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun modelDownloadSourceVerifiedSha256AcceptsMatchingCustomFileWithoutHash() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length(),
                expectedSha256 = null,
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }
    }

    private fun withTempModelFile(content: String, block: (File) -> Unit) {
        val file = File.createTempFile("pocketmind-model", ".litertlm")
        try {
            file.writeText(content, Charsets.UTF_8)
            block(file)
        } finally {
            file.delete()
        }
    }
}
