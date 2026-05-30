package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {
    @Test
    fun validateBeforeExecutionForUnknownTool() {
        var delegated = false
        val executor = ValidatingToolExecutor(
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    delegated = true
                    return request.succeeded("should not reach")
                }
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-unknown",
                toolName = "unknown_tool",
                reason = "validation test",
            ),
        )

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(ToolErrorCode.UnknownTool, result.error?.code)
        assertTrue("Unknown tool should never delegate", !delegated)
        assertEquals("unknown_tool", result.data["toolName"])
    }

    @Test
    fun validateBeforeExecutionForMissingArguments() {
        var delegated = false
        val executor = ValidatingToolExecutor(
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    delegated = true
                    return request.succeeded("should not reach")
                }
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-web-search",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = emptyMap(),
                reason = "validation test",
            ),
        )

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue("Missing arguments should never delegate", !delegated)
        assertTrue(result.summary.contains("requires argument"))
    }

    @Test
    fun wrapsExecutionFailureAsToolResult() {
        val executor = ValidatingToolExecutor(
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    throw IllegalStateException("execute failed")
                }
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-failure",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "failure test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertTrue(result.summary.contains("execute failed"))
        assertEquals("request-failure", result.requestId)
    }

    @Test
    fun addsToolNameWhenDelegateOmitsItFromResultData() {
        val executor = ValidatingToolExecutor(
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    ToolResult(
                        requestId = request.id,
                        status = ToolStatus.Succeeded,
                        summary = "ok",
                        data = emptyMap(),
                    )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-missing-name",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "result shape test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
    }

    @Test
    fun overwritesDelegateToolNameWhenMissingCanonicalValue() {
        val executor = ValidatingToolExecutor(
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    request.succeeded(
                        summary = "ok",
                        data = mapOf("toolName" to "custom:${request.toolName}"),
                    )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-custom-name",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
    }
}
