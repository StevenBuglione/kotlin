// WITH_STDLIB

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

@Serializable
class PrimitiveArrayHolder(val a: IntArray = intArrayOf())

@Serializable
class ObjectArrayHolder(val a: Array<String> = arrayOf("x", "y"))

@Serializable
class NestedArrayHolder(val a: Array<IntArray> = arrayOf(intArrayOf(1), intArrayOf(2, 3)))

@Serializable
class NullableArrayHolder(val a: Array<String>? = null)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
class UnsignedArrayHolder(val a: UIntArray = uintArrayOf(1u, 2u))

@Serializable
class AlwaysHolder(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val a: IntArray = intArrayOf())

private val encodeAll = Json { encodeDefaults = true }

fun box(): String {
    // Primitive array: content equal to the (empty) default is omitted, different content is emitted.
    assertEquals("{}", Json.encodeToString(PrimitiveArrayHolder()))
    assertEquals("{}", Json.encodeToString(PrimitiveArrayHolder(intArrayOf())))
    assertEquals("""{"a":[7]}""", Json.encodeToString(PrimitiveArrayHolder(intArrayOf(7))))

    // Object array: content-equal-to-default omitted (different array instance, equal contents), differing contents emitted.
    assertEquals("{}", Json.encodeToString(ObjectArrayHolder(arrayOf("x", "y"))))
    assertEquals("""{"a":["z"]}""", Json.encodeToString(ObjectArrayHolder(arrayOf("z"))))

    // Nested object array: deep content equality. Inner arrays are fresh instances yet equal -> omitted.
    assertEquals("{}", Json.encodeToString(NestedArrayHolder(arrayOf(intArrayOf(1), intArrayOf(2, 3)))))
    // Same outer shape but a differing inner array must still be emitted (verifies deep, not shallow, comparison).
    assertEquals("""{"a":[[1],[9,9]]}""", Json.encodeToString(NestedArrayHolder(arrayOf(intArrayOf(1), intArrayOf(9, 9)))))

    // Nullable array: null vs null default omitted, a non-null value emitted.
    assertEquals("{}", Json.encodeToString(NullableArrayHolder(null)))
    assertEquals("""{"a":["a"]}""", Json.encodeToString(NullableArrayHolder(arrayOf("a"))))

    // Unsigned array: content-equal-to-default omitted, differing contents emitted.
    assertEquals("{}", Json.encodeToString(UnsignedArrayHolder(uintArrayOf(1u, 2u))))
    assertEquals("""{"a":[5]}""", Json.encodeToString(UnsignedArrayHolder(uintArrayOf(5u))))

    // @EncodeDefault(ALWAYS) always emits, even with default Json and content equal to default.
    assertEquals("""{"a":[]}""", Json.encodeToString(AlwaysHolder()))

    // Json { encodeDefaults = true } always emits, regardless of content equality.
    assertEquals("""{"a":[]}""", encodeAll.encodeToString(PrimitiveArrayHolder()))
    assertEquals("""{"a":["x","y"]}""", encodeAll.encodeToString(ObjectArrayHolder()))
    assertEquals("""{"a":[[1],[2,3]]}""", encodeAll.encodeToString(NestedArrayHolder()))
    assertEquals("""{"a":null}""", encodeAll.encodeToString(NullableArrayHolder()))
    assertEquals("""{"a":[1,2]}""", encodeAll.encodeToString(UnsignedArrayHolder()))

    return "OK"
}
