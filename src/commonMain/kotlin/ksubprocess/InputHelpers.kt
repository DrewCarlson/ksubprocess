package ksubprocess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.io.Source
import kotlinx.io.readLine

internal fun Source?.lines(): Flow<String> {
    return if (this == null) {
        emptyFlow()
    } else {
        flow {
            while (!exhausted()) {
                emit(readLine() ?: return@flow)
            }
        }.onCompletion { close() }
    }
}
