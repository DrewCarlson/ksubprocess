import kotlin.system.exitProcess

/**
 * Exit with the code given in args[0]
 */
fun main(args: Array<String>) {
    val ec = args.single().toInt()
    exitProcess(ec)
}
