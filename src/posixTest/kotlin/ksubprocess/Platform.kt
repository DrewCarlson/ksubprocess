package ksubprocess

// Platform types differ in width between Linux and macOS, so shared tests reach them through these.

/** The parent's read end of the stdout pipe. */
internal expect val Process.stdoutPipeFd: Int

/** The mode bits of the file at [path]. */
internal expect fun fileMode(path: String): Int
