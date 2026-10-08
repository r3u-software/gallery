package com.example.gallery.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data object NoWifi : ServerState
    data class Running(val url: String, val ip: String, val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/** Process-wide server status shared between the foreground service and the Compose UI. */
object ServerStateHolder {
    var state: ServerState by mutableStateOf(ServerState.Stopped)
}
