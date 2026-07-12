import kotlin.concurrent.Volatile
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private class Node(val value: Int, var next: Node?)

private fun canonicalTraversal(head: Node, steps: Int): Int {
    var cursor = head
    var sum = cursor.value
    repeat(steps) {
        cursor = cursor.next!!
        sum += cursor.value
    }
    return sum
}

private fun escapeFieldResult(owner: Node): Node = owner.next!!

private fun observe(node: Node): Int = node.value

private fun inspectCharBuffer(value: CharArray): Int = value.size

private class CharBufferOwner(initial: CharArray) {
    private var buffer = initial

    fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity > buffer.size) buffer = buffer.copyOf(requiredCapacity)
    }

    fun verifyFailedCopyPreservesBuffer(): Int {
        var failedAsExpected = false
        try {
            buffer.copyOf(-1)
        } catch (_: RuntimeException) {
            failedAsExpected = true
        }
        check(failedAsExpected) { "copyOf with a negative capacity must fail" }
        return buffer.size
    }

    fun inspectThroughUnknownConsumer(): Int = inspectCharBuffer(buffer)

    fun copyAfterReplacingInSuffix(replacement: CharArray, newSize: Int): CharArray =
        buffer.copyOf(run {
            buffer = replacement
            newSize
        })
}

private fun interveningCall(head: Node): Int {
    var cursor = head
    cursor = run {
        val projected = cursor.next!!
        observe(cursor)
        projected
    }
    return cursor.value
}

private fun capturedOwner(head: Node): Int {
    var cursor = head
    val readOwner = { cursor.value }
    cursor = cursor.next!!
    return readOwner() + cursor.value
}

private class VolatileNode(val value: Int, next: VolatileNode?) {
    @Volatile
    var next: VolatileNode? = next
}

private fun volatileField(head: VolatileNode): Int {
    var cursor = head
    cursor = cursor.next!!
    return cursor.value
}

private class WeakNode(val value: Int, next: WeakNode?) {
    @ArcWeak
    var next: WeakNode? = next
}

private fun weakField(head: WeakNode): Int {
    var cursor = head
    cursor = cursor.next!!
    return cursor.value
}

private class UnownedTarget(val value: Int)

private class UnownedHolder(next: UnownedTarget) {
    @ArcUnowned
    var next: UnownedTarget = next
}

private fun unownedField(holder: UnownedHolder, initial: UnownedTarget): Int {
    var target = initial
    target = holder.next
    return target.value
}

private suspend fun suspendField(head: Node): Int {
    var cursor = head
    cursor = cursor.next!!
    return cursor.value
}

private var finallyCount = 0

private fun tryField(head: Node): Int {
    var cursor = head
    try {
        cursor = cursor.next!!
    } finally {
        finallyCount++
    }
    return cursor.value
}

private fun fieldWrite(head: Node, replacement: Node): Int {
    var cursor = head
    cursor = run {
        val projected = cursor.next!!
        cursor.next = replacement
        projected
    }
    return cursor.value
}

private fun differentTarget(head: Node): Int {
    var cursor = head
    var target = head
    target = cursor.next!!
    return cursor.value + target.value
}

private fun runSuspend(block: suspend () -> Int): Int {
    var outcome: Result<Int>? = null
    block.startCoroutine(object : Continuation<Int> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Int>) {
            outcome = result
        }
    })
    return outcome!!.getOrThrow()
}

private class LifetimeNode(val value: Int, var next: LifetimeNode?)

private class LifetimeProbe(owner: LifetimeNode) {
    @ArcWeak
    var target: LifetimeNode? = owner
}

private fun differentObjectLifetime(): Int {
    var cursor = LifetimeNode(10, LifetimeNode(32, null))
    val oldOwner = LifetimeProbe(cursor)
    val projectedTarget = LifetimeProbe(cursor.next!!)
    cursor = cursor.next!!
    check(oldOwner.target == null) { "the replaced owner must release eagerly" }
    check(projectedTarget.target === cursor) { "the field target must be retained before releasing its owner" }
    return cursor.value
}

private fun sameObjectLifetime(): Int {
    var cursor = LifetimeNode(42, null)
    val target = LifetimeProbe(cursor)
    cursor.next = cursor
    cursor = cursor.next!!
    check(target.target === cursor) { "self replacement must not prematurely destroy its target" }
    cursor.next = null
    check(target.target === cursor) { "the local remains the sole owner after breaking the self cycle" }
    return cursor.value
}

fun main() {
    val tail = Node(3, null)
    val middle = Node(2, tail)
    val head = Node(1, middle)
    check(canonicalTraversal(head, 2) == 6)
    check(escapeFieldResult(head) === middle)
    check(interveningCall(head) == 2)
    check(capturedOwner(head) == 4)

    val charBufferOwner = CharBufferOwner(CharArray(1))
    check(charBufferOwner.verifyFailedCopyPreservesBuffer() == 1)
    charBufferOwner.ensureCapacity(8)
    check(charBufferOwner.inspectThroughUnknownConsumer() == 8)
    val original = charArrayOf('o', 'k')
    val replacement = charArrayOf('x')
    val suffixOwner = CharBufferOwner(original)
    val copiedOriginal = suffixOwner.copyAfterReplacingInSuffix(replacement, original.size)
    check(copiedOriginal.size == 2 && copiedOriginal[0] == 'o' && copiedOriginal[1] == 'k')
    check(suffixOwner.inspectThroughUnknownConsumer() == replacement.size)

    val volatileTail = VolatileNode(5, null)
    check(volatileField(VolatileNode(4, volatileTail)) == 5)

    val weakTail = WeakNode(7, null)
    check(weakField(WeakNode(6, weakTail)) == 7)

    val unownedTarget = UnownedTarget(9)
    check(unownedField(UnownedHolder(unownedTarget), UnownedTarget(0)) == 9)

    check(runSuspend { suspendField(head) } == 2)
    check(tryField(head) == 2)
    check(finallyCount == 1)
    check(fieldWrite(Node(10, middle), tail) == 2)
    check(differentTarget(head) == 3)

    check(differentObjectLifetime() == 32)
    check(sameObjectLifetime() == 42)

    println("ARC_FIELD_PROJECTION_OK")
}
