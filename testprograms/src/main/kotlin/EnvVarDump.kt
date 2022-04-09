/**
 * For every argument, dumps the named environment variable. Missing vars will print null.
 */
fun main(args: Array<String>) {
    println(args.joinToString("\n") { System.getenv(it) ?: "<NOT-SET>" })
}
