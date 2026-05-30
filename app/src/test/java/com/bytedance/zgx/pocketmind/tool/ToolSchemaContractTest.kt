package com.bytedance.zgx.pocketmind.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaContractTest {
    private val registry = ToolRegistry()

    @Test
    fun allToolSchemasDriveRegistryValidation() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val propertyNames = properties.keysSet()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = requiredProperties.associateWith { propertyName ->
                validValueFor(properties.getJSONObject(propertyName))
            }

            assertEquals("${spec.name} schema must be an object", "object", schema.getString("type"))
            assertFalse("${spec.name} schema must be closed", schema.optBoolean("additionalProperties", true))
            assertTrue(
                "${spec.name} required properties must be declared in properties",
                propertyNames.containsAll(requiredProperties),
            )

            assertNull(
                "${spec.name} minimal arguments derived from schema should validate",
                registry.validate(
                    ToolRequest(
                        id = "valid-${spec.name}",
                        toolName = spec.name,
                        arguments = minimalValidArguments,
                        reason = "schema contract",
                    ),
                ),
            )

            val extraArgumentRejection = registry.validate(
                ToolRequest(
                    id = "extra-${spec.name}",
                    toolName = spec.name,
                    arguments = minimalValidArguments + ("__unexpected" to "value"),
                    reason = "schema contract",
                ),
            )
            assertNotNull("${spec.name} should reject arguments not declared in schema", extraArgumentRejection)

            requiredProperties.forEach { propertyName ->
                val missingRejection = registry.validate(
                    ToolRequest(
                        id = "missing-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments - propertyName,
                        reason = "schema contract",
                    ),
                )
                assertNotNull("${spec.name} should reject missing required $propertyName", missingRejection)

                val blankRejection = registry.validate(
                    ToolRequest(
                        id = "blank-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments + (propertyName to " "),
                        reason = "schema contract",
                    ),
                )
                assertNotNull("${spec.name} should reject blank required $propertyName", blankRejection)
            }
        }
    }

    @Test
    fun stringPatternsDeclaredInSchemasAreEnforcedByRegistry() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = requiredProperties.associateWith { propertyName ->
                validValueFor(properties.getJSONObject(propertyName))
            }

            properties.keysSet().forEach { propertyName ->
                val property = properties.optJSONObject(propertyName) ?: return@forEach
                val pattern = property.optStringOrNull("pattern") ?: return@forEach
                val invalidValue = firstInvalidValueFor(pattern)
                val rejection = registry.validate(
                    ToolRequest(
                        id = "pattern-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments + (propertyName to invalidValue),
                        reason = "schema contract",
                    ),
                )

                assertNotNull("${spec.name} should enforce pattern for $propertyName", rejection)
            }
        }
    }

    @Test
    fun typedAndEnumeratedConstraintsAreEnforcedByRegistry() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = requiredProperties.associateWith { propertyName ->
                validValueFor(properties.getJSONObject(propertyName))
            }

            properties.keysSet().forEach { propertyName ->
                val property = properties.optJSONObject(propertyName) ?: return@forEach
                val invalidValue = invalidValueFor(property)
                    ?: return@forEach
                val candidate = minimalValidArguments + (propertyName to invalidValue)
                val rejection = registry.validate(
                    ToolRequest(
                        id = "typed-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = candidate,
                        reason = "schema contract",
                    ),
                )

                assertNotNull(
                    "${spec.name} should reject invalid typed/enum value for $propertyName",
                    rejection,
                )
            }
        }
    }

    private fun validValueFor(property: JSONObject): String {
        val enum = property.optStringSetOrNull("enum")
        if (enum != null && enum.isNotEmpty()) {
            return enum.first()
        }

        val type = property.optStringOrNull("type") ?: "string"
        if (type == "boolean") {
            return "true"
        }
        if (type == "integer" || type == "number") {
            val minimum = property.optDoubleOrNull("minimum")
            val maximum = property.optDoubleOrNull("maximum")
            val exclusiveMinimum = property.optDoubleOrNull("exclusiveMinimum")
            val exclusiveMaximum = property.optDoubleOrNull("exclusiveMaximum")
            return when {
                minimum != null -> {
                    val value = if (exclusiveMinimum != null) minimum + 1 else minimum
                    if (type == "integer") value.toLong().toString() else value.toString()
                }

                maximum != null -> {
                    val value = if (exclusiveMaximum != null) maximum - 1 else maximum
                    if (type == "integer") value.toLong().toString() else value.toString()
                }

                else -> {
                    if (type == "integer") {
                        "1"
                    } else {
                        "1.0"
                    }
                }
            }
        }
        if (type == "array") {
            return "[]"
        }
        if (type == "object") {
            return "{}"
        }

        val pattern = property.optStringOrNull("pattern")
        if (pattern != null) {
            return listOf("1", "10", "abc", "value", "http://x", "https://x", "mailto:a@b.com", "tel:123", "geo:0,0")
                .firstOrNull { Regex(pattern).matches(it) }
                ?: error("No test fixture value matches pattern $pattern")
        }

        val minLength = property.optIntOrNull("minLength") ?: 1
        return "x".repeat(minLength.coerceAtLeast(1))
    }

    private fun firstInvalidValueFor(pattern: String): String =
        listOf("", " ", "0", "-1", "1.5", "abc", "http://", "geo:", "mailto:", "tel:")
            .firstOrNull { !Regex(pattern).matches(it) }
            ?: error("No invalid fixture value for pattern $pattern")

    private fun invalidValueFor(property: JSONObject): String? {
        val enum = property.optStringSetOrNull("enum")
        if (enum != null && enum.isNotEmpty()) {
            return listOf("invalid", "_", "", "0", "false", "unsupported").firstOrNull {
                it !in enum
            }
        }

        return when (property.optStringOrNull("type")) {
            "integer", "number" -> {
                val minimum = property.optDoubleOrNull("minimum")
                val maximum = property.optDoubleOrNull("maximum")
                val exclusiveMinimum = property.optDoubleOrNull("exclusiveMinimum")
                val exclusiveMaximum = property.optDoubleOrNull("exclusiveMaximum")
                when {
                    minimum != null -> (minimum - 1).toString()
                    exclusiveMinimum != null -> exclusiveMinimum.toString()
                    maximum != null -> (maximum + 1).toString()
                    exclusiveMaximum != null -> exclusiveMaximum.toString()
                    else -> "abc"
                }
            }

            "boolean" -> "not-a-bool"
            "array" -> "{}"
            "object" -> "[]"

            "string" -> property.optStringOrNull("pattern")?.let { firstInvalidValueFor(it) }

            else -> null
        }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name)
    }

    private fun JSONObject.optStringSetOrNull(name: String): Set<String>? {
        val array = optJSONArray(name) ?: return null
        return buildSet {
            for (index in 0 until array.length()) {
                add(array.getString(index))
            }
        }
    }

    private fun JSONObject.keysSet(): Set<String> {
        val result = linkedSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            result += iterator.next()
        }
        return result
    }

    private fun JSONObject.optStringSet(name: String): Set<String> {
        val array = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                add(array.getString(index))
            }
        }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name)) optInt(name) else null

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name)
}
