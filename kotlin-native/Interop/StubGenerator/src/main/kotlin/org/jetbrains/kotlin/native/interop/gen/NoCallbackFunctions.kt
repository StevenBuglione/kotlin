/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.indexer.*

/**
 * Validates the behavioral assertions supplied through a .def file before they can
 * are emitted into cinterop metadata.
 *
 * A C header cannot prove that a function does not call Kotlin. Keep this surface
 * deliberately narrow: only exact, named C declarations with callback-free shapes
 * can carry the assertion. Dynamic function pointer invocations have no declaration
 * here and therefore cannot acquire the marker.
 */
internal fun validateNoCallbackFunctions(
        language: Language,
        functions: Collection<FunctionDecl>,
        requestedNames: Set<String>,
        excludedNames: Set<String>
) {
    if (requestedNames.isEmpty()) return

    require(language == Language.C) {
        "noCallbackFunctions is supported only for C declarations"
    }

    val functionsByName = functions.groupBy(FunctionDecl::name)
    requestedNames.sorted().forEach { name ->
        require(name !in excludedNames) {
            "noCallbackFunctions contains excluded function '$name'"
        }

        val matches = functionsByName[name].orEmpty()
        require(matches.isNotEmpty()) {
            "noCallbackFunctions contains unknown C function '$name'"
        }
        require(matches.size == 1) {
            "noCallbackFunctions contains ambiguous C function '$name'"
        }

        val function = matches.single()
        require(!function.isVararg) {
            "noCallbackFunctions cannot contain variadic C function '$name'"
        }
        require(function.parameters.none { it.type.containsFunctionType() } &&
                !function.returnType.containsFunctionType()) {
            "noCallbackFunctions cannot contain callback-shaped C function '$name'"
        }
    }
}

private fun Type.containsFunctionType(visitedRecords: MutableSet<StructDecl> = mutableSetOf()): Boolean =
        when (val type = unwrapTypedefs()) {
            is FunctionType -> true
            is PointerType -> type.pointeeType.containsFunctionType(visitedRecords)
            is ArrayType -> type.elemType.containsFunctionType(visitedRecords)
            is RecordType -> type.decl.containsFunctionType(visitedRecords)
            is ManagedType -> type.decl.containsFunctionType(visitedRecords)
            else -> false
        }

private fun StructDecl.containsFunctionType(visitedRecords: MutableSet<StructDecl>): Boolean {
    if (!visitedRecords.add(this)) return false
    return def?.fields?.any { it.type.containsFunctionType(visitedRecords) } == true
}
