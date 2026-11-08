package ksubprocess

import ksubprocess.io.PosixException
import platform.posix.EPIPE

internal actual fun Throwable.isBrokenPipe(): Boolean =
    generateSequence(this) { it.cause }.any { it is PosixException && it.errno == EPIPE }
