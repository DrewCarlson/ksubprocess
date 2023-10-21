package ksubprocess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okio.BufferedSource
import kotlin.coroutines.coroutineContext

internal fun BufferedSource?.lines(): Flow<String> = if (this == null) {
    emptyFlow()
} else {
    flow {
        while (coroutineContext.isActive) {
            emit(readUtf8Line() ?: return@flow)
        }
    }
}
