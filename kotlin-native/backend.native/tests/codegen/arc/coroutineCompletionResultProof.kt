import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.startCoroutine

private var pending: Continuation<Int>? = null

private suspend fun delayed(): Int = suspendCoroutineUninterceptedOrReturn { continuation ->
    pending = continuation
    COROUTINE_SUSPENDED
}

private suspend fun twoStep(): Int = delayed() + delayed()

fun main() {
    var outcome: Result<Int>? = null
    suspend { twoStep() }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<Int>) {
            outcome = result
        }
    })

    check(outcome == null)
    pending!!.resumeWith(Result.success(20))
    check(outcome == null)
    pending!!.resumeWith(Result.success(21))
    check(outcome!!.getOrThrow() == 41)
    println("ARC_COROUTINE_COMPLETION_RESULT_PROOF_OK result=41")
}
