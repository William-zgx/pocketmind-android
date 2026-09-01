package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ExternalSkillPackageValidationException(message: String) : IllegalArgumentException(message)

class ExternalSkillPackageValidator(
    frozenToolSpecs: Collection<ToolSpec>,
    private val currentAppVersion: String,
) {
    private val toolSpecsByName = frozenToolSpecs.associateBy { it.name }

    fun validate(manifest: ExternalSkillPackageManifest, packageDirectory: Path) {
        requireMatch(PACKAGE_ID_REGEX, manifest.packageId, "packageId")
        requireMatch(SKILL_ID_REGEX, manifest.skillId, "skillId")
        parseVersion(manifest.version, "version")
        val minimumVersion = parseVersion(manifest.minAppVersion, "minAppVersion")
        val appVersion = parseVersion(currentAppVersion, "current app version")
        reject(appVersion < minimumVersion) {
            "Package requires app version ${manifest.minAppVersion}, current version is $currentAppVersion"
        }
        reject(manifest.requiredTools.isEmpty()) { "requiredTools must not be empty" }
        reject(manifest.requiredTools.size != manifest.requiredTools.distinct().size) {
            "requiredTools must not contain duplicates"
        }
        val unknownTools = manifest.requiredTools.filterNot(toolSpecsByName::containsKey)
        reject(unknownTools.isNotEmpty()) {
            "requiredTools contains tool(s) outside the frozen ToolSpec set: ${unknownTools.sorted().joinToString()}"
        }
        val highestToolRisk = manifest.requiredTools
            .mapNotNull(toolSpecsByName::get)
            .maxByOrNull { it.riskLevel.ordinal }
            ?.riskLevel
            ?: RiskLevel.LowReadOnly
        reject(manifest.riskLevel.ordinal < highestToolRisk.ordinal) {
            "External package risk ${manifest.riskLevel} is lower than required tool risk $highestToolRisk"
        }
        validateResourceHashes(manifest, packageDirectory)
    }

    private fun validateResourceHashes(manifest: ExternalSkillPackageManifest, packageDirectory: Path) {
        reject(manifest.resourceSha256.isEmpty()) { "resource SHA-256 map must not be empty" }
        val actualPayloadFiles = Files.walk(packageDirectory).use { paths ->
            paths.filter(Files::isRegularFile)
                .map(packageDirectory::relativize)
                .map { it.toString().replace(java.io.File.separatorChar, '/') }
                .filter { it != MANIFEST_FILE && it != SIGNATURE_FILE }
                .toList()
                .toSet()
        }
        val declaredPaths = manifest.resourceSha256.keys
        reject(actualPayloadFiles != declaredPaths) {
            val missingHashes = actualPayloadFiles - declaredPaths
            val missingFiles = declaredPaths - actualPayloadFiles
            buildString {
                append("Resource hash manifest does not match package payload")
                if (missingHashes.isNotEmpty()) append("; missing hashes: ${missingHashes.sorted().joinToString()}")
                if (missingFiles.isNotEmpty()) append("; missing files: ${missingFiles.sorted().joinToString()}")
            }
        }
        manifest.resourceSha256.forEach { (relativePath, expectedHash) ->
            reject(!SHA_256_REGEX.matches(expectedHash)) {
                "Invalid SHA-256 for resource $relativePath"
            }
            val resource = packageDirectory.resolve(relativePath).normalize()
            reject(!resource.startsWith(packageDirectory.normalize()) || !Files.isRegularFile(resource)) {
                "Invalid resource path: $relativePath"
            }
            val actualHash = sha256(resource)
            reject(!actualHash.equals(expectedHash, ignoreCase = true)) {
                "SHA-256 mismatch for resource $relativePath"
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun requireMatch(regex: Regex, value: String, label: String) {
        reject(!regex.matches(value)) { "Invalid $label: $value" }
    }

    private fun parseVersion(value: String, label: String): ComparableVersion {
        val match = VERSION_REGEX.matchEntire(value)
            ?: throw ExternalSkillPackageValidationException("Invalid $label: $value")
        return ComparableVersion(match.groupValues[1].split('.').map(String::toInt))
    }

    private inline fun reject(condition: Boolean, message: () -> String) {
        if (condition) throw ExternalSkillPackageValidationException(message())
    }

    private data class ComparableVersion(val parts: List<Int>) : Comparable<ComparableVersion> {
        override fun compareTo(other: ComparableVersion): Int {
            val count = maxOf(parts.size, other.parts.size)
            repeat(count) { index ->
                val comparison = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
                if (comparison != 0) return comparison
            }
            return 0
        }
    }

    private companion object {
        val PACKAGE_ID_REGEX = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
        val SKILL_ID_REGEX = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        val VERSION_REGEX = Regex("(0|[1-9]\\d*(?:\\.(?:0|[1-9]\\d*)){0,3})(?:-[0-9A-Za-z.-]+)?")
        val SHA_256_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}
