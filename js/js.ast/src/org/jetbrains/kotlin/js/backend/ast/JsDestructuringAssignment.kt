/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.backend.ast

/**
 * An expression-level destructuring assignment such as `({ a = 1 } = obj)` or `([a, ...rest] = arr)`.
 *
 * Unlike a plain [JsBinaryOperation] with [JsBinaryOperator.ASG], the left-hand side is a destructuring
 * [target] (a [JsAssignable] pattern, which is not a [JsExpression]).
 */
class JsDestructuringAssignment(target: JsAssignable, value: JsExpression) : JsExpression() {
    var target: JsAssignable = target
        private set
    var value: JsExpression = value
        private set

    override fun accept(visitor: JsVisitor) {
        visitor.visitDestructuringAssignment(this)
    }

    override fun acceptChildren(visitor: JsVisitor) {
        visitor.accept(target)
        visitor.accept(value)
    }

    override fun traverse(
        visitor: JsVisitorWithContext,
        ctx: JsContext<*>,
    ) {
        if (visitor.visit(this, ctx)) {
            target = visitor.accept(target)
            value = visitor.accept(value)
        }
        visitor.endVisit(this, ctx)
    }

    override fun deepCopy(): JsExpression {
        return JsDestructuringAssignment(target.deepCopy(), value.deepCopy()).withMetadataFrom(this)
    }
}
