import java.io.*

/**
 * Reads from stdin and writes to stdout
 */
fun main() {
    while (true) {
        val line = try {
            readlnOrNull() ?: break
        } catch (_: IOException) {
            break
        }
        println(line)
    }
}
