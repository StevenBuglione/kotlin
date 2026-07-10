/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.SessionHolder
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.utils.isClass
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.isJavaNonAbstractSealed
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.isSubclassOf
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.utils.addToStdlib.flattenTo

interface FirComplementarySymbolsCalculator : FirSessionComponent {
    fun collectAllSubclassesFor(symbol: FirClassSymbol<*>, session: FirSession): Set<FirClassSymbol<*>>

    context(holder: SessionHolder)
    fun collectComplementarySymbolsFor(symbol: FirRegularClassSymbol): Set<FirClassSymbol<*>>
}

class FirDefaultComplementarySymbolsCalculator(private val session: FirSession) : FirComplementarySymbolsCalculator {
    private val allSubclassesCache: FirCache<FirClassSymbol<*>, Set<FirClassSymbol<*>>, MutableSet<FirClassSymbol<*>>> =
        session.firCachesFactory.createCache { symbol, visited ->
            when {
                !visited.add(symbol) -> emptySet()
                symbol !is FirRegularClassSymbol -> setOf(symbol)
                symbol.fir.modality == Modality.SEALED -> buildSet {
                    if (symbol.fir.isJavaNonAbstractSealed == true) {
                        add(symbol)
                    }

                    symbol.fir.getSealedClassInheritors(session).forEach {
                        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(it) as? FirRegularClassSymbol ?: return@forEach
                        this += allSubclassesCache.getValue(symbol, visited)
                    }
                }
                else -> setOf(symbol)
            }
        }

    override fun collectAllSubclassesFor(symbol: FirClassSymbol<*>, session: FirSession): Set<FirClassSymbol<*>> =
        allSubclassesCache.getValue(symbol, mutableSetOf())

    context(holder: SessionHolder)
    fun FirClassSymbol<*>.isSubclassOf(other: FirClassSymbol<*>): Boolean =
        isSubclassOf(other.toLookupTag(), holder.session, isStrict = false, lookupInterfaces = true)

    context(holder: SessionHolder)
    fun areUnrelated(a: FirClassSymbol<*>, b: FirClassSymbol<*>): Boolean =
        !a.isSubclassOf(b) && !b.isSubclassOf(a)

    context(holder: SessionHolder)
    fun FirRegularClassSymbol.getImmediateSuperTypes(): Set<FirRegularClassSymbol> =
        getSuperTypes(holder.session, recursive = false)
            .mapNotNullTo(mutableSetOf()) { it.toRegularClassSymbol() }

    context(holder: SessionHolder)
    fun FirRegularClassSymbol.collectRelevantSealedUniverse(
        relevantSealedUniverseCache: MutableMap<FirClassSymbol<*>, Set<FirClassSymbol<*>>> = mutableMapOf(),
    ): Set<FirClassSymbol<*>> =
        relevantSealedUniverseCache.getOrPut(this) {
            getImmediateSuperTypes()
                .map { it.collectRelevantSealedUniverse(relevantSealedUniverseCache) + collectAllSubclassesFor(it, holder.session) }
                .flattenTo(mutableSetOf())
        }

    context(holder: SessionHolder)
    override fun collectComplementarySymbolsFor(symbol: FirRegularClassSymbol): Set<FirClassSymbol<*>> =
        symbol.collectRelevantSealedUniverse().filterTo(mutableSetOf()) {
            (symbol.isFinal || it.isFinal || symbol.isClass && it.isClass) && areUnrelated(symbol, it)
        }
}

val FirSession.complementarySymbolsCalculator: FirComplementarySymbolsCalculator by FirSession.sessionComponentAccessor()

fun FirClassSymbol<*>.collectAllSubclasses(session: FirSession): Set<FirClassSymbol<*>> =
    session.complementarySymbolsCalculator.collectAllSubclassesFor(this, session)
