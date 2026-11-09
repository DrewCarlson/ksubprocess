package ksubprocess

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat

internal actual val Process.stdoutPipeFd: Int
    get() = stdoutFd

internal actual fun fileMode(path: String): Int = memScoped {
    val st = alloc<stat>()
    check(stat(path, st.ptr) == 0)
    st.st_mode.toInt()
}
