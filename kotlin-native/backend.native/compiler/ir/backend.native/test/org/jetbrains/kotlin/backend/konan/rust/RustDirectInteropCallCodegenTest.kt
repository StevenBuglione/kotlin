/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCall
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCallResolver
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustIrCodegen
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustLinkerSymbolNamer
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

class RustDirectInteropCallCodegenTest {
    @Test
    fun rendersContainedDirectCallWithoutMakingFacadeReachable() {
        val fixture = IrFixture()
        val helper = fixture.function("argumentHelper") { function ->
            IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, function.parameters.single().symbol)
        }
        val facade = fixture.function("interopFacade", isExternal = true)
        val entry = fixture.function("entry") { function ->
            val helperCall = call(helper, fixture.intType, IrGetValueImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                function.parameters.single().symbol,
            ))
            call(facade, fixture.intType, helperCall)
        }
        val resolver = RustDirectInteropCallResolver { callee ->
            RustDirectInteropCall("direct_fixture::double").takeIf { callee === facade }
        }

        val result = RustIrCodegen(
            linkerSymbolNamer = RustLinkerSymbolNamer { "link_${it.name.asString()}" },
            directInteropCallResolver = resolver,
        ).generate(fixture.module, listOf(entry))

        assertEquals(listOf(entry, helper).toSet(), result.generatedFunctions.map { it.declaration }.toSet())
        assertEquals(emptyList(), result.fallbackFunctions)
        assertFalse(result.generatedFunctions.any { it.declaration === facade })
        assertFalse(result.fallbackFunctions.any { it.declaration === facade })
        assertFalse("interopFacade" in result.source)
        val helperRustName = result.generatedFunctions.single { it.declaration === helper }.rustName
        assertContains(
            result.source,
            "match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| " +
                    "direct_fixture::double($helperRustName(value_0)))) {\n" +
                    "        Ok(value) => value,\n" +
                    "        Err(_) => std::process::abort(),\n" +
                    "    }",
        )
        assertContains(result.source, "#[export_name = \"link_argumentHelper\"]")
        assertContains(result.source, "#[export_name = \"link_entry\"]")
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
