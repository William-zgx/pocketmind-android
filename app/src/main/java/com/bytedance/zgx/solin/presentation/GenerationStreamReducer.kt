package com.bytedance.zgx.solin.presentation

/**
 * Identifies one assistant generation stream. [generationToken] must increase whenever a caller
 * replaces the active stream, so callbacks from an older coroutine cannot mutate the new stream.
 */
internal data class GenerationStreamKey(
    val sessionId: String,
    val runId: String,
    val generationToken: Long,
)

internal sealed interface GenerationStreamEvent {
    val key: GenerationStreamKey

    data class Start(
        override val key: GenerationStreamKey,
        val initialText: String = "",
    ) : GenerationStreamEvent

    data class Delta(
        override val key: GenerationStreamKey,
        val text: String,
    ) : GenerationStreamEvent

    data class Complete(
        override val key: GenerationStreamKey,
    ) : GenerationStreamEvent

    data class Fail(
        override val key: GenerationStreamKey,
        val message: String,
    ) : GenerationStreamEvent

    data class Cancel(
        override val key: GenerationStreamKey,
        val reason: String? = null,
    ) : GenerationStreamEvent
}

internal enum class GenerationStreamPhase {
    Active,
    Completed,
    Failed,
    Cancelled,
}

internal data class GenerationStreamState(
    val key: GenerationStreamKey,
    val text: String,
    val phase: GenerationStreamPhase,
    val terminalMessage: String? = null,
) {
    val isTerminal: Boolean
        get() = phase != GenerationStreamPhase.Active
}

/** Pure reducer for one replaceable, typed generation stream. */
internal object GenerationStreamReducer {
    fun reduce(
        state: GenerationStreamState?,
        event: GenerationStreamEvent,
    ): GenerationStreamState? = when (event) {
        is GenerationStreamEvent.Start -> reduceStart(state, event)
        else -> reduceActiveEvent(state, event)
    }

    private fun reduceStart(
        state: GenerationStreamState?,
        event: GenerationStreamEvent.Start,
    ): GenerationStreamState? {
        if (state != null && event.key.generationToken <= state.key.generationToken) {
            return state
        }
        return GenerationStreamState(
            key = event.key,
            text = event.initialText,
            phase = GenerationStreamPhase.Active,
        )
    }

    private fun reduceActiveEvent(
        state: GenerationStreamState?,
        event: GenerationStreamEvent,
    ): GenerationStreamState? {
        if (state == null || event.key != state.key || state.isTerminal) return state
        return when (event) {
            is GenerationStreamEvent.Delta -> state.copy(text = state.text + event.text)
            is GenerationStreamEvent.Complete -> state.copy(phase = GenerationStreamPhase.Completed)
            is GenerationStreamEvent.Fail -> state.copy(
                phase = GenerationStreamPhase.Failed,
                terminalMessage = event.message,
            )
            is GenerationStreamEvent.Cancel -> state.copy(
                phase = GenerationStreamPhase.Cancelled,
                terminalMessage = event.reason,
            )
            is GenerationStreamEvent.Start -> error("Start is handled before active events")
        }
    }
}

/**
 * Small thread-safe owner for callback-based generation code. It can be wired into
 * ChatGenerationSupport without exposing mutable reducer state to the ViewModel.
 */
internal class GenerationStreamCoordinator(
    initialState: GenerationStreamState? = null,
) {
    private var mutableState: GenerationStreamState? = initialState

    val state: GenerationStreamState?
        @Synchronized get() = mutableState

    @Synchronized
    fun dispatch(event: GenerationStreamEvent): GenerationStreamState? {
        mutableState = GenerationStreamReducer.reduce(mutableState, event)
        return mutableState
    }
}
