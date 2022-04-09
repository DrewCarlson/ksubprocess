import java.io.File

/**
 * Print the current directory, testing the switch.
 */
fun main() {
    println(File("dummy").absoluteFile.parent)
}
