// IGNORE_BACKEND: JS_IR, JS_IR_ES6, WASM_JS, WASM_WASI
// FILE: lib.kt
val x: String = computeX()

fun computeX(): String = throw IllegalStateException("1")

val y: String = computeY()

fun computeY(): String = "2"

// FILE: main.kt
fun box() : String {
    try {
        x
        return "FAIL 1.1"
    } catch(t: Error) {
        val cause = t.cause
        if (cause !is IllegalStateException) return "FAIL 1.2: cause must be IllegalStateException, was ${cause?.let { it::class }}"
        if (cause.message != "1") return "FAIL 1.3: message must be '1', was '${cause.message}'"
    }
    try {
        y
        return "FAIL 2.1"
    } catch(t: Error) {
        if (JDK_MAJOR_VERSION < 20 || BACKEND_UNDER_TEST == "NATIVE") {
            if (t.cause != null) return "FAIL 2.2.2: cause must be null, got ${t.cause}"
        } else {
            val cause = t.cause as? Error
            if (cause == null) return "FAIL 2.3.2: cause must be Error, was ${t.cause}"
            if (cause.cause !is IllegalStateException) return "FAIL 2.3.3: cause.cause must be IllegalStateException, was ${cause?.let { it::class }}"
            if (cause.message != "1") return "FAIL 2.3.4: cause.message must be '1', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "JVM_IR" -> "Could not initialize class LibKt"
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize file"
        }
        if (t.message != expectedMessage) return "FAIL 2.4: message must be '$expectedMessage', was '${t.message}'"
    }
    return "OK"
}
