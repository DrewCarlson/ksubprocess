import kotlinx.coroutines.delay
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val lines = args.getOrNull(0)?.toIntOrNull() ?: 100

    repeat(lines) { i ->
        println("Output line $i: This is a test line with some content")
        delay(5)
    }
    
    exitProcess(0)
}
