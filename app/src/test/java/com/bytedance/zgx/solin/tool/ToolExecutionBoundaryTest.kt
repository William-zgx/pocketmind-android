package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.safety.SafetyPolicy
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

    @Test
    fun publicEvidenceBatchMapsSingleToolCancellationWithoutCancellingWholeBatch() = runTest {
        val cancelled = ToolRequest(id = "cancelled", toolName = "cancelled_tool")
        val succeeded = ToolRequest(id = "succeeded", toolName = "succeeded_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                when (request.id) {
                    "cancelled" -> throw CancellationException("single tool stopped")
                    "succeeded" -> request.succeeded("success")
                    else -> error("Unexpected request ${request.id}")
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val results = boundary.executePublicEvidenceBatch(listOf(cancelled, succeeded))

        assertEquals(listOf("cancelled", "succeeded"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Cancelled, ToolStatus.Succeeded), results.map { it.status })
        assertEquals(ToolErrorCode.UserCancelled, results.first().error?.code)
        assertFalse(results.first().retryable)
        assertEquals("cancelled_tool", results.first().data["toolName"])
    }

    @Test
    fun executeFinalAuthorizationRejectsMissingSpecBeforeDispatch() = runTest {
        val request = ToolRequest(id = "missing", toolName = "missing_tool")
        var executeCallCount = 0
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor {
                executeCallCount += 1
                request.succeeded("unexpected")
            },
            registry = ToolRegistry(providers = emptyList()),
            contextProvider = ToolExecutionAuthorizationContextProvider {
                ToolExecutionAuthorizationContext(
                    userConfirmed = true,
                    availableCapabilities = ToolCapability.entries.toSet(),
                )
            },
        )

        val result = boundary.execute(request)

        assertEquals(0, executeCallCount)
        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(ToolErrorCode.UnknownTool, result.error?.code)
        assertFalse(result.retryable)
    }

    @Test
    fun executeFinalAuthorizationFailsClosedForUnclearContextAndMissingCapability() = runTest {
        val spec = testSpec(name = "guarded_tool")
        val registry = ToolRegistry(TestToolProvider(spec))
        val contexts = mutableListOf<ToolExecutionAuthorizationContext?>(
            null,
            ToolExecutionAuthorizationContext(userConfirmed = true, availableCapabilities = null),
            ToolExecutionAuthorizationContext(userConfirmed = true, availableCapabilities = emptySet()),
        )
        var executeCallCount = 0
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("unexpected")
            },
            registry = registry,
            contextProvider = ToolExecutionAuthorizationContextProvider { contexts.removeAt(0) },
        )
        val request = ToolRequest(id = "guarded", toolName = spec.name)

        val results = List(3) { boundary.execute(request) }

        assertEquals(0, executeCallCount)
        assertTrue(results.all { it.status == ToolStatus.Rejected })
        assertTrue(results[0].summary.contains("context is unavailable"))
        assertTrue(results[1].summary.contains("capability context is unclear"))
        assertTrue(results[2].summary.contains("capability ${spec.capability} is unavailable"))
    }

    @Test
    fun executeFinalAuthorizationRequiresMediumRiskConfirmation() = runTest {
        val spec = testSpec(
            name = "medium_tool",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
        )
        val registry = ToolRegistry(TestToolProvider(spec))
        var executeCallCount = 0
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("executed")
            },
            registry = registry,
            contextProvider = ToolExecutionAuthorizationContextProvider {
                ToolExecutionAuthorizationContext(
                    userConfirmed = false,
                    availableCapabilities = setOf(spec.capability),
                )
            },
        )

        val result = boundary.execute(ToolRequest(id = "medium", toolName = spec.name))

        assertEquals(0, executeCallCount)
        assertEquals(ToolStatus.Rejected, result.status)
        assertTrue(result.summary.contains("requires user confirmation"))
    }

    @Test
    fun executeReauthorizesEveryRealExecutionAttempt() = runTest {
        val spec = testSpec(name = "repeat_tool")
        val registry = ToolRegistry(TestToolProvider(spec))
        var authorizationCount = 0
        var executeCallCount = 0
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("executed")
            },
            registry = registry,
            contextProvider = ToolExecutionAuthorizationContextProvider {
                authorizationCount += 1
                if (authorizationCount == 1) {
                    ToolExecutionAuthorizationContext(
                        userConfirmed = true,
                        availableCapabilities = setOf(spec.capability),
                    )
                } else {
                    ToolExecutionAuthorizationContext(
                        userConfirmed = true,
                        availableCapabilities = emptySet(),
                    )
                }
            },
        )
        val request = ToolRequest(id = "repeat", toolName = spec.name)

        val first = boundary.execute(request)
        val second = boundary.execute(request)

        assertEquals(ToolStatus.Succeeded, first.status)
        assertEquals(ToolStatus.Rejected, second.status)
        assertEquals(2, authorizationCount)
        assertEquals(1, executeCallCount)
    }

    @Test
    fun batchFinalAuthorizationRejectsWholeBatchBeforePartialExecution() = runTest {
        val firstSpec = testSpec(name = "first_tool")
        val secondSpec = testSpec(name = "second_tool", capability = ToolCapability.WebSearch)
        val registry = ToolRegistry(TestToolProvider(firstSpec, secondSpec))
        var executeCallCount = 0
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("unexpected")
            },
            registry = registry,
            contextProvider = ToolExecutionAuthorizationContextProvider {
                ToolExecutionAuthorizationContext(
                    userConfirmed = true,
                    availableCapabilities = setOf(firstSpec.capability),
                )
            },
        )

        val results = boundary.executeBatch(
            listOf(
                ToolRequest(id = "first", toolName = firstSpec.name),
                ToolRequest(id = "second", toolName = secondSpec.name),
            ),
        )

        assertEquals(0, executeCallCount)
        assertEquals(listOf(ToolStatus.Rejected, ToolStatus.Rejected), results.map { it.status })
        assertTrue(results[0].summary.contains("another request was ineligible"))
        assertTrue(results[1].summary.contains("capability ${secondSpec.capability} is unavailable"))
    }

    @Test
    fun batchRetryReauthorizesRetryableRequestBeforeRetryDispatch() = runTest {
        val spec = testSpec(name = "retry_tool")
        val registry = ToolRegistry(TestToolProvider(spec))
        var authorizationCount = 0
        var executeCallCount = 0
        var retryCallbackCount = 0
        val request = ToolRequest(id = "retry", toolName = spec.name)
        val boundary = authorizationBoundary(
            executor = LambdaToolExecutor {
                executeCallCount += 1
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "temporary failure",
                    retryable = true,
                )
            },
            registry = registry,
            contextProvider = ToolExecutionAuthorizationContextProvider {
                authorizationCount += 1
                ToolExecutionAuthorizationContext(
                    userConfirmed = true,
                    availableCapabilities = if (authorizationCount == 1) {
                        setOf(spec.capability)
                    } else {
                        emptySet()
                    },
                )
            },
        )

        val result = boundary.executeBatch(listOf(request)) {
            retryCallbackCount += 1
        }.single()

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(2, authorizationCount)
        assertEquals(1, executeCallCount)
        assertEquals(0, retryCallbackCount)
        assertTrue(result.summary.contains("capability ${spec.capability} is unavailable"))
    }

    @Test
    fun publicEvidenceBatchValidatorRejectsWholeBatchBeforeExecution() = runTest {
        val allowed = ToolRequest(id = "allowed", toolName = "allowed_tool")
        val blocked = ToolRequest(id = "blocked", toolName = "blocked_tool")
        var executeCallCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            publicEvidenceBatchRequestValidator = { request ->
                if (request.id == "blocked") {
                    request.rejected("blocked is not public evidence")
                } else {
                    null
                }
            },
        )

        val results = boundary.executePublicEvidenceBatch(listOf(allowed, blocked))

        assertEquals(0, executeCallCount)
        assertEquals(listOf("allowed", "blocked"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Rejected, ToolStatus.Rejected), results.map { it.status })
        assertTrue(results[0].summary.contains("another request was ineligible"))
        assertTrue(results[1].summary.contains("blocked is not public evidence"))
    }
}

private class LambdaToolExecutor(
    private val executeBlock: (ToolRequest) -> ToolResult,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult = executeBlock(request)
}

private fun authorizationBoundary(
    executor: ToolExecutor,
    registry: ToolRegistry,
    contextProvider: ToolExecutionAuthorizationContextProvider,
): TimeoutToolExecutionBoundary =
    TimeoutToolExecutionBoundary(
        executor = executor,
        dispatcher = Dispatchers.Unconfined,
        executionAuthorizer = ToolExecutionAuthorizer(
            toolRegistry = registry,
            safetyPolicy = SafetyPolicy(),
            contextProvider = contextProvider,
        ),
    )

private fun testSpec(
    name: String,
    capability: ToolCapability = ToolCapability.ExternalNavigation,
    riskLevel: RiskLevel = RiskLevel.LowReadOnly,
    confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.NotRequired,
): ToolSpec =
    ToolSpec(
        name = name,
        title = name,
        description = "Test tool",
        inputSchemaJson = "{}",
        capability = capability,
        riskLevel = riskLevel,
        confirmationPolicy = confirmationPolicy,
    )

private class TestToolProvider(
    private vararg val specs: ToolSpec,
) : ToolProvider {
    override fun specs(): List<ToolSpec> = specs.toList()
}
