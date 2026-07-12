private class StrictFluent {
    fun touch(): StrictFluent = this
}

// STRICT-LABEL: define internal void @"kfun:strictDiscardedReturnedReceivers#internal"
private fun strictDiscardedReturnedReceivers() {
    val value = StrictFluent()
    // STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:StrictFluent.touch#internal"({{.*}}%struct.ObjHeader** [[STRICT_FIRST:%[-a-zA-Z$._0-9]+]])
    value.touch()
    // STRICT-NOT: @"kfun:StrictFluent.touch#internal"({{.*}}%struct.ObjHeader** [[STRICT_FIRST]])
    // STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:StrictFluent.touch#internal"({{.*}}%struct.ObjHeader** [[STRICT_SECOND:%[-a-zA-Z$._0-9]+]])
    value.touch()
}

fun main() {
    strictDiscardedReturnedReceivers()
    println("OK")
}
