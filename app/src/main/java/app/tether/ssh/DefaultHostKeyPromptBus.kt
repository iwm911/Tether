package app.tether.ssh

import app.tether.core.HostKeyPrompt
import app.tether.core.HostKeyPromptBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands host-key trust decisions from the SSH layer to the root-level dialog. Prompts are
 * serialised: while one is on screen, other connections wait their turn. Unanswered prompts
 * resolve to "don't trust" after [TIMEOUT_MS].
 */
class DefaultHostKeyPromptBus : HostKeyPromptBus {

    private val mutex = Mutex()
    private val state = MutableStateFlow<HostKeyPrompt?>(null)
    @Volatile private var current: Pair<String, CompletableDeferred<Boolean>>? = null

    override val pending: StateFlow<HostKeyPrompt?> = state.asStateFlow()

    override suspend fun ask(prompt: HostKeyPrompt): Boolean = mutex.withLock {
        val answer = CompletableDeferred<Boolean>()
        current = prompt.id to answer
        state.value = prompt
        try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: false
        } finally {
            current = null
            if (state.value?.id == prompt.id) state.value = null
        }
    }

    override fun answer(promptId: String, trust: Boolean) {
        val c = current ?: return
        if (c.first == promptId) c.second.complete(trust)
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
