@file:Suppress("DEPRECATION")

import kotlin.native.concurrent.FreezableAtomicReference

private class StrictPayload(val value: Int)

// STRICT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"
// STRICT-SAME: {{.*}}%struct.ObjHeader** [[STRICT_PRODUCER_SLOT:%[-a-zA-Z$._0-9]+]])
// STRICT-COUNT-1: call %struct.ObjHeader* @Kotlin_AtomicReference_get({{.*}}%struct.ObjHeader** [[STRICT_PRODUCER_SLOT]])
// STRICT: store %struct.ObjHeader* {{.*}}, %struct.ObjHeader** [[STRICT_PRODUCER_SLOT]]
// STRICT: ret %struct.ObjHeader*
private fun readStrict(reference: FreezableAtomicReference<StrictPayload>): Int = reference.value.value

fun main() {
    check(readStrict(FreezableAtomicReference(StrictPayload(7))) == 7)
}
