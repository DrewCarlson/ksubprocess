package ksubprocess

import java.io.IOException

private val brokenPipeMessages = listOf(
    "Broken pipe",
    "Stream closed",
    "The pipe is being closed",
    "The pipe has been ended",
)

internal actual fun Throwable.isBrokenPipe(): Boolean =
    generateSequence(this) { it.cause }.any { e ->
        e is IOException && brokenPipeMessages.any { e.message?.contains(it, ignoreCase = true) == true }
    }
