package ksubprocess

import platform.posix.siginfo_t

internal actual val siginfo_t.exitCode: Int
    get() = _sifields._sigchld.si_status