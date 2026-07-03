/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isActualDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isExpectDeclaration
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A local PSI-based utility that checks if this [KtSimpleNameExpression] **CAN** be a reference to [candidateTarget].
 *
 * @return `false` when this [KtSimpleNameExpression] **CANNOT** be a reference to [candidateTarget], `true` if it definitely is,
 * and `null` if unsure.
 */
@KaImplementationDetail
fun KtSimpleNameExpression.canBeReferenceToLocalCheck(candidateTarget: PsiElement): Boolean? {
    if (!isValidForLocalLookup) return null

    when (candidateTarget) {
        is KtProperty -> {
            if (!candidateTarget.isLocal) return null
        }

        is KtParameter -> {
            if (parent is KtValueArgumentName) {
                return null
            }
            candidateTarget.parentOfType<KtFunction>()?.let { f ->
                // primary constructor parameters can have more usages (see destructuring assignments)
                if (f is KtPrimaryConstructor && f.valueParameters.any { it == candidateTarget })
                    return null
            }
        }

        is KtClass -> {
            if (candidateTarget is KtEnumEntry)
                return null

            if (candidateTarget.isExpectDeclaration() || candidateTarget.isActualDeclaration())
                return null
        }
        is KtTypeAlias -> {}

        else -> return null
    }

    val localLookupResult = doLookupLocally() ?: return null

    return localLookupResult.isEquivalentTo(candidateTarget)
}

/**
 * Performs a local PSI-based lookup.
 *
 * @return `null` if the lookup failed, otherwise the found declaration.
 */
@KaImplementationDetail
fun KtSimpleNameExpression.doLookupLocally(): KtNamedDeclaration? {
    if (!isValidForLocalLookup) return null

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

private val KtSimpleNameExpression.isValidForLocalLookup: Boolean
    get() = this !is KtOperationReferenceExpression && when (val p = nonContainerParent) {
        is KtCallExpression, is KtImportDirective, is KtPackageDirective, is KtCallableReferenceExpression, is KtValueArgumentName -> false
        // for DQE's we can only resolve them if we are resolving the receiver
        is KtDotQualifiedExpression -> p.receiverExpression == this@isValidForLocalLookup
        is KtUserType -> p.qualifier == null && (p.referenceExpression == this@isValidForLocalLookup)
        else -> true
    }

private val KtSimpleNameExpression.contextKind: LightContextLookupUtil.ContextKind
    get() = when (nonContainerParent) {
        is KtClassLiteralExpression -> LightContextLookupUtil.ContextKind.VALUE_OR_TYPE
        is KtValueArgument,
        is KtExpression,
        is KtExpressionCodeFragment,
        is KtWhenConditionInRange,
        is KtSimpleNameStringTemplateEntry,
        is KtWhenConditionWithExpression,
        is KtWhenEntry,
            -> LightContextLookupUtil.ContextKind.VALUE
        is KtUserType, is KtTypeConstraint, is KtDelegatedSuperTypeEntry -> LightContextLookupUtil.ContextKind.TYPE
        else -> TODO("Unknown context type: ${parent::class.qualifiedName}")
    }

private class LightContextLookupUtil(val element: KtSimpleNameExpression, val contextKind: ContextKind) {
    enum class ContextKind {
        VALUE,
        TYPE,
        VALUE_OR_TYPE,
    }

    fun lookup(): KtNamedDeclaration? {
        var current: KtElement = element

        while (true) {
            try {
                visit(current)
            } catch (_: FoundEnd) {
                LOGGER.debug("Resolved ${element.parent.text} -> ${_found?.text}")
                return requireNotNull(_found)
            } catch (_: PsiUnresolvable) {
                return null
            }
            require(_found == null) { "_found is not null but no FoundEnd was thrown" }

            previousElement = current
            current = next(current) ?: return null
            processIgnores(current)
        }
    }

    private var _found: KtNamedDeclaration? = null
    private var previousElement: KtElement? = null
    private val name: Name = element.getReferencedNameAsName()

    private class FoundEnd : Throwable()
    private class PsiUnresolvable : Throwable()

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
        CONTEXT_COLLECT,
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

    private fun visit(element: KtElement) {
        when (element) {
            is KtFile -> visitFile(element)
            is KtProperty -> visitProperty(element)
            is KtWhenExpression -> visitWhenExpression(element)
            is KtForExpression -> visitForExpression(element)
            is KtClassOrObject -> visitClassOrObject(element)
            is KtDestructuringDeclaration -> visitDestructuringDeclaration(element)
            is KtTypeAlias -> visitTypeAlias(element)
            is KtNamedFunction -> visitFunction(element)
            is KtLambdaExpression -> visitLambda(element)
        }
    }

    private fun visitFile(element: KtFile) {
        collectingContext {
            for (decl in element.declarations) {
                visit(decl)
            }
        }
    }

    private fun visitForExpression(element: KtForExpression) {
        val loopParameter = element.loopParameter ?: return

        processParameter(loopParameter)
    }

    private fun visitWhenExpression(element: KtWhenExpression) {
        element.subjectVariable?.let(::visitProperty)
    }

    private fun visitProperty(element: KtProperty) {
        foundIfNameMatches(element)

        if (lastDirectionIs(LastDirection.PARENT)) {
            for (contextParam in element.contextParameters) {
                processParameter(contextParam)
            }
        }
    }

    private fun visitFunction(element: KtNamedFunction) {
        if (lastDirectionIs(LastDirection.PARENT)) {
            for (param in element.valueParameters) {
                processParameter(param)
            }

            for (contextParam in element.contextParameters) {
                processParameter(contextParam)
            }

            for (tyParam in element.typeParameters) {
                processTypeParameter(tyParam)
            }
        }

        // functions cannot be referenced via simple references unless
        // they are part of KtCallExpressions, and we do not do any overload
        // resolution here
    }

    private fun processTypeParameter(tyParam: KtTypeParameter) {
        foundIfNameMatches(tyParam)
    }

    private fun visitLambda(element: KtLambdaExpression) {
        require(lastDirectionIs(LastDirection.PARENT))

        for (param in element.valueParameters) {
            processParameter(param)
        }
    }

    private fun processParameter(parameter: KtParameter) {
        if (isIgnored(parameter)) return

        when (val des = parameter.destructuringDeclaration) {
            null -> foundIfNameMatches(parameter)

            else -> visitDestructuringDeclaration(des)
        }
    }

    private fun found(element: KtNamedDeclaration) {
        _found = element
        throw FoundEnd()
    }

    private fun nameMatchesAndIsValidCandidate(element: KtNamedDeclaration): Boolean =
        element.nameAsSafeName == name && !isIgnored(element)

    private fun foundIfNameMatches(element: KtNamedDeclaration) {
        if (nameMatchesAndIsValidCandidate(element)) {
            found(element)
        }
    }

    private fun unresolvableIfNameMatches(element: KtNamedDeclaration) {
        if (nameMatchesAndIsValidCandidate(element)) {
            throw PsiUnresolvable()
        }
    }

    private fun visitClassOrObject(element: KtClassOrObject) {
        if (element.isActualDeclaration() || element.isExpectDeclaration())
            unresolvableIfNameMatches(element)

        foundIfNameMatches(element)
    }

    @OptIn(ExperimentalContracts::class)
    private fun collectingContext(f: () -> Unit) {
        contract {
            callsInPlace(f, InvocationKind.EXACTLY_ONCE)
        }

        val oldLastDirection = myLastDirection
        myLastDirection = LastDirection.CONTEXT_COLLECT
        try {
            f()
        } finally {
            myLastDirection = oldLastDirection
        }
    }

    private fun visitDestructuringDeclaration(decl: KtDestructuringDeclaration) {
        if (isIgnored(decl)) return

        for (entry in decl.entries) {
            foundIfNameMatches(entry)
        }
    }

    private fun visitTypeAlias(decl: KtTypeAlias) {
        foundIfNameMatches(decl)
    }

    private val PsiElement.kt: KtElement? get() = this as? KtElement

    companion object {
        private val LOGGER = Logger.getInstance(LightContextLookupUtil::class.java)
    }
}
