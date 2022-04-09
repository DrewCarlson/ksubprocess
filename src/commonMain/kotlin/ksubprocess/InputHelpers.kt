package ksubprocess

import io.ktor.utils.io.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

internal fun Input?.lines(): Flow<String> = if (this == null) {
    emptyFlow()
} else {
    flow {
        while (true) {
            emit(readUTF8Line() ?: return@flow)
        }
    }
}
