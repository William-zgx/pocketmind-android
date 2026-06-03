package com.bytedance.zgx.pocketmind.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionBoundaryTest {
    @Test
    fun executeMapsExecutorExceptionsToRetryableFailure() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                throw IllegalStateException("boom")
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val result = boundary.execute(request)

        assertEquals("request-1", result.requestId)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals("test_tool", result.data["toolName"])
        assertEquals("Tool execution failed before completion: boom", result.summary)
    }

    @Test
    fun executePropagatesCancellationException() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                throw CancellationException("stop")
            },
            dispatcher = Dispatchers.Unconfined,
        )

        var cancellationThrown = false
        try {
            boundary.execute(request)
        } catch (throwable: CancellationException) {
            cancellationThrown = true
            assertEquals("stop", throwable.message)
        }

        assertTrue(cancellationThrown)
    }

    @Test
    fun executeReturnsTimeoutFailureWhenTimeoutElapsesBeforeExecution() = runTest {
        var callCount = 0
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                callCount += 1
                request.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            timeoutMillis = 0L,
        )

        val result = boundary.execute(request)

        assertEquals(0, callCount)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals("test_tool", result.data["toolName"])
        assertEquals("Tool execution timed out after 0 seconds.", result.summary)
    }

    @Test
    fun publicEvidenceBatchRetriesOnlyRetryableFailuresAndPreservesOrder() = runTest {
        val first = ToolRequest(id = "first", toolName = "first_tool")
        val retryable = ToolRequest(id = "retryable", toolName = "retryable_tool")
        val stableFailure = ToolRequest(id = "stable-failure", toolName = "stable_failure_tool")
        val callsByRequestId = mutableMapOf<String, Int>()
        var retryCallbackCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                val callCount = callsByRequestId.getOrDefault(request.id, 0) + 1
                callsByRequestId[request.id] = callCount
                when (request.id) {
                    "first" -> request.succeeded("first success")
                    "retryable" -> if (callCount == 1) {
                        request.failed(
                            code = ToolErrorCode.ExecutionFailed,
                            summary = "temporary failure",
                            retryable = true,
                        )
                    } else {
                        request.succeeded("retry success")
                    }
                    "stable-failure" -> request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "final failure",
                        retryable = false,
                    )
                    else -> error("Unexpected request ${request.id}")
                }
            },
            dispatcher = Dispatchers.Unconfined,
            publicEvidenceBatchRetryAttempts = 1,
        )

        val results = boundary.executePublicEvidenceBatch(
            requests = listOf(first, retryable, stableFailure),
        ) {
            retryCallbackCount += 1
        }

        assertEquals(listOf("first", "retryable", "stable-failure"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Succeeded, ToolStatus.Succeeded, ToolStatus.Failed), results.map { it.status })
        assertEquals("retry success", results[1].summary)
        assertFalse(results[2].retryable)
        assertEquals(1, callsByRequestId["first"])
        assertEquals(2, callsByRequestId["retryable"])
        assertEquals(1, callsByRequestId["stable-failure"])
        assertEquals(1, retryCallbackCount)
    }

    @Test
    fun publicEvidenceBatchDoesNotRetryWhenNothingIsRetryable() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        var callCount = 0
        var retryCallbackCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                callCount += 1
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "final failure",
                    retryable = false,
                )
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val results = boundary.executePublicEvidenceBatch(listOf(request)) {
            retryCallbackCount += 1
        }

        assertEquals(1, results.size)
        assertEquals(ToolStatus.Failed, results.single().status)
        assertEquals(1, callCount)
        assertEquals(0, retryCallbackCount)
    }
}

private class LambdaToolExecutor(
    private val executeBlock: (ToolRequest) -> ToolResult,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult = executeBlock(request)
}
