import kotlin.system.exitProcess

/**
 * Dump all arguments to stdout, separated by '#' (which is a character that shouldn't be part of any arguments)
 */
fun main(args: Array<String>) {
    if (args.any { '#' in it }) {
        System.err.println("Invalid arguments")
        exitProcess(1)
    }

    print(args.joinToString(separator = "#"))
}
