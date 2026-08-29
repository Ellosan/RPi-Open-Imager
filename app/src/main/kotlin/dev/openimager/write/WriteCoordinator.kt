package dev.openimager.write

import dev.openimager.core.image.ImageSource
import dev.openimager.core.image.WriteOptions
import dev.openimager.core.image.WriteProgress
import dev.openimager.core.image.WriteResult
import dev.openimager.storage.StorageTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything [WriteService] needs to run one write. */
data class WriteRequest(
    val source: ImageSource,
    val target: StorageTarget,
    val options: WriteOptions,
    val imageLabel: String,
    val targetLabel: String,
)

sealed interface WriteState {
    data object Idle : WriteState

    data class Running(
        val progress: WriteProgress,
        val imageLabel: String,
        val targetLabel: String,
        val sourceBytes: Long,
    ) : WriteState

    data class Finished(
        val result: WriteResult,
        val imageLabel: String,
        val targetLabel: String,
    ) : WriteState

    data class Failed(val message: String, val targetLabel: String) : WriteState

    data object Cancelled : WriteState
}

/**
 * The single place the UI and the foreground service meet. The request is handed over in process
 * rather than through the intent, because an open USB connection cannot be put in a Bundle.
 */
object WriteCoordinator {

    private val _state = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = _state.asStateFlow()

    @Volatile
    private var pending: WriteRequest? = null

    fun submit(request: WriteRequest) {
        pending = request
        _state.value = WriteState.Running(
            progress = WriteProgress(dev.openimager.core.image.WritePhase.PREPARING, 0, 0, 0),
            imageLabel = request.imageLabel,
            targetLabel = request.targetLabel,
            sourceBytes = request.source.compressedSize,
        )
    }

    fun consume(): WriteRequest? = pending.also { pending = null }

    fun update(state: WriteState) {
        _state.value = state
    }

    fun reset() {
        if (_state.value !is WriteState.Running) _state.value = WriteState.Idle
    }

    val isRunning: Boolean get() = _state.value is WriteState.Running
}
