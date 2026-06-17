// LANGUAGE: +CompanionBlocksAndExtensions
// IGNORE_BACKEND: NATIVE
// NATIVE initializes the child companion before its superclass companion (`CP` instead of `PC`)
// WASM_IGNORE_FOR: mode=single-module
// Wasm KT-87329
// WASM_IGNORE_FOR: mode=multi-module
// Wasm KT-87109

// MODULE: lib
// FILE: lib.kt
var initLog = ""

open class Parent {
    companion {
        val parentValue = run {
            initLog += "P"
            "parent"
        }
    }
}

// MODULE: main(lib)
// FILE: main.kt
class Child : Parent() {
    companion {
        val childValue = run {
            initLog += "C"
            "child"
        }
    }
}

fun box(): String {
    if (Child.childValue != "child") return "FAIL: child"
    if (Parent.parentValue != "parent") return "FAIL: parent"
    if (initLog != "PC") return "FAIL: order: $initLog"

    return "OK"
}
