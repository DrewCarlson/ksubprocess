package ksubprocess.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import ksubprocess.iop.write_no_sigpipe
import platform.posix.size_t
import platform.posix.ssize_t

internal actual fun writePipe(fd: Int, source: CPointer<ByteVar>, count: size_t): ssize_t =
    write_no_sigpipe(fd, source, count)
