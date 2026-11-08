package ksubprocess

import ksubprocess.io.WindowsException
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.ERROR_NO_DATA

internal actual fun Throwable.isBrokenPipe(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is WindowsException && (it.errorCode.toInt() == ERROR_NO_DATA || it.errorCode.toInt() == ERROR_BROKEN_PIPE)
    }
