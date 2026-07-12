import kotlin.native.arc.ArcWeak

private class RootedLoopNode(val value: Int, var next: RootedLoopNode?)

private class RootedLoopProbe(owner: RootedLoopNode) {
    @ArcWeak
    var target: RootedLoopNode? = owner
}

private class AcyclicScopeResult(
    val anchorProbe: RootedLoopProbe,
    val targetProbe: RootedLoopProbe,
    val checksum: Long,
)

private fun guaranteedAnchorTraversal(anchor: RootedLoopNode, steps: Int): Long {
    var cursor = anchor
    var checksum = 0L
    var index = 0
    while (index < steps) {
        checksum += cursor.value
        cursor = cursor.next!!
        index++
    }
    return checksum
}

private fun boundedStackCycleTraversal(firstValue: Int, secondValue: Int, steps: Int): Long {
    val first = RootedLoopNode(firstValue, null)
    val second = RootedLoopNode(secondValue, first)
    first.next = second
    var cursor = first
    var checksum = 0L
    var index = 0
    while (index < steps) {
        checksum += cursor.value
        cursor = cursor.next!!
        index++
    }
    return checksum
}

private fun traversePastNull(anchor: RootedLoopNode) {
    var cursor = anchor
    var index = 0
    while (index < 4) {
        cursor = cursor.next!!
        index++
    }
}

private fun nullEdgePreservesAnchor(anchor: RootedLoopNode): Int {
    return try {
        traversePastNull(anchor)
        error("the fourth edge must be null")
    } catch (_: NullPointerException) {
        check(anchor.value == 5) { "null-edge: guaranteed anchor changed during throw" }
        check(anchor.next?.value == 7) { "null-edge: anchor no longer owns its first target" }
        anchor.value + anchor.next!!.value
    }
}

private fun createAndTraverseAcyclicScope(recursionGuard: Int = 0): AcyclicScopeResult {
    // The cold recursive edge prevents Kotlin/LLVM from inlining away the owning helper frame.
    if (recursionGuard < 0) return createAndTraverseAcyclicScope(recursionGuard + 1)
    val anchor = RootedLoopNode(
        5,
        RootedLoopNode(7, RootedLoopNode(11, RootedLoopNode(17, null))),
    )
    val anchorProbe = RootedLoopProbe(anchor)
    val targetProbe = RootedLoopProbe(anchor.next!!.next!!)
    val checksum = guaranteedAnchorTraversal(anchor, 3)
    check(checksum == 23L) { "acyclic: rooted traversal checksum was $checksum" }
    check(anchorProbe.target === anchor) { "acyclic: guaranteed anchor died during traversal" }
    check(targetProbe.target?.value == 11) { "acyclic: projected target died while anchor was live" }
    check(nullEdgePreservesAnchor(anchor) == 12) { "acyclic: null-edge catch result changed" }
    println("ARC_ROOTED_LOOP_STAGE acyclic-live")
    return AcyclicScopeResult(anchorProbe, targetProbe, checksum)
}

private fun verifyAcyclicWeakLifetimes(): Long {
    val result = createAndTraverseAcyclicScope()
    // Same-frame last-use elimination for anonymous temporaries is a separate optimizer concern.
    // Zeroing is required here because the helper's sole owning frame has definitely exited.
    check(result.anchorProbe.target == null) { "acyclic: released anchor remained alive after owner frame exit" }
    check(result.targetProbe.target == null) { "acyclic: target remained alive after owner frame exit" }
    println("ARC_ROOTED_LOOP_STAGE acyclic-released")
    return result.checksum
}

private fun verifyStrongCycleRemainsRetained(): Long {
    var first: RootedLoopNode? = RootedLoopNode(13, null)
    var second: RootedLoopNode? = RootedLoopNode(29, first)
    first!!.next = second
    val firstProbe = RootedLoopProbe(first!!)
    val secondProbe = RootedLoopProbe(second!!)
    val checksum = guaranteedAnchorTraversal(first!!, 4)
    check(checksum == 84L) { "retained-cycle: rooted traversal checksum was $checksum" }
    check(firstProbe.target != null) { "retained-cycle: first node died while explicit roots were live" }
    check(secondProbe.target != null) { "retained-cycle: second node died while explicit roots were live" }
    println("ARC_ROOTED_LOOP_STAGE retained-cycle-live")
    first = null
    second = null
    check(firstProbe.target != null) { "retained-cycle: first node was collected after roots were cleared" }
    check(secondProbe.target != null) { "retained-cycle: second node was collected after roots were cleared" }
    println("ARC_ROOTED_LOOP_STAGE retained-cycle-roots-cleared")
    return checksum
}

fun main() {
    val rootedChecksum = verifyAcyclicWeakLifetimes()
    println("ARC_ROOTED_LOOP_STAGE stack-cycle-start")
    val stackChecksum = boundedStackCycleTraversal(13, 29, 10_000)
    check(stackChecksum == 210_000L) { "stack-cycle: traversal checksum was $stackChecksum" }
    println("ARC_ROOTED_LOOP_STAGE stack-cycle-complete")
    val retainedCycleChecksum = verifyStrongCycleRemainsRetained()
    val checksum = rootedChecksum + stackChecksum + retainedCycleChecksum
    check(checksum == 210_107L) { "combined: rooted-loop checksum was $checksum" }
    println("ARC_ROOTED_LOOP_OK checksum=$checksum")
}
