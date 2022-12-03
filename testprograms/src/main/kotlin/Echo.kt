import java.io.*

/**
 * Reads from stdin and writes to stdout
 */
fun main() {
    while (true) {
        val line = try {
            readlnOrNull() ?: break
        } catch (e: IOException) {
            break
        }
        println(line)
    }
}
