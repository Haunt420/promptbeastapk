package promptbeast

import kotlin.system.exitProcess

/** Runs every golden suite without JUnit (used by tools/kotlinc-local.sh). */
fun main(args: Array<String>) {
    val only = args.toSet()
    var bad = 0
    for (rep in GoldenSuite.all()) {
        if (only.isNotEmpty() && rep.name !in only) continue
        println(rep)
        rep.failures.forEach { println("  FAIL $it") }
        bad += rep.failures.size
    }
    if (only.isEmpty() || "aihttp" in only) {
        val rep = AiHttpSuite.run()
        println(rep)
        rep.failures.forEach { println("  FAIL $it") }
        bad += rep.failures.size
    }
    if (bad > 0) exitProcess(1)
    println("ALL GOLDEN SUITES PASS")
}
