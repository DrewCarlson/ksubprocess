import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val lines = args.getOrNull(0)?.toIntOrNull() ?: 100

    repeat(lines) { i ->
        println("Output line $i: This is a test line with some content")
        Thread.sleep(10)
    }
    
    exitProcess(0)
}
