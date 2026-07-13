// STRICT-LABEL: define internal %struct.ObjHeader* @"kfun:generatedStringTemplate#internal"
// STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[STRICT_FIRST:%[-a-zA-Z$._0-9]+]])
// STRICT-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[STRICT_FIRST]])
// STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[STRICT_SECOND:%[-a-zA-Z$._0-9]+]])
private fun generatedStringTemplate(index: Int): String = "arc-${index}-tail"

fun main() {
    check(generatedStringTemplate(7) == "arc-7-tail")
}
