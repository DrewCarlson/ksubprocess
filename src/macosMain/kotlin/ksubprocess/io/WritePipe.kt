package ksubprocess.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import platform.posix.size_t
import platform.posix.ssize_t
import platform.posix.write

// stdin pipes are created with F_SETNOSIGPIPE, so a plain write reports EPIPE
internal actual fun writePipe(fd: Int, source: CPointer<ByteVar>, count: size_t): ssize_t =
    write(fd, source, count)
