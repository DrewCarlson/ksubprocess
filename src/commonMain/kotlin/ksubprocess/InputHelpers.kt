package ksubprocess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import okio.BufferedSource

internal fun BufferedSource?.lines(): Flow<String> = if (this == null) {
    emptyFlow()
} else {
    flow {
        while (true) {
            emit(readUtf8Line() ?: return@flow)
        }
    }
}
