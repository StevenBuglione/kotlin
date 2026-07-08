/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.util

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

/**
 * Performs a local PSI-based lookup.
 *
 * @return `null` if the lookup failed, otherwise the found declaration.
 */
@KaImplementationDetail
fun KtSimpleNameExpression.doLookupLocally(): KtNamedDeclaration? {
    val contextKind = contextKind ?: return null

    return LightContextLookupUtil(this, contextKind).lookup()
}

private val KtElement.nonContainerParent: KtElement?
    get() {
        var e = parent
        while (e is KtContainerNode) {
            e = e.parent
        }
        return e as? KtElement
    }

private fun KtSimpleNameExpression.typeIsValidForLocalLookup(): Boolean =
    this !is KtOperationReferenceExpression

private val KtSimpleNameExpression.contextKind: LightContextLookupUtil.ContextKind?
    get() =
        if (!typeIsValidForLocalLookup()) null
        else when (val p = nonContainerParent) {
            is KtCallExpression,
            is KtImportDirective,
            is KtPackageDirective,
            is KtCallableReferenceExpression,
            is KtValueArgumentName,
                -> null
            is KtDotQualifiedExpression -> {
                LightContextLookupUtil.ContextKind.VALUE.takeIf { p.receiverExpression == this@contextKind }
            }
            is KtUserType -> LightContextLookupUtil.ContextKind.TYPE.takeIf { p.qualifier == null && (p.referenceExpression == this@contextKind) }
            is KtClassLiteralExpression -> LightContextLookupUtil.ContextKind.VALUE_OR_TYPE
            is KtValueArgument,
            is KtExpression,
            is KtExpressionCodeFragment,
            is KtWhenConditionInRange,
            is KtSimpleNameStringTemplateEntry,
            is KtWhenConditionWithExpression,
            is KtWhenEntry,
                -> LightContextLookupUtil.ContextKind.VALUE
            is KtTypeConstraint, is KtDelegatedSuperTypeEntry -> LightContextLookupUtil.ContextKind.TYPE
            else -> null
        }

private class LightContextLookupUtil(val element: KtSimpleNameExpression, val contextKind: ContextKind) : KtVisitorVoid() {
    enum class ContextKind {
        VALUE,
        TYPE,
        VALUE_OR_TYPE,
    }

    fun lookup(): KtNamedDeclaration? {
        var current: KtElement = element

        while (true) {
            current.accept(this)
            _found?.let { return it }

            previousElement = current
            current = next(current) ?: return null
            processIgnores(current)
        }
    }

    private var _found: KtNamedDeclaration? = null
    private var previousElement: KtElement? = null
    private val name: Name = element.getReferencedNameAsName()

    private val resolveIgnore: MutableSet<KtElement> = mutableSetOf()

    private fun ignore(element: KtElement) {
        resolveIgnore.add(element)
    }

    private fun isIgnored(element: KtElement): Boolean = resolveIgnore.contains(element) || !typeMatchesGivenContext(element, contextKind)
    private fun typeMatchesGivenContext(element: KtElement, contextKind: ContextKind): Boolean = when (contextKind) {
        ContextKind.VALUE -> element is KtProperty
                || element is KtDestructuringDeclarationEntry
                || element is KtParameter
        ContextKind.TYPE -> element is KtClassOrObject || element is KtTypeAlias
        ContextKind.VALUE_OR_TYPE ->
            typeMatchesGivenContext(element, ContextKind.VALUE)
                    || typeMatchesGivenContext(element, ContextKind.TYPE)
    }

    fun PsiElement.prevKtSibling(condition: (KtElement) -> Boolean = { true }): KtElement? {
        var current: PsiElement? = this.prevSibling
        while (current != null) {
            if (current is KtElement && condition(current)) return current
            current = current.prevSibling
        }
        return null
    }

    private enum class LastDirection {
        BACKWARDS,
        PARENT,
        UNKNOWN,
        ;
    }

    private var myLastDirection: LastDirection = LastDirection.UNKNOWN

    private fun isStopElement(element: KtElement): Boolean =
        element is KtNamedFunction || (element is KtProperty && !element.isLocal)

    private fun shouldStopBeforeProcessing(element: KtElement): Boolean =
        element is KtClassOrObject

    private fun next(element: KtElement): KtElement? {
        if (isStopElement(element)) return null

        myLastDirection = LastDirection.UNKNOWN
        return when (val p = element.parent?.kt) {
            is KtBlockExpression -> {
                val prev = element.prevKtSibling()
                if (prev != null) {
                    myLastDirection = LastDirection.BACKWARDS
                    return prev
                }

                myLastDirection = LastDirection.PARENT
                p
            }

            is KtContainerNode -> {
                myLastDirection = LastDirection.PARENT
                p.nonContainerParent
            }

            else -> {
                myLastDirection = LastDirection.PARENT
                p
            }
        }
            ?.takeUnless(::shouldStopBeforeProcessing)
    }

    private fun lastDirectionIs(value: LastDirection): Boolean =
        when (val d = myLastDirection) {
            LastDirection.UNKNOWN -> throw IllegalStateException("Last direction is unknown")
            else -> d == value
        }

    private fun lastDirectionIsNot(value: LastDirection): Boolean = !lastDirectionIs(value)

    private fun processIgnores(current: KtElement) {
        when (current) {
            is KtProperty -> {
                if (lastDirectionIs(LastDirection.PARENT) && previousElement == current.delegateExpressionOrInitializer) {
                    ignore(current)
                }
            }

            is KtDestructuringDeclaration -> {
                if (lastDirectionIs(LastDirection.PARENT) && previousElement == current.initializer) {
                    ignore(current)
                }
            }

            is KtNamedFunction -> {
                if (lastDirectionIsNot(LastDirection.PARENT)) {
                    for (param in current.valueParameters) {
                        ignoreParameter(param)
                    }

                    for (tyParam in current.typeParameters) {
                        ignore(tyParam)
                    }

                    for (contextParam in current.contextParameters) {
                        ignoreParameter(contextParam)
                    }
                }
            }

            is KtForExpression -> {
                if (lastDirectionIs(LastDirection.PARENT) && previousElement == current.loopRange) {
                    val loopParameter = current.loopParameter ?: return
                    ignoreParameter(loopParameter)
                }
            }
        }
    }

    private fun ignoreParameter(param: KtParameter) {
        ignore(param)
        param.destructuringDeclaration?.entries?.forEach(::ignore)
    }

    override fun visitForExpression(element: KtForExpression) {
        val loopParameter = element.loopParameter ?: return

        processParameter(loopParameter)
    }

    override fun visitWhenExpression(element: KtWhenExpression) {
        element.subjectVariable?.let(::visitProperty)
    }

    override fun visitProperty(element: KtProperty) {
        foundIfNameMatches(element)

        if (lastDirectionIs(LastDirection.PARENT)) {
            element.contextParameters.processingMany(::processParameter)
        }
    }

    override fun visitNamedFunction(element: KtNamedFunction) {
        if (lastDirectionIs(LastDirection.PARENT)) {
            element.valueParameters.processingMany(::processParameter)
            element.contextParameters.processingMany(::processParameter)
            element.typeParameters.processingMany(::processTypeParameter)
        }

        // functions cannot be referenced via simple references unless
        // they are part of KtCallExpressions, and we do not do any overload
        // resolution here
    }

    private fun processTypeParameter(tyParam: KtTypeParameter) {
        foundIfNameMatches(tyParam)
    }

    override fun visitLambdaExpression(element: KtLambdaExpression) {
        require(lastDirectionIs(LastDirection.PARENT))

        element.valueParameters.processingMany(::processParameter)
    }

    private fun processParameter(parameter: KtParameter) {
        if (isIgnored(parameter)) return

        when (val des = parameter.destructuringDeclaration) {
            null -> foundIfNameMatches(parameter)

            else -> visitDestructuringDeclaration(des)
        }
    }

    private fun found(element: KtNamedDeclaration) {
        if (_found != null) return
        _found = element
    }

    private fun nameMatchesAndIsValidCandidate(element: KtNamedDeclaration): Boolean =
        element.nameAsSafeName == name && !isIgnored(element)

    private fun foundIfNameMatches(element: KtNamedDeclaration) {
        if (nameMatchesAndIsValidCandidate(element)) {
            found(element)
        }
    }

    override fun visitClassOrObject(element: KtClassOrObject) {
        foundIfNameMatches(element)
    }

    override fun visitDestructuringDeclaration(decl: KtDestructuringDeclaration) {
        if (isIgnored(decl)) return

        decl.entries.processingMany(::foundIfNameMatches)
    }

    override fun visitTypeAlias(decl: KtTypeAlias) {
        foundIfNameMatches(decl)
    }

    private val PsiElement.kt: KtElement? get() = this as? KtElement

    private inline fun <T> List<T>.processingMany(f: (T) -> Unit) {
        if (_found != null) return

        for (element in this) {
            f(element)
            if (_found != null) return
        }
    }
}
