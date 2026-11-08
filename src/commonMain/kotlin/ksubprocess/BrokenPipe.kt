package ksubprocess

/** True if [this] or one of its causes reports writing to a pipe whose reader is gone. */
internal expect fun Throwable.isBrokenPipe(): Boolean
