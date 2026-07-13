import kotlin.native.arc.ArcDeinit

private var deinitCount = 0

private class Fluent {
    var total: Int = 0

    fun add(value: Int): Fluent {
        failIfNegative(value)
        total += value
        return this
    }

    fun branchReturningThis(value: Int, early: Boolean): Fluent {
        if (early) return this
        total += value
        return this
    }

    fun branchReturningDifferent(different: Boolean): Fluent {
        if (different) return Fluent()
        return this
    }

    @ArcDeinit
    private fun deinit() {
        deinitCount++
    }
}

private fun failIfNegative(value: Int) {
    if (value < 0) throw IllegalStateException("negative")
}

private fun barrier(value: Fluent) {
    check(value.total >= 0)
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:stringBuilderStyle#internal"
// CHECK: alloca %struct.ObjHeader*, i32 4
// CHECK: call void @EnterFrame({{.*}}i32 4)
// DEBUG-LABEL: define internal %struct.ObjHeader* @"kfun:stringBuilderStyle#internal"
private fun stringBuilderStyle(): String {
    val builder = StringBuilder()
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[SB_SEED:%[-a-zA-Z$._0-9]+]])
    // DEBUG: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[DEBUG_FIRST:%[-a-zA-Z$._0-9]+]])
    builder.append("a")
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[SB_SEED]])
    // DEBUG-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[DEBUG_FIRST]])
    // DEBUG: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[DEBUG_SECOND:%[-a-zA-Z$._0-9]+]])
    builder.append("b")
    // CHECK-NOT: call void @UpdateStackRef
    return builder.toString()
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:loweredStringTemplate#internal"
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_SEED:%[-a-zA-Z$._0-9]+]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_SEED]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_SEED]])
// DEBUG: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DEBUG_FIRST:%[-a-zA-Z$._0-9]+]])
// DEBUG-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DEBUG_FIRST]])
// DEBUG: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DEBUG_SECOND:%[-a-zA-Z$._0-9]+]])
// NOOPT-LABEL: define internal %struct.ObjHeader* @"kfun:loweredStringTemplate#internal"
// NOOPT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Any?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_NOOPT_FIRST:%[-a-zA-Z$._0-9]+]])
// NOOPT-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_NOOPT_FIRST]])
// NOOPT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_NOOPT_SECOND:%[-a-zA-Z$._0-9]+]])
// DIAGNOSTICS-LABEL: define internal %struct.ObjHeader* @"kfun:loweredStringTemplate#internal"
// DIAGNOSTICS: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DIAGNOSTICS_FIRST:%[-a-zA-Z$._0-9]+]])
// DIAGNOSTICS-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DIAGNOSTICS_FIRST]])
// DIAGNOSTICS: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_DIAGNOSTICS_SECOND:%[-a-zA-Z$._0-9]+]])
// STRICT-LABEL: define internal %struct.ObjHeader* @"kfun:loweredStringTemplate#internal"
// STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_STRICT_FIRST:%[-a-zA-Z$._0-9]+]])
// STRICT-NOT: @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_STRICT_FIRST]])
// STRICT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[CONCAT_STRICT_SECOND:%[-a-zA-Z$._0-9]+]])
private fun loweredStringTemplate(index: Int): String =
    "arc-${index and 1023}-${index.toString(16)}"

// CHECK-LABEL: define internal i32 @"kfun:nestedStringInitializer#internal"
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[NESTED_SEED:%[-a-zA-Z$._0-9]+]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[NESTED_SEED]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[NESTED_SEED]])
private fun nestedStringInitializer(index: Int): Int {
    val value = "v-${index}-x"
    return value.length
}

private fun throwingPart(fail: Boolean): String {
    if (fail) throw IllegalArgumentException("part")
    return "ok"
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:throwingStringTemplate#internal"
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[THROWING_SEED:%[-a-zA-Z$._0-9]+]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"({{.*}}%struct.ObjHeader** [[THROWING_SEED]])
private fun throwingStringTemplate(fail: Boolean): String = "a-${throwingPart(fail)}-z"

// CHECK-LABEL: define internal i32 @"kfun:groupedFluent#internal"
// CHECK: alloca %struct.ObjHeader*, i32 11
// CHECK: call void @EnterFrame({{.*}}i32 11)
private fun groupedFluent(fail: Boolean): Int {
    val first = Fluent()
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[FIRST_SEGMENT:%[-a-zA-Z$._0-9]+]])
    first.add(1)
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[FIRST_SEGMENT]])
    first.add(2)
    barrier(first)
    // CHECK-NOT: @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[FIRST_SEGMENT]])
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[AFTER_BARRIER:%[-a-zA-Z$._0-9]+]])
    first.add(3)

    val second = Fluent()
    // CHECK-NOT: @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[AFTER_BARRIER]])
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[SECOND_RECEIVER:%[-a-zA-Z$._0-9]+]])
    second.add(4)
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[SECOND_RECEIVER]])
    second.add(if (fail) -1 else 5)

    // A used result is not a discarded statement and must not consume a segment seed.
    // CHECK-NOT: @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[SECOND_RECEIVER]])
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[OBSERVED_RESULT:%[-a-zA-Z$._0-9]+]])
    val observed = second.add(6)
    check(observed === second)
    // CHECK: call void @LeaveFrame
    return first.total + second.total
}

// CHECK-LABEL: define internal i32 @"kfun:branchProofs#internal"
private fun branchProofs(): Int {
    val value = Fluent()
    // Every normal branch exit returns this, so these calls may share one exact segment seed.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.branchReturningThis#internal"({{.*}}%struct.ObjHeader** [[BRANCH_THIS:%[-a-zA-Z$._0-9]+]])
    value.branchReturningThis(7, true)
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.branchReturningThis#internal"({{.*}}%struct.ObjHeader** [[BRANCH_THIS]])
    value.branchReturningThis(8, false)

    // One possible non-receiver return invalidates the proof and retains per-call result slots.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.branchReturningDifferent#internal"({{.*}}%struct.ObjHeader** [[BRANCH_OTHER_FIRST:%[-a-zA-Z$._0-9]+]])
    value.branchReturningDifferent(false)
    // CHECK-NOT: @"kfun:Fluent.branchReturningDifferent#internal"({{.*}}%struct.ObjHeader** [[BRANCH_OTHER_FIRST]])
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.branchReturningDifferent#internal"({{.*}}%struct.ObjHeader** [[BRANCH_OTHER_SECOND:%[-a-zA-Z$._0-9]+]])
    value.branchReturningDifferent(false)
    return value.total
}

private fun argumentOrThrow(fail: Boolean): Int {
    if (fail) throw IllegalArgumentException("argument")
    return 9
}

// CHECK-LABEL: define internal i32 @"kfun:groupedArgumentThrow#internal"
// CHECK: alloca %struct.ObjHeader*, i32 5
// CHECK: call void @EnterFrame({{.*}}i32 5)
private fun groupedArgumentThrow(fail: Boolean): Int {
    val value = Fluent()
    // The first candidate's argument may throw before Fluent.add initializes the shared seed.
    // Its compiler-created anonymous frame slot remains zero and is safe to unwind.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[ARGUMENT_THROW_SEGMENT:%[-a-zA-Z$._0-9]+]])
    value.add(argumentOrThrow(fail))
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.add#internal"({{.*}}%struct.ObjHeader** [[ARGUMENT_THROW_SEGMENT]])
    value.add(1)
    return value.total
}

fun main() {
    check(stringBuilderStyle() == "ab")
    check(loweredStringTemplate(7) == "arc-7-7")
    check(nestedStringInitializer(7) == 5)
    check(throwingStringTemplate(false) == "a-ok-z")
    try {
        throwingStringTemplate(true)
        error("expected string argument failure")
    } catch (_: IllegalArgumentException) {
        // The shared seed is a normal frame slot and must unwind exactly once.
    }
    check(groupedFluent(false) == 21)
    check(deinitCount == 2)
    check(branchProofs() == 8)
    check(deinitCount == 3)
    check(groupedArgumentThrow(false) == 10)
    check(deinitCount == 4)
    try {
        groupedArgumentThrow(true)
        error("expected argument failure")
    } catch (_: IllegalArgumentException) {
        check(deinitCount == 5)
    }
    try {
        groupedFluent(true)
        error("expected failure")
    } catch (_: IllegalStateException) {
        check(deinitCount == 7)
    }
    println("OK")
}
