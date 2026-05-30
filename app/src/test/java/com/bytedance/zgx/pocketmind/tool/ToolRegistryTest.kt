package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry()

    @Test
    fun rejectsUnknownTool() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-1",
                toolName = "delete_contact",
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertEquals(ToolErrorCode.UnknownTool, rejection.error?.code)
        assertTrue(rejection.summary.contains("Unknown tool"))
        assertEquals("delete_contact", rejection.data["toolName"])
    }

    @Test
    fun exposesSpecsForSupportedActionsWithConfirmationRequired() {
        val specNames = registry.specs().map { it.name }.toSet()

        assertTrue(specNames.containsAll(MobileActionFunctions.supported))

        val wifiSpec = registry.specFor(MobileActionFunctions.OPEN_WIFI_SETTINGS)
        assertNotNull(wifiSpec)
        requireNotNull(wifiSpec)
        assertEquals(ToolCapability.DeviceSettings, wifiSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in wifiSpec.permissions)
        assertEquals(RiskLevel.MediumDraftOrNavigation, wifiSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, wifiSpec.confirmationPolicy)

        val webSearchSpec = registry.specFor(MobileActionFunctions.WEB_SEARCH)
        assertNotNull(webSearchSpec)
        requireNotNull(webSearchSpec)
        assertEquals(ToolCapability.WebSearch, webSearchSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in webSearchSpec.permissions)
        assertTrue(webSearchSpec.inputSchemaJson.contains("query"))

        val reminderSpec = registry.specFor(MobileActionFunctions.SCHEDULE_REMINDER)
        assertNotNull(reminderSpec)
        requireNotNull(reminderSpec)
        assertEquals(ToolCapability.BackgroundTask, reminderSpec.capability)
        assertTrue(ToolPermission.SchedulesBackgroundWork in reminderSpec.permissions)
        assertTrue(ToolPermission.PostsNotification in reminderSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in reminderSpec.permissions)

        val clipboardSpec = registry.specFor(MobileActionFunctions.READ_CLIPBOARD)
        assertNotNull(clipboardSpec)
        requireNotNull(clipboardSpec)
        assertEquals(ToolCapability.DeviceContext, clipboardSpec.capability)
        assertTrue(ToolPermission.ReadsDeviceContext in clipboardSpec.permissions)
        assertTrue(ToolPermission.ReadsClipboard in clipboardSpec.permissions)

        val shareSpec = registry.specFor(MobileActionFunctions.SHARE_TEXT)
        assertNotNull(shareSpec)
        requireNotNull(shareSpec)
        assertEquals(ToolCapability.ExternalShare, shareSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in shareSpec.permissions)
        assertTrue(ToolPermission.SendsTextToExternalApp in shareSpec.permissions)

        val deepLinkSpec = registry.specFor(MobileActionFunctions.OPEN_DEEP_LINK)
        assertNotNull(deepLinkSpec)
        requireNotNull(deepLinkSpec)
        assertEquals(ToolCapability.ExternalNavigation, deepLinkSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in deepLinkSpec.permissions)
        assertTrue(deepLinkSpec.inputSchemaJson.contains("\"uri\""))

        val openAppIntentSpec = registry.specFor(MobileActionFunctions.OPEN_APP_INTENT)
        assertNotNull(openAppIntentSpec)
        requireNotNull(openAppIntentSpec)
        assertEquals(ToolCapability.ExternalNavigation, openAppIntentSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in openAppIntentSpec.permissions)
        assertTrue(openAppIntentSpec.inputSchemaJson.contains("\"packageName\""))
        assertEquals(ConfirmationPolicy.Required, openAppIntentSpec.confirmationPolicy)

        val calendarAvailabilitySpec = registry.specFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY)
        assertNotNull(calendarAvailabilitySpec)
        requireNotNull(calendarAvailabilitySpec)
        assertEquals(ToolCapability.DeviceContext, calendarAvailabilitySpec.capability)
        assertEquals(RiskLevel.LowReadOnly, calendarAvailabilitySpec.riskLevel)
        assertTrue(ToolPermission.ReadsDeviceContext in calendarAvailabilitySpec.permissions)
        assertTrue(ToolPermission.ReadsCalendar in calendarAvailabilitySpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in calendarAvailabilitySpec.permissions)
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("\"start\""))
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("\"end\""))
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("31 days"))

        val foregroundAppSpec = registry.specFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        assertNotNull(foregroundAppSpec)
        requireNotNull(foregroundAppSpec)
        assertEquals(ToolCapability.DeviceContext, foregroundAppSpec.capability)
        assertEquals(RiskLevel.LowReadOnly, foregroundAppSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, foregroundAppSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in foregroundAppSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in foregroundAppSpec.permissions)
        assertTrue(foregroundAppSpec.inputSchemaJson.contains("\"type\": \"object\""))

        val recentNotificationSpec = registry.specFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)
        assertNotNull(recentNotificationSpec)
        requireNotNull(recentNotificationSpec)
        assertEquals(ToolCapability.DeviceContext, recentNotificationSpec.capability)
        assertEquals(RiskLevel.LowReadOnly, recentNotificationSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, recentNotificationSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in recentNotificationSpec.permissions)
        assertTrue(recentNotificationSpec.inputSchemaJson.contains("maxCount"))

        val contactQuerySpec = registry.specFor(MobileActionFunctions.QUERY_CONTACTS)
        assertNotNull(contactQuerySpec)
        requireNotNull(contactQuerySpec)
        assertEquals(ToolCapability.DeviceContext, contactQuerySpec.capability)
        assertEquals(RiskLevel.LowReadOnly, contactQuerySpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, contactQuerySpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in contactQuerySpec.permissions)
        assertTrue(ToolPermission.ReadsContacts in contactQuerySpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in contactQuerySpec.permissions)
        assertTrue(contactQuerySpec.inputSchemaJson.contains("query"))
        assertTrue(contactQuerySpec.inputSchemaJson.contains("maxCount"))

        val cancelReminderSpec = registry.specFor(MobileActionFunctions.CANCEL_REMINDER)
        assertNotNull(cancelReminderSpec)
        requireNotNull(cancelReminderSpec)
        assertEquals(ToolCapability.BackgroundTask, cancelReminderSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, cancelReminderSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, cancelReminderSpec.confirmationPolicy)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in cancelReminderSpec.permissions)
        assertTrue(cancelReminderSpec.inputSchemaJson.contains("taskId"))
    }

    @Test
    fun allToolInputSchemasAreParseableAndClosed() {
        registry.specs().forEach { spec ->
            assertTrue("${spec.name} schema should declare object type", spec.inputSchemaJson.contains("\"object\""))
            assertTrue(
                "${spec.name} schema should reject undeclared arguments",
                spec.inputSchemaJson.contains("\"additionalProperties\": false"),
            )
        }
    }

    @Test
    fun validatesWebSearchQueryArgument() {
        val missingQuery = registry.validate(
            ToolRequest(
                id = "request-2",
                toolName = MobileActionFunctions.WEB_SEARCH,
                reason = "test",
            ),
        )
        assertNotNull(missingQuery)
        requireNotNull(missingQuery)
        assertEquals(ToolStatus.Rejected, missingQuery.status)
        assertTrue(missingQuery.summary.contains("query"))

        val blankQuery = registry.validate(
            ToolRequest(
                id = "request-3",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to " "),
                reason = "test",
            ),
        )
        assertNotNull(blankQuery)
        requireNotNull(blankQuery)
        assertEquals(ToolStatus.Rejected, blankQuery.status)
        assertTrue(blankQuery.summary.contains("query"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-4",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin coroutines Android"),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun rejectsUnknownArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-extra",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                arguments = mapOf(
                    "body" to "明天聊",
                    "sendNow" to "true",
                ),
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("sendNow"))
    }

    @Test
    fun validatesRequiredArgumentsForDraftTools() {
        val requiredArgumentsByTool = mapOf(
            MobileActionFunctions.COMPOSE_EMAIL to "body",
            MobileActionFunctions.CREATE_CALENDAR_EVENT to "title",
            MobileActionFunctions.CREATE_CONTACT_DRAFT to "name",
            MobileActionFunctions.SEARCH_MAPS to "query",
            MobileActionFunctions.WEB_SEARCH to "query",
            MobileActionFunctions.SCHEDULE_REMINDER to "title",
            MobileActionFunctions.CANCEL_REMINDER to "taskId",
            MobileActionFunctions.SHARE_TEXT to "text",
            MobileActionFunctions.OPEN_DEEP_LINK to "uri",
            MobileActionFunctions.OPEN_APP_INTENT to "packageName",
            MobileActionFunctions.QUERY_CONTACTS to "query",
        )

        requiredArgumentsByTool.forEach { (toolName, requiredArgument) ->
            val rejection = registry.validate(
                ToolRequest(
                    id = "request-$toolName",
                    toolName = toolName,
                    arguments = mapOf(requiredArgument to " "),
                    reason = "test",
                ),
            )

            assertNotNull("Expected blank $requiredArgument to reject $toolName", rejection)
            requireNotNull(rejection)
            assertEquals(ToolStatus.Rejected, rejection.status)
            assertTrue(rejection.summary.contains(requiredArgument))
        }
    }

    @Test
    fun validatesRecentNotificationMaxCountPattern() {
        val invalid = registry.validate(
            ToolRequest(
                id = "request-recent-invalid",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "0"),
                reason = "test",
            ),
        )
        assertNotNull(invalid)
        requireNotNull(invalid)
        assertTrue(invalid.summary.contains("maxCount"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-recent-valid",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "6"),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun validatesContactQueryMaxCountPattern() {
        val invalid = registry.validate(
            ToolRequest(
                id = "request-contact-max-invalid",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf(
                    "query" to "li",
                    "maxCount" to "0",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalid)
        requireNotNull(invalid)
        assertEquals(ToolStatus.Rejected, invalid.status)
        assertTrue(invalid.summary.contains("maxCount"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-contact-max-valid",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf(
                    "query" to "li",
                    "maxCount" to "6",
                ),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun acceptsOpenWifiSettingsWithoutArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-5",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )

        assertNull(rejection)
    }

    @Test
    fun rejectsArgumentsDisallowedByEmptyObjectSchema() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-wifi-extra",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                arguments = mapOf("enabled" to "true"),
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("enabled"))
    }

    @Test
    fun validatesReminderDelayMinutesAsPositiveInteger() {
        val invalid = registry.validate(
            ToolRequest(
                id = "request-reminder-invalid",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "delayMinutes" to "0",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalid)
        requireNotNull(invalid)
        assertTrue(invalid.summary.contains("delayMinutes"))

        val nonInteger = registry.validate(
            ToolRequest(
                id = "request-reminder-non-integer",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "delayMinutes" to "1.5",
                ),
                reason = "test",
            ),
        )
        assertNotNull(nonInteger)
        requireNotNull(nonInteger)
        assertTrue(nonInteger.summary.contains("delayMinutes"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-reminder-valid",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "body" to "提醒我喝水",
                    "delayMinutes" to "15",
                ),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun acceptsReadClipboardWithoutArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertNull(rejection)
    }

    @Test
    fun validatesCalendarAvailabilityStartAndEndArguments() {
        val missingEnd = registry.validate(
            ToolRequest(
                id = "request-calendar-missing",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf("start" to "2026-06-01T09:00:00Z"),
                reason = "test",
            ),
        )
        assertNotNull(missingEnd)
        requireNotNull(missingEnd)
        assertEquals(ToolStatus.Rejected, missingEnd.status)
        assertTrue(missingEnd.summary.contains("end"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-calendar-valid",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf(
                    "start" to "2026-06-01T09:00:00Z",
                    "end" to "2026-06-01T10:00:00Z",
                ),
                reason = "test",
            ),
        )

        assertNull(valid)
    }
}
