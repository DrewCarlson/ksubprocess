import java.io.*

/**
 * Reads from stdin and writes to stdout
 */
fun main() {
    while (true) {
        val line = try {
            readLine() ?: break
        } catch (e: IOException) {
            break
        }
        println(line)
    }
}
