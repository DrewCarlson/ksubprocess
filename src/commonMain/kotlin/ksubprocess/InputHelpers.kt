package ksubprocess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import okio.BufferedSource

internal fun BufferedSource?.lines(): Flow<String> {
    return if (this == null) {
        emptyFlow()
    } else {
        flow {
            while (!exhausted()) {
                emit(readUtf8Line() ?: return@flow)
            }
        }.onCompletion { close() }
    }
}
