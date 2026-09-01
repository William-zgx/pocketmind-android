package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.tool.RiskLevel
import org.json.JSONException
import org.json.JSONObject

/** Metadata trusted only after [ExternalSkillPackageValidator] succeeds. */
data class ExternalSkillPackageManifest(
    val packageId: String,
    val skillId: String,
    val version: String,
    val requiredTools: List<String>,
    val riskLevel: RiskLevel,
    val privacy: MessagePrivacy,
    val minAppVersion: String,
    val resourceSha256: Map<String, String>,
)

data class ExternalSkillPackageSignature(
    val publisher: String,
    val keyId: String,
    val signatureBase64: String,
)

class ExternalSkillPackageParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ExternalSkillPackageParser {
    fun parseManifest(json: String): ExternalSkillPackageManifest = parseJson("manifest.json", json) { root ->
        val requiredToolsArray = root.requireArray("requiredTools")
        val requiredTools = buildList {
            for (index in 0 until requiredToolsArray.length()) {
                add(requiredToolsArray.requireString(index, "requiredTools"))
            }
        }
        val resources = root.requireObject("resources")
        val resourceHashes = linkedMapOf<String, String>()
        resources.keys().asSequence().sorted().forEach { path ->
            resourceHashes[path] = resources.requireString(path)
        }
        ExternalSkillPackageManifest(
            packageId = root.requireString("packageId"),
            skillId = root.requireString("skillId"),
            version = root.requireString("version"),
            requiredTools = requiredTools,
            riskLevel = parseRisk(root.requireString("riskLevel")),
            privacy = parsePrivacyFailClosed(root.optString("privacy", "")),
            minAppVersion = root.requireString("minAppVersion"),
            resourceSha256 = resourceHashes,
        )
    }

    fun parseSignature(json: String): ExternalSkillPackageSignature = parseJson("signature.json", json) { root ->
        ExternalSkillPackageSignature(
            publisher = root.requireString("publisher"),
            keyId = root.requireString("keyId"),
            signatureBase64 = root.requireString("signatureBase64"),
        )
    }

    private fun parseRisk(value: String): RiskLevel =
        RiskLevel.entries.firstOrNull { it.name == value }
            ?: throw ExternalSkillPackageParseException("manifest.json has unknown riskLevel: $value")

    private fun parsePrivacyFailClosed(value: String): MessagePrivacy =
        MessagePrivacy.entries.firstOrNull { it.name == value } ?: MessagePrivacy.LocalOnly

    private inline fun <T> parseJson(label: String, json: String, block: (JSONObject) -> T): T = try {
        block(JSONObject(json))
    } catch (error: ExternalSkillPackageParseException) {
        throw error
    } catch (error: JSONException) {
        throw ExternalSkillPackageParseException("Invalid $label: ${error.message}", error)
    }

    private fun JSONObject.requireString(name: String): String {
        if (!has(name) || isNull(name)) {
            throw ExternalSkillPackageParseException("Missing required string: $name")
        }
        val value = opt(name)
        if (value !is String || value.isBlank()) {
            throw ExternalSkillPackageParseException("Field $name must be a non-blank string")
        }
        return value
    }

    private fun JSONObject.requireObject(name: String): JSONObject =
        optJSONObject(name) ?: throw ExternalSkillPackageParseException("Field $name must be an object")

    private fun JSONObject.requireArray(name: String) =
        optJSONArray(name) ?: throw ExternalSkillPackageParseException("Field $name must be an array")

    private fun org.json.JSONArray.requireString(index: Int, field: String): String {
        val value = opt(index)
        if (value !is String || value.isBlank()) {
            throw ExternalSkillPackageParseException("Field $field[$index] must be a non-blank string")
        }
        return value
    }
}
