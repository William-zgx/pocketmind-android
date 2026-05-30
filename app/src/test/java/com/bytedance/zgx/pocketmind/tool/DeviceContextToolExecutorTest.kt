package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityWindow
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.ForegroundAppInfo
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceContextToolExecutorTest {
    @Test
    fun foregroundAppToolExecutorReturnsAvailableAppInfo() {
        val executor = ForegroundAppToolExecutor(
            FakeForegroundAppProvider(
                result = ForegroundAppReadResult.Available(
                    ForegroundAppInfo(
                        packageName = "com.example.app",
                        appLabel = "示例应用",
                        lastTimeUsedMillis = 1_700_000_000_000L,
                    ),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("com.example.app", result.data["packageName"])
        assertEquals("示例应用", result.data["appLabel"])
        assertEquals("1700000000000", result.data["lastTimeUsedMillis"])
    }

    @Test
    fun foregroundAppToolExecutorReturnsPermissionFailure() {
        val executor = ForegroundAppToolExecutor(
            FakeForegroundAppProvider(
                result = ForegroundAppReadResult.PermissionDenied("未授权\"查看应用使用情况\"权限"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-foreground-permission",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
    }

    @Test
    fun foregroundAppToolExecutorRejectsOtherTools() {
        val executor = ForegroundAppToolExecutor(
            FakeForegroundAppProvider(
                result = ForegroundAppReadResult.Failed("not used"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-foreground-unknown",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.UnknownTool, result.error?.code)
    }

    @Test
    fun contactSummaryToolExecutorReadsAndSerializesItems() {
        val executor = ContactSummaryToolExecutor(
            FakeContactSummaryProvider { _, _ ->
                ContactSummaryReadResult.Available(
                    listOf(
                        ContactSummaryItem(name = "联系人A", phone = "13800000000"),
                        ContactSummaryItem(name = "联系人B", phone = "13800000001"),
                    ),
                )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-contacts",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "联系人", "maxCount" to "2"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("2", result.data["contactCount"])
        assertEquals("2", result.data["maxCount"])
        assertEquals("联系人", result.data["query"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])

        val contacts = JSONArray(result.data.getValue("contactsJson"))
        assertEquals(2, contacts.length())
        assertEquals("联系人A", contacts.getJSONObject(0).getString("name"))
        assertEquals("13800000000", contacts.getJSONObject(0).getString("phone"))
    }

    @Test
    fun contactSummaryToolExecutorDefaultsToConfiguredCountWhenMaxCountMissing() {
        val fakeProvider = FakeContactSummaryProvider { query, maxCount ->
            assertEquals("联系人A", query)
            assertEquals(5, maxCount)
            ContactSummaryReadResult.Available(emptyList())
        }
        val executor = ContactSummaryToolExecutor(fakeProvider)

        val result = executor.execute(
            ToolRequest(
                id = "request-contacts-default",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "联系人A"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("0", result.data["contactCount"])
    }

    @Test
    fun contactSummaryToolExecutorReturnsStructuredPermissionFailure() {
        val executor = ContactSummaryToolExecutor(
            FakeContactSummaryProvider { _, _ ->
                ContactSummaryReadResult.PermissionDenied("未授权")
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-contact-deny",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "联系人A"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
    }

    @Test
    fun notificationSummaryToolExecutorReadsAndSerializesItems() {
        val executor = NotificationSummaryToolExecutor(
            FakeNotificationSummaryProvider {
                NotificationSummaryReadResult.Available(
                    listOf(
                        NotificationSummaryItem(
                            id = 101,
                            title = "消息 A",
                            isOngoing = false,
                            postTimeMillis = 1700000001000L,
                        ),
                        NotificationSummaryItem(
                            id = 102,
                            title = "消息 B",
                            isOngoing = true,
                            postTimeMillis = 1700000002000L,
                        ),
                    ),
                )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-notifications",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "2"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("2", result.data["notificationCount"])
        assertEquals("2", result.data["maxCount"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])

        val notifications = JSONArray(result.data.getValue("notificationsJson"))
        assertEquals(2, notifications.length())
        assertEquals(101, notifications.getJSONObject(0).getInt("id"))
        assertEquals("消息 A", notifications.getJSONObject(0).getString("title"))
        assertEquals(false, notifications.getJSONObject(0).getBoolean("isOngoing"))
    }

    @Test
    fun notificationSummaryToolExecutorDefaultsToConfiguredCountWhenMaxCountMissing() {
        val fakeProvider = FakeNotificationSummaryProvider { maxCount ->
            NotificationSummaryReadResult.Available(emptyList())
        }
        val executor = NotificationSummaryToolExecutor(fakeProvider)

        val result = executor.execute(
            ToolRequest(
                id = "request-notifications-default",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(5, fakeProvider.lastRequestedCount)
        assertEquals("0", result.data["notificationCount"])
    }

    @Test
    fun routingExecutorRoutesDeviceContextToolsAndOnlyFallsBackForOthers() {
        var delegatedTool: String? = null
        val routing = RoutingToolExecutor(
            calendarAvailabilityProvider = UnusedCalendarProvider,
            foregroundAppProvider = FakeForegroundAppProvider(
                result = ForegroundAppReadResult.Available(
                    ForegroundAppInfo(
                        packageName = "com.example.app",
                        appLabel = "示例应用",
                        lastTimeUsedMillis = 1L,
                    ),
                ),
            ),
            notificationSummaryProvider = FakeNotificationSummaryProvider {
                NotificationSummaryReadResult.Available(emptyList())
            },
            contactSummaryProvider = FakeContactSummaryProvider { _, _ ->
                ContactSummaryReadResult.Available(emptyList())
            },
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    delegatedTool = request.toolName
                    return request.succeeded("delegated")
                }
            },
        )

        val foregroundResult = routing.execute(
            ToolRequest(
                id = "request-route-foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, foregroundResult.status)
        assertNull(delegatedTool)

        val notificationsResult = routing.execute(
            ToolRequest(
                id = "request-route-notification",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, notificationsResult.status)
        assertNull(delegatedTool)

        val contactsResult = routing.execute(
            ToolRequest(
                id = "request-route-contact",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "联系人A"),
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, contactsResult.status)
        assertNull(delegatedTool)

        routing.execute(
            ToolRequest(
                id = "request-route-unknown",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, delegatedTool)
    }

    @Test
    fun notificationSummaryToolExecutorReturnsStructuredPermissionFailure() {
        val executor = NotificationSummaryToolExecutor(
            FakeNotificationSummaryProvider {
                NotificationSummaryReadResult.PermissionDenied("未授权")
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-notification-deny",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
    }

    private object UnusedCalendarProvider : CalendarAvailabilityProvider {
        override fun queryAvailability(window: CalendarAvailabilityWindow): CalendarAvailabilityReadResult =
            CalendarAvailabilityReadResult.Failed("unused")
    }

    private class FakeForegroundAppProvider(
        private val result: ForegroundAppReadResult,
    ) : ForegroundAppProvider {
        override fun currentForegroundApp(): ForegroundAppReadResult {
            return result
        }
    }

    private class FakeNotificationSummaryProvider(
        private val resultFactory: (Int) -> NotificationSummaryReadResult,
    ) : NotificationSummaryProvider {
        var lastRequestedCount: Int = -1
            private set

        override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult {
            lastRequestedCount = maxCount
            return resultFactory(maxCount)
        }
    }

    private class FakeContactSummaryProvider(
        private val resultFactory: (String, Int) -> ContactSummaryReadResult,
    ) : ContactSummaryProvider {
        override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult {
            return resultFactory(query, maxCount)
        }
    }
}
