package ksubprocess.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.POLLIN
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.write

// stdin pipes are created with F_SETNOSIGPIPE, so a plain write reports EPIPE
internal actual fun writePipe(fd: Int, source: CPointer<ByteVar>, count: Int): Int =
    write(fd, source, count.convert()).toInt()

internal actual fun pollReadable(fd: Int, timeoutMs: Int): Int = memScoped {
    val pfd = alloc<pollfd>()
    pfd.fd = fd
    pfd.events = POLLIN.toShort()
    poll(pfd.ptr, 1.convert(), timeoutMs)
}
