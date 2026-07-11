import arc.compat.prebuilt.prebuiltValue

fun main() {
    check(prebuiltValue().value == 42)
    println("PREBUILT_KLIB_OK")
}
