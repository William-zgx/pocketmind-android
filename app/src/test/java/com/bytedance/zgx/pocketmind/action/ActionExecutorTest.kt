package com.bytedance.zgx.pocketmind.action

import android.content.Intent
import android.net.Uri
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun schedulesReminderThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("task-1", result.data["taskId"])
        assertEquals("喝水", scheduler.lastReminderRequest?.title)
        assertEquals(15L, scheduler.lastReminderRequest?.delayMinutes)
    }

    @Test
    fun rejectsReminderWhenNotificationPermissionIsMissing() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { false },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertTrue(result.summary.contains("通知权限"))
    }

    @Test
    fun reportsSchedulerFailureAsStructuredToolResult() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(
                failure = IllegalStateException("alarm unavailable"),
            ),
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("alarm unavailable"))
    }

    @Test
    fun cancelsReminderThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-abc"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("task-abc", scheduler.lastCancelledTaskId)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, result.data["toolName"])
        assertEquals("task-abc", result.data["taskId"])
    }

    @Test
    fun rejectsCancelReminderWithoutTaskId() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-empty",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "  "),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
    }

    @Test
    fun reportsCancelFailureAsStructuredToolResult() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(
                cancelFailure = IllegalArgumentException("cancel failed"),
            ),
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-failed",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-abc"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("cancel failed"))
    }

    @Test
    fun buildsDeepLinkIntentWithCustomParser() {
        var parsed = false
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { true },
            deepLinkParser = { uri ->
                parsed = true
                Uri.EMPTY
            },
            activityStarter = { intent ->
                fail("deep link should not execute Activity directly in this unit test")
                false
            },
        )

        val request = ToolRequest(
            id = "request-deeplink",
            toolName = MobileActionFunctions.OPEN_DEEP_LINK,
            arguments = mapOf("uri" to "https://example.com/path?query=1"),
            reason = "test",
        )
        val openDeepLinkIntentMethod = ActionExecutor::class.java.getDeclaredMethod(
            "openDeepLinkIntent",
            ToolRequest::class.java,
        ).apply { isAccessible = true }
        val deepLinkIntent = openDeepLinkIntentMethod.invoke(executor, request) as Intent
        assertEquals(Intent::class.java, deepLinkIntent.javaClass)
        assertTrue(parsed)
    }

    @Test
    fun buildsOpenAppIntentWithPackageAndOptionalClass() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { true },
        )

        val request = ToolRequest(
            id = "request-open-app-intent",
            toolName = MobileActionFunctions.OPEN_APP_INTENT,
            arguments = mapOf(
                "packageName" to "com.tencent.mm",
                "activityClass" to "com.tencent.mm.ui.LauncherUI",
                "action" to "android.intent.action.VIEW",
                "data" to "https://example.com/app",
            ),
            reason = "test",
        )
        val openAppIntentMethod = ActionExecutor::class.java.getDeclaredMethod(
            "openAppIntent",
            ToolRequest::class.java,
        ).apply { isAccessible = true }
        val openAppIntent = openAppIntentMethod.invoke(executor, request) as Intent
        assertEquals(Intent::class.java, openAppIntent.javaClass)
    }

    @Test
    fun executesOpenAppIntentTool() {
        var started = false
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { true },
            activityStarter = {
                started = true
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-open-app-intent-exec",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf(
                    "packageName" to "com.tencent.mm",
                    "activityClass" to "com.tencent.mm.ui.LauncherUI",
                    "action" to "android.intent.action.VIEW",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertTrue(started)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, result.data["toolName"])
        assertTrue(result.summary.contains("已打开应用"))
    }

    @Test
    fun readsClipboardTextThroughInjectedProvider() {
        val executor = ActionExecutor(
            context = null,
            clipboardTextProvider = { "  需要总结的剪贴板内容  " },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("需要总结的剪贴板内容", result.data["text"])
        assertEquals("false", result.data["truncated"])
        assertTrue(result.summary.contains("剪贴板"))
    }

    @Test
    fun reportsEmptyClipboardAsStructuredFailure() {
        val executor = ActionExecutor(
            context = null,
            clipboardTextProvider = { " " },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-clipboard-empty",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("剪贴板"))
    }

    private fun reminderRequest(): ToolRequest =
        ToolRequest(
            id = "request-reminder",
            toolName = MobileActionFunctions.SCHEDULE_REMINDER,
            arguments = mapOf(
                "title" to "喝水",
                "body" to "提醒我 15 分钟后喝水",
                "delayMinutes" to "15",
            ),
            reason = "test",
        )

    private class RecordingBackgroundTaskScheduler(
        private val failure: Throwable? = null,
        private val cancelFailure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        var lastReminderRequest: ReminderScheduleRequest? = null
            private set
        var lastCancelledTaskId: String? = null
            private set

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> {
            lastReminderRequest = request
            failure?.let { return Result.failure(it) }
            return Result.success(
                ScheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    title = request.title,
                    body = request.body,
                    triggerAtMillis = 901_000L,
                    status = ScheduledTaskStatus.Scheduled,
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
            )
        }

        override fun cancel(taskId: String): Result<Unit> {
            lastCancelledTaskId = taskId
            cancelFailure?.let { return Result.failure(it) }
            return Result.success(Unit)
        }
    }
}
