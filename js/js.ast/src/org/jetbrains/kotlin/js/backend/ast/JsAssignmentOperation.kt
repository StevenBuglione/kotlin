/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.backend.ast

/**
 * An expression-level assignment using the `=` operator.
 *
 * Replaces the former `JsBinaryOperation` with `JsBinaryOperator.ASG`: assignment is no longer a binary
 * operator, so that both a regular assignment ([Simple], e.g. `a.b = c`) and a destructuring assignment
 * ([Destructuring], e.g. `({ a = 1 } = obj)` or `([a, ...rest] = arr)`) can be represented uniformly.
 *
 * Compound assignments (`+=`, `-=`, ...) remain [JsBinaryOperation]s, since they are only valid with a
 * plain left-hand side and never with a destructuring pattern.
 */
sealed class JsAssignmentOperation : JsExpression() {
    abstract override fun deepCopy(): JsAssignmentOperation

    /**
     * A regular assignment `target = value`, where [target] is an arbitrary l-value expression
     * (a name reference, property access, array access, ...).
     */
    class Simple(target: JsExpression, value: JsExpression) : JsAssignmentOperation() {
        var target: JsExpression = target
            private set
        var value: JsExpression = value
            private set

        override fun accept(visitor: JsVisitor) {
            visitor.visitSimpleAssignment(this)
        }

        override fun acceptChildren(visitor: JsVisitor) {
            visitor.acceptLvalue(target)
            visitor.accept(value)
        }

        override fun traverse(
            visitor: JsVisitorWithContext,
            ctx: JsContext<*>,
        ) {
            if (visitor.visit(this, ctx)) {
                target = visitor.acceptLvalue(target)
                value = visitor.accept(value)
            }
            visitor.endVisit(this, ctx)
        }

        override fun deepCopy(): Simple {
            return Simple(target.deepCopy(), value.deepCopy()).withMetadataFrom(this)
        }
    }

    /**
     * A destructuring assignment `pattern = value`, where the left-hand side is a destructuring
     * [JsAssignable] pattern (which is not a [JsExpression]).
     */
    class Destructuring(pattern: JsAssignable, value: JsExpression) : JsAssignmentOperation() {
        var pattern: JsAssignable = pattern
            private set
        var value: JsExpression = value
            private set

        override fun accept(visitor: JsVisitor) {
            visitor.visitDestructuringAssignment(this)
        }

        override fun acceptChildren(visitor: JsVisitor) {
            visitor.accept(pattern)
            visitor.accept(value)
        }

        override fun traverse(
            visitor: JsVisitorWithContext,
            ctx: JsContext<*>,
        ) {
            if (visitor.visit(this, ctx)) {
                pattern = visitor.accept(pattern)
                value = visitor.accept(value)
            }
            visitor.endVisit(this, ctx)
        }

        override fun deepCopy(): Destructuring {
            return Destructuring(pattern.deepCopy(), value.deepCopy()).withMetadataFrom(this)
        }
    }

    companion object {
        /**
         * Precedence of an assignment expression, matching the value historically returned by
         * `JsBinaryOperator.ASG.getPrecedence()`.
         */
        const val PRECEDENCE: Int = 2
    }
}
