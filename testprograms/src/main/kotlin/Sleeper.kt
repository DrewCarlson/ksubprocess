/**
 * Simply sleeps for <arg> seconds. Allows to test timeouts.
 */
fun main(args: Array<String>) {
    Thread.sleep(args.single().toLong() * 1000)
}
