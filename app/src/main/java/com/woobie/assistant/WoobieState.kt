package com.woobie.assistant

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AssistantStatus {
    IDLE,
    ACTIVE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

enum class LogSource {
    USER,
    WOOBIE,
    SCREEN_OCR,
    SYSTEM
}

data class CommandLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val source: LogSource,
    val message: String
)

data class PermissionStatus(
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val audioGranted: Boolean = false,
    val screenCaptureGranted: Boolean = false
) {
    val allCoreGranted: Boolean
        get() = accessibilityGranted && overlayGranted && audioGranted
}

object WoobieState {
    private val _status = MutableStateFlow(AssistantStatus.IDLE)
    val status: StateFlow<AssistantStatus> = _status.asStateFlow()

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionStatus())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _commandLogs = MutableStateFlow<List<CommandLogEntry>>(
        listOf(
            CommandLogEntry(
                source = LogSource.SYSTEM,
                message = "Woobie Offline Core initialized. Grant permissions above to begin."
            )
        )
    )
    val commandLogs: StateFlow<List<CommandLogEntry>> = _commandLogs.asStateFlow()

    var mediaProjectionResultCode: Int = 0
    var mediaProjectionData: Intent? = null

    private val _incomingCommands = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val incomingCommands: SharedFlow<String> = _incomingCommands.asSharedFlow()

    fun setStatus(newStatus: AssistantStatus) {
        _status.value = newStatus
    }

    fun setOverlayRunning(running: Boolean) {
        _isOverlayRunning.value = running
    }

    fun updatePermissions(
        accessibility: Boolean = _permissions.value.accessibilityGranted,
        overlay: Boolean = _permissions.value.overlayGranted,
        audio: Boolean = _permissions.value.audioGranted,
        screenCapture: Boolean = _permissions.value.screenCaptureGranted
    ) {
        _permissions.value = PermissionStatus(
            accessibilityGranted = accessibility,
            overlayGranted = overlay,
            audioGranted = audio,
            screenCaptureGranted = screenCapture
        )
    }

    fun log(source: LogSource, message: String) {
        val entry = CommandLogEntry(source = source, message = message)
        _commandLogs.update { current ->
            (listOf(entry) + current).take(200)
        }
    }

    fun clearLogs() {
        _commandLogs.value = emptyList()
    }

    fun dispatchCommand(commandText: String) {
        if (commandText.isBlank()) return
        log(LogSource.USER, commandText)
        _incomingCommands.tryEmit(commandText.trim())
    }
}
