// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-66344
// LATEST_LV_DIFFERENCE

class Outer<A> {
    inner class Inner<B> {
        fun foo(b: B) {}
        fun bar(): B = null!!
    }

    val refBarIn: Outer<A>.Inner<in CharSequence>.() -> CharSequence = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<in CharSequence><!>::<!INITIALIZER_TYPE_MISMATCH!>bar<!>
    val refFooIn: Outer<A>.Inner<in CharSequence>.(CharSequence) -> Unit = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<in CharSequence><!>::foo
    val refBarOut: Outer<A>.Inner<out CharSequence>.() -> CharSequence = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<out CharSequence><!>::bar
    val refFooOut: Outer<A>.Inner<out CharSequence>.(CharSequence) -> Unit = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<out CharSequence><!>::<!INITIALIZER_TYPE_MISMATCH!>foo<!>
    val refBarStar = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<*><!>::foo
    val refFooStar = <!WRONG_NUMBER_OF_TYPE_ARGUMENTS!>Inner<*><!>::bar
}

/* GENERATED_FIR_TAGS: callableReference, checkNotNullCall, classDeclaration, functionDeclaration, functionalType,
inProjection, inner, nullableType, out, outProjection, propertyDeclaration, starProjection, typeParameter,
typeWithExtension */
