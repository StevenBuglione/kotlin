import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:immediate#internal"
// OPT-NOT: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// OPT: ret %struct.ObjHeader*
// DEBUG-LABEL: define internal %struct.ObjHeader* @"kfun:immediate#internal"
// DEBUG: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// DEBUG: ret %struct.ObjHeader*
// DIAGNOSTICS-LABEL: define internal %struct.ObjHeader* @"kfun:immediate#internal"
// DIAGNOSTICS: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// DIAGNOSTICS: ret %struct.ObjHeader*
// STRICT-LABEL: define internal %struct.ObjHeader* @"kfun:immediate#internal"
// STRICT: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// STRICT: ret %struct.ObjHeader*
private suspend fun immediate(value: Int): Int =
    suspendCoroutine { continuation -> continuation.resume(value + 1) }

// A structurally identical inlined Result.success receiver outside a lowered coroutine must fall back.
// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:ordinaryResult#internal"
// OPT: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// OPT: ret %struct.ObjHeader*
private fun ordinaryResult(value: Int): Result<Int> = Result.success(value)

// An observed Result companion in a coroutine must remain owned even though the declaration identities match.
// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:observedCompanion#internal"
// OPT: @"kfun:kotlin.Result#<get-$companion>#static(){}kotlin.Result.Companion"
// OPT: ret %struct.ObjHeader*
private suspend fun observedCompanion(): Any =
    suspendCoroutine { continuation ->
        val observed: Any = Result
        continuation.resume(observed)
    }

private class ResultLookalike {
    companion object {
        fun success(value: Int): Int = value
    }
}

// A companion/getter/inlined-receiver lookalike in a lowered coroutine must fail symbol identity.
// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:lookalikeCompanion#internal"
// OPT: @"kfun:ResultLookalike#<get-companion>#static(){}ResultLookalike.Companion"
// OPT: ret %struct.ObjHeader*
private suspend fun lookalikeCompanion(): Int =
    suspendCoroutine { continuation -> continuation.resume(ResultLookalike.success(44)) }

private class Completion<T> : Continuation<T> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var outcome: Result<T>? = null
    override fun resumeWith(result: Result<T>) { outcome = result }
}

fun main() {
    val immediateCompletion = Completion<Int>()
    suspend { immediate(41) }.startCoroutine(immediateCompletion)
    check(immediateCompletion.outcome?.getOrThrow() == 42)
    check(ordinaryResult(43).getOrThrow() == 43)

    val observedCompletion = Completion<Any>()
    suspend { observedCompanion() }.startCoroutine(observedCompletion)
    check(observedCompletion.outcome?.getOrThrow() === Result)

    val lookalikeCompletion = Completion<Int>()
    suspend { lookalikeCompanion() }.startCoroutine(lookalikeCompletion)
    check(lookalikeCompletion.outcome?.getOrThrow() == 44)
}
