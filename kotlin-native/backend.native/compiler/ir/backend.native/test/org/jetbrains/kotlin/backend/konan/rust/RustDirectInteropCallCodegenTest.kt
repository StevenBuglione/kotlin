/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCall
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCallResolver
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropBoundaryPolicy
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustIrCodegen
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustLinkerSymbolNamer
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustUnsupportedCode
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RustDirectInteropCallCodegenTest {
    @Test
    fun evaluatesKotlinArgumentsBeforeContainingOnlyTheRustCall() {
        val fixture = IrFixture()
        val kotlinArgument = fixture.function("kotlinArgument", isExternal = true)
        val facade = fixture.function("interopFacade", isExternal = true)
        val entry = fixture.function("entry") { function ->
            val kotlinArgumentCall = call(kotlinArgument, fixture.intType, IrGetValueImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                function.parameters.single().symbol,
            ))
            call(facade, fixture.intType, kotlinArgumentCall)
        }
        val resolver = RustDirectInteropCallResolver { callee ->
            RustDirectInteropCall("direct_fixture::double").takeIf { callee === facade }
        }

        val result = RustIrCodegen(
            linkerSymbolNamer = RustLinkerSymbolNamer { "link_${it.name.asString()}" },
            directInteropCallResolver = resolver,
        ).generate(fixture.module, listOf(entry))

        assertEquals(setOf(entry), result.generatedFunctions.map { it.declaration }.toSet())
        assertEquals(listOf(kotlinArgument), result.fallbackFunctions.map { it.declaration })
        assertFalse(result.generatedFunctions.any { it.declaration === facade })
        assertFalse(result.fallbackFunctions.any { it.declaration === facade })
        assertFalse("interopFacade" in result.source)
        assertContains(result.source, "let __kn_direct_arg_0_0 = unsafe { ")
        assertContains(result.source, "__llvm(value_0) };")
        assertContains(result.source, "catch_unwind(std::panic::AssertUnwindSafe(|| direct_fixture::double(__kn_direct_arg_0_0)))")
        assertTrue(
            result.source.indexOf("let __kn_direct_arg_0_0") < result.source.indexOf("catch_unwind"),
            "A potentially throwing Kotlin argument must be evaluated before the Rust panic catcher",
        )
        assertFalse("resume_unwind" in result.source)
        assertContains(result.source, "#[export_name = \"link_entry\"]")
    }

    @Test
    fun reportsUnsupportedBoundaryConversionInsteadOfEmittingACall() {
        val fixture = IrFixture()
        val facade = fixture.function("interopFacade", isExternal = true)
        val entry = fixture.function("entry") { function ->
            call(facade, fixture.intType, IrGetValueImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                function.parameters.single().symbol,
            ))
        }
        val resolver = RustDirectInteropCallResolver { callee ->
            RustDirectInteropCall(
                "direct_fixture::fallible",
                RustDirectInteropBoundaryPolicy.Unsupported("Rust errors cannot be converted to Kotlin exceptions yet"),
            ).takeIf { callee === facade }
        }

        val result = RustIrCodegen(directInteropCallResolver = resolver).generate(fixture.module, listOf(entry))

        assertTrue(result.generatedFunctions.isEmpty())
        assertEquals(listOf(entry), result.fallbackFunctions.map { it.declaration })
        assertEquals(RustUnsupportedCode.UNSUPPORTED_DIRECT_INTEROP_BOUNDARY, result.diagnostics.single().code)
        assertContains(result.diagnostics.single().message, "cannot be converted")
        assertFalse("direct_fixture::fallible" in result.source)
    }

    @Test
    fun rejectsUnsafeRustPathsAtTheImmutableCallBoundary() {
        assertFailsWith<IllegalArgumentException> {
            RustDirectInteropCall("direct_fixture::double; unsafe_code()")
        }
    }

    private fun call(function: IrSimpleFunction, type: IrType, argument: IrExpression): IrCallImpl =
        IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, function.symbol).apply {
            arguments[0] = argument
        }

    private class IrFixture {
        private val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<rust-direct-interop-test>"),
            LockBasedStorageManager("RustDirectInteropCallCodegenTest"),
            DefaultBuiltIns.Instance,
        )
        val module: IrModuleFragment = IrModuleFragmentImpl(moduleDescriptor)
        private val builtInsFile = file("builtins.kt", FqName("kotlin"))
        private val sourceFile = file("source.kt", FqName("fixture"))
        val intType: IrType = IrFactoryImpl.buildClass {
            name = Name.identifier("Int")
        }.also { intClass ->
            intClass.parent = builtInsFile
            intClass.createThisReceiverParameter()
            builtInsFile.declarations += intClass
        }.defaultType

        fun function(
            name: String,
            isExternal: Boolean = false,
            body: ((IrSimpleFunction) -> IrExpression)? = null,
        ): IrSimpleFunction = IrFactoryImpl.buildFun {
            this.name = Name.identifier(name)
            returnType = intType
            this.isExternal = isExternal
        }.also { function ->
            function.parent = sourceFile
            function.addValueParameter("value", intType)
            function.body = body?.let { expression ->
                IrFactoryImpl.createExpressionBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression(function))
            }
            sourceFile.declarations += function
        }

        private fun file(name: String, packageName: FqName): IrFile = IrFileImpl(
            NaiveSourceBasedFileEntryImpl(name, lineStartOffsets = intArrayOf(0), maxOffset = 0),
            IrFileSymbolImpl(),
            packageName,
            module,
        ).also { module.files += it }
    }
}
