package com.bytedance.zgx.solin.skill.`package`

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ExternalSkillPackageExtractionException(message: String) : IllegalArgumentException(message)

data class ExternalSkillPackageLimits(
    val maxTotalBytes: Long = 8L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 2L * 1024L * 1024L,
    val maxFileCount: Int = 128,
)

class SafeExternalSkillPackageExtractor(
    private val limits: ExternalSkillPackageLimits = ExternalSkillPackageLimits(),
) {
    /**
     * The JDK ZipEntry API does not expose Unix mode. We therefore inspect the ZIP central
     * directory separately and fail closed for Unix symlinks, devices, directories, or files
     * with executable bits. ZIP64 central directories are intentionally unsupported in V1.
     */
    fun extract(zipFile: Path, stagingDirectory: Path): Set<String> {
        Files.createDirectories(stagingDirectory)
        val unixModes = ZipUnixModeReader.read(zipFile)
        val extracted = linkedSetOf<String>()
        var totalBytes = 0L
        Files.newInputStream(zipFile).use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedName = validateEntry(entry, unixModes[entry.name])
                    if (!extracted.add(normalizedName)) {
                        fail("Duplicate ZIP entry: $normalizedName")
                    }
                    if (extracted.size > limits.maxFileCount) fail("ZIP exceeds maximum file count")
                    if (entry.size > limits.maxSingleFileBytes) fail("ZIP entry exceeds single-file limit: $normalizedName")
                    val destination = stagingDirectory.resolve(normalizedName).normalize()
                    if (!destination.startsWith(stagingDirectory.normalize())) fail("ZIP entry escapes staging: $normalizedName")
                    Files.createDirectories(destination.parent)
                    Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).use { output ->
                        val firstBytes = ByteArray(2)
                        var firstByteCount = 0
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (firstByteCount < 2) {
                                val copied = minOf(2 - firstByteCount, count)
                                buffer.copyInto(firstBytes, firstByteCount, 0, copied)
                                firstByteCount += copied
                            }
                            fileBytes += count
                            totalBytes += count
                            if (fileBytes > limits.maxSingleFileBytes) fail("ZIP entry exceeds single-file limit: $normalizedName")
                            if (totalBytes > limits.maxTotalBytes) fail("ZIP exceeds total uncompressed byte limit")
                            output.write(buffer, 0, count)
                        }
                        if (firstByteCount == 2 && firstBytes[0] == '#'.code.toByte() && firstBytes[1] == '!'.code.toByte()) {
                            fail("Executable/script resource is forbidden: $normalizedName")
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (MANIFEST_FILE !in extracted) fail("ZIP is missing $MANIFEST_FILE")
        if (SIGNATURE_FILE !in extracted) fail("ZIP is missing $SIGNATURE_FILE")
        if (INSTRUCTIONS_FILE !in extracted) fail("ZIP is missing $INSTRUCTIONS_FILE")
        return extracted
    }

    private fun validateEntry(entry: ZipEntry, unixMode: Int?): String {
        val name = entry.name
        if (name.isBlank()) fail("ZIP entry name must not be blank")
        if (entry.isDirectory || name.endsWith('/')) fail("Explicit directory entries are unsupported: $name")
        if (name.startsWith('/') || WINDOWS_ABSOLUTE.matches(name)) fail("Absolute ZIP path is forbidden: $name")
        if ('\\' in name) fail("Backslash ZIP path is forbidden: $name")
        val segments = name.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) fail("Unsafe ZIP path is forbidden: $name")
        if (!isAllowedPath(name)) fail("ZIP entry is outside the package allowlist: $name")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in FORBIDDEN_EXTENSIONS) fail("Forbidden executable/script extension: $name")
        unixMode?.let { mode ->
            val fileType = mode and UNIX_FILE_TYPE_MASK
            if (fileType != UNIX_REGULAR_FILE) fail("Non-regular Unix ZIP entry is forbidden: $name")
            if (mode and UNIX_EXECUTABLE_BITS != 0) fail("Executable Unix ZIP entry is forbidden: $name")
        }
        return name
    }

    private fun isAllowedPath(name: String): Boolean =
        name == MANIFEST_FILE ||
            name == INSTRUCTIONS_FILE ||
            name == SIGNATURE_FILE ||
            name.startsWith("schemas/") ||
            name.startsWith("resources/")

    private fun fail(message: String): Nothing = throw ExternalSkillPackageExtractionException(message)

    private companion object {
        val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:/.*")
        val FORBIDDEN_EXTENSIONS = setOf("dex", "class", "jar", "so", "sh", "py", "js", "mjs", "cjs")
        const val UNIX_FILE_TYPE_MASK = 0xF000
        const val UNIX_REGULAR_FILE = 0x8000
        const val UNIX_EXECUTABLE_BITS = 0x49
    }
}

internal const val MANIFEST_FILE = "manifest.json"
internal const val INSTRUCTIONS_FILE = "instructions.md"
internal const val SIGNATURE_FILE = "signature.json"

private object ZipUnixModeReader {
    fun read(zipFile: Path): Map<String, Int> {
        val bytes = Files.readAllBytes(zipFile)
        val endOffset = findEndOfCentralDirectory(bytes)
        val entryCount = unsignedShort(bytes, endOffset + 10)
        val centralSize = unsignedInt(bytes, endOffset + 12)
        val centralOffset = unsignedInt(bytes, endOffset + 16)
        if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            throw ExternalSkillPackageExtractionException("ZIP64 packages are unsupported in V1")
        }
        var cursor = centralOffset.toInt()
        val result = mutableMapOf<String, Int>()
        repeat(entryCount) {
            if (cursor + CENTRAL_HEADER_FIXED_SIZE > bytes.size || unsignedInt(bytes, cursor) != CENTRAL_HEADER_SIGNATURE) {
                throw ExternalSkillPackageExtractionException("Invalid ZIP central directory")
            }
            val madeByHost = bytes[cursor + 5].toInt() and 0xff
            val nameLength = unsignedShort(bytes, cursor + 28)
            val extraLength = unsignedShort(bytes, cursor + 30)
            val commentLength = unsignedShort(bytes, cursor + 32)
            val nameStart = cursor + CENTRAL_HEADER_FIXED_SIZE
            val nameEnd = nameStart + nameLength
            if (nameEnd > bytes.size) throw ExternalSkillPackageExtractionException("Invalid ZIP central entry name")
            val name = bytes.copyOfRange(nameStart, nameEnd).toString(Charsets.UTF_8)
            if (madeByHost == UNIX_HOST) {
                val externalAttributes = unsignedInt(bytes, cursor + 38)
                result[name] = (externalAttributes ushr 16).toInt()
            }
            cursor = nameEnd + extraLength + commentLength
        }
        return result
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = maxOf(0, bytes.size - MAX_EOCD_SEARCH)
        for (offset in bytes.size - EOCD_FIXED_SIZE downTo minimum) {
            if (unsignedInt(bytes, offset) == EOCD_SIGNATURE) return offset
        }
        throw ExternalSkillPackageExtractionException("ZIP end-of-central-directory record not found")
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw ExternalSkillPackageExtractionException("Truncated ZIP metadata")
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) throw ExternalSkillPackageExtractionException("Truncated ZIP metadata")
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private const val UNIX_HOST = 3
    private const val CENTRAL_HEADER_FIXED_SIZE = 46
    private const val EOCD_FIXED_SIZE = 22
    private const val MAX_EOCD_SEARCH = 65_557
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val EOCD_SIGNATURE = 0x06054b50L
}
