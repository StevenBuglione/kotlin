/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.native.interop.rust.RustInteropAsyncPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlan
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlanRenderer
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeSymbols
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeType
import org.jetbrains.kotlin.native.interop.rust.RustInteropCrate
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperation
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationKind
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationThreading
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropParameter
import org.jetbrains.kotlin.native.interop.rust.RustInteropPrimitive
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiver
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiverOwnership
import org.jetbrains.kotlin.native.interop.rust.RustInteropTargetPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustDirectInteropExceptionIntegrationTest {
    @Test
    fun resultErrorsAndPanicsBecomeCatchableRuntimeExceptions() {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return
        val distribution = System.getenv(DISTRIBUTION_ENV)?.let(Paths::get) ?: return
        val konanc = distribution.resolve("bin/konanc")
        val cinterop = distribution.resolve("bin/cinterop")
        assertTrue(Files.isRegularFile(konanc), "Kotlin/Native compiler not found: $konanc")
        assertTrue(Files.isRegularFile(cinterop), "Kotlin/Native cinterop not found: $cinterop")

        withTemporaryDirectory { directory ->
            val resultOperation = operation(
                id = "result-value",
                rustPath = "$CRATE_NAME::result_value",
                kotlinName = "resultValue",
                errorMode = RustInteropErrorMode.KOTLIN_EXCEPTION,
                panicMode = RustInteropPanicMode.KOTLIN_EXCEPTION,
            )
            val panicOperation = operation(
                id = "panic-value",
                rustPath = "$CRATE_NAME::panic_value",
                kotlinName = "panicValue",
                errorMode = RustInteropErrorMode.NONE,
                panicMode = RustInteropPanicMode.KOTLIN_EXCEPTION,
            )
            val dropCountOperation = operation(
                id = "drop-count",
                rustPath = "$CRATE_NAME::drop_count",
                kotlinName = "dropCount",
                parameters = emptyList(),
                errorMode = RustInteropErrorMode.NONE,
                panicMode = RustInteropPanicMode.ABORT,
            )
            val plan = RustInteropBridgePlan(
                schemaVersion = 1,
                kotlinPackage = KOTLIN_PACKAGE,
                crate = RustInteropCrate(CRATE_NAME, FIXTURE_VERSION, emptyList(), true),
                handles = emptyList(),
                operations = listOf(resultOperation, panicOperation, dropCountOperation),
            )
            val symbols = plan.operations.associateWith { RustInteropBridgeSymbols.bindingSymbol(plan, it) }
            val crate = writeFixtureCrate(directory, symbols.getValue(resultOperation), symbols.getValue(panicOperation), symbols.getValue(dropCountOperation))
            val planFile = directory.resolve("bridge-plan.json").apply {
                writeText(RustInteropBridgePlanRenderer.render(plan))
            }

            runChecked(crate, "cargo", "generate-lockfile", "--offline")
            runChecked(crate, "cargo", "clippy", "--offline", "--locked", "--all-targets", "--", "-D", "warnings")
            runChecked(crate, "cargo", "build", "--offline", "--locked")

            val cInteropLibrary = buildCInterop(
                cinterop = cinterop,
                directory = directory,
                crate = crate,
                resultSymbol = symbols.getValue(resultOperation),
                panicSymbol = symbols.getValue(panicOperation),
                dropCountSymbol = symbols.getValue(dropCountOperation),
            )
            val facade = writeFacade(directory, symbols.getValue(resultOperation), symbols.getValue(panicOperation), symbols.getValue(dropCountOperation))
            val main = writeMain(directory)

            val llvm = compile(konanc, listOf(facade, main), planFile, crate, cInteropLibrary, directory.resolve("exception-llvm"), "llvm")
            assertEquals(0, llvm.exitCode, llvm.output)
            val llvmObservations = observations(runProgram(directory.resolve("exception-llvm.kexe")))
            assertEquals(EXPECTED_OBSERVATIONS, llvmObservations)

            val hybrid = compile(konanc, listOf(facade, main), planFile, crate, cInteropLibrary, directory.resolve("exception-hybrid"), "rust-hybrid")
            assertEquals(0, hybrid.exitCode, hybrid.output)
            val hybridOutput = runProgram(directory.resolve("exception-hybrid.kexe"))
            assertEquals(llvmObservations, observations(hybridOutput))
            val hybridRust = directory.resolve(".kotlin-rust/exception-hybrid/src/lib.rs").readText()
            assertContains(hybridRust, "$CRATE_NAME::result_value")
            assertContains(hybridRust, "$CRATE_NAME::panic_value")
            assertContains(hybridRust, "Kotlin_RustInterop_ThrowRuntimeException")
            assertContains(hybridRust, "catch_unwind")

            val strictSource = writeStrictSource(directory, symbols.getValue(resultOperation))
            val strict = compileStrict(
                konanc,
                strictSource,
                planFile,
                crate,
                directory.resolve("exception-strict"),
            )
            assertNotEquals(0, strict.exitCode, "Strict standalone mode must reject Kotlin exception conversion until it links the Native runtime")
            assertContains(strict.output, "UNSUPPORTED_DIRECT_INTEROP_BOUNDARY")
            assertContains(
                strict.output,
                "requires the Kotlin/Native runtime exception bridge, which is unavailable to the standalone Rust program backend",
            )
        }
    }

    private fun writeFixtureCrate(directory: Path, resultSymbol: String, panicSymbol: String, dropCountSymbol: String): Path =
        directory.resolve("local-fixture").also { crate ->
            crate.resolve("src").createDirectories()
            crate.resolve("Cargo.toml").writeText(
                """
                    [package]
                    name = "$CRATE_NAME"
                    version = "$FIXTURE_VERSION"
                    edition = "2021"
                    publish = false

                    [lib]
                    crate-type = ["rlib", "staticlib"]

                    [profile.dev]
                    panic = "unwind"
                """.trimIndent() + "\n"
            )
            crate.resolve("src/lib.rs").writeText(
                fixtureRustSource(resultSymbol, panicSymbol, dropCountSymbol)
            )
        }

    private fun fixtureRustSource(resultSymbol: String, panicSymbol: String, dropCountSymbol: String): String = """
        #![allow(clippy::missing_safety_doc)]

        use std::any::Any;
        use std::fmt;
        use std::panic::{catch_unwind, AssertUnwindSafe};
        use std::sync::atomic::{AtomicI32, Ordering};

        static DROP_COUNT: AtomicI32 = AtomicI32::new(0);

        struct DropGuard;

        impl Drop for DropGuard {
            fn drop(&mut self) {
                DROP_COUNT.fetch_add(1, Ordering::SeqCst);
            }
        }

        #[derive(Debug)]
        pub struct FixtureError(i32);

        impl fmt::Display for FixtureError {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(formatter, "résultat\0錯誤:{}", self.0)
            }
        }

        pub fn result_value(value: i32) -> Result<i32, FixtureError> {
            let _guard = DropGuard;
            if value < 0 { Err(FixtureError(value)) } else { Ok(value * 2) }
        }

        pub fn panic_value(value: i32) -> i32 {
            let _guard = DropGuard;
            std::panic::panic_any(format!("panique\0失敗:{value}"));
        }

        pub fn drop_count() -> i32 {
            DROP_COUNT.load(Ordering::SeqCst)
        }

        #[repr(C)]
        pub struct KnriUtf8 {
            data: *mut u8,
            len: usize,
        }

        unsafe fn write_message(output: *mut KnriUtf8, message: String) {
            let mut bytes = message.into_bytes().into_boxed_slice();
            (*output).data = bytes.as_mut_ptr();
            (*output).len = bytes.len();
            std::mem::forget(bytes);
        }

        fn panic_message(payload: Box<dyn Any + Send>) -> String {
            if let Some(value) = payload.downcast_ref::<String>() {
                value.clone()
            } else if let Some(value) = payload.downcast_ref::<&str>() {
                (*value).to_owned()
            } else {
                "Rust panic".to_owned()
            }
        }

        #[no_mangle]
        pub unsafe extern "C" fn $resultSymbol(value: i32, output: *mut i32, error: *mut KnriUtf8) -> i32 {
            match catch_unwind(AssertUnwindSafe(|| result_value(value))) {
                Ok(Ok(result)) => { *output = result; 0 }
                Ok(Err(failure)) => { write_message(error, failure.to_string()); 1 }
                Err(payload) => { write_message(error, panic_message(payload)); 2 }
            }
        }

        #[no_mangle]
        pub unsafe extern "C" fn $panicSymbol(value: i32, output: *mut i32, error: *mut KnriUtf8) -> i32 {
            match catch_unwind(AssertUnwindSafe(|| panic_value(value))) {
                Ok(result) => { *output = result; 0 }
                Err(payload) => { write_message(error, panic_message(payload)); 2 }
            }
        }

        #[no_mangle]
        pub unsafe extern "C" fn $dropCountSymbol(output: *mut i32, _error: *mut KnriUtf8) -> i32 {
            *output = drop_count();
            0
        }

        #[no_mangle]
        pub unsafe extern "C" fn knri_fixture_free_utf8(data: *mut u8, len: usize) {
            if !data.is_null() {
                drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(data, len)));
            }
        }
    """.trimIndent() + "\n"

    private fun buildCInterop(
        cinterop: Path,
        directory: Path,
        crate: Path,
        resultSymbol: String,
        panicSymbol: String,
        dropCountSymbol: String,
    ): Path {
        val header = directory.resolve("fixture.h").apply {
            writeText(
                """
                    #include <stddef.h>
                    #include <stdint.h>
                    typedef struct KnriUtf8 { uint8_t *data; size_t len; } KnriUtf8;
                    int32_t $resultSymbol(int32_t value, int32_t *output, KnriUtf8 *error);
                    int32_t $panicSymbol(int32_t value, int32_t *output, KnriUtf8 *error);
                    int32_t $dropCountSymbol(int32_t *output, KnriUtf8 *error);
                    void knri_fixture_free_utf8(uint8_t *data, size_t len);
                """.trimIndent() + "\n"
            )
        }
        val definition = directory.resolve("fixture.def").apply {
            writeText(
                """
                    headers = ${header.absolutePathString()}
                    package = $CINTEROP_PACKAGE
                    staticLibraries = lib$CRATE_NAME.a
                    libraryPaths = ${crate.resolve("target/debug").absolutePathString()}
                    linkerOpts = -lgcc_s -lutil -lrt -lpthread -lm -ldl -lc
                """.trimIndent() + "\n"
            )
        }
        val output = directory.resolve("fixture-cinterop")
        runChecked(
            directory,
            cinterop.absolutePathString(),
            "-def", definition.absolutePathString(),
            "-target", "linux_x64",
            "-o", output.absolutePathString(),
        )
        return output.resolveSibling(output.fileName.toString() + ".klib")
    }

    private fun writeFacade(directory: Path, resultSymbol: String, panicSymbol: String, dropCountSymbol: String): Path =
        directory.resolve("facade.kt").apply {
            writeText(
                """
                    @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

                    package $KOTLIN_PACKAGE

                    import kotlinx.cinterop.*
                    import $CINTEROP_PACKAGE.KnriUtf8
                    import $CINTEROP_PACKAGE.$resultSymbol as resultValueC
                    import $CINTEROP_PACKAGE.$panicSymbol as panicValueC
                    import $CINTEROP_PACKAGE.$dropCountSymbol as dropCountC
                    import $CINTEROP_PACKAGE.knri_fixture_free_utf8

                    private fun message(error: KnriUtf8): String {
                        val result = if (error.data == null || error.len.toLong() == 0L) "" else error.data!!.readBytes(error.len.toInt()).decodeToString()
                        knri_fixture_free_utf8(error.data, error.len)
                        return result
                    }

                    private fun $resultSymbol(value: Int): Int = memScoped {
                        val output = alloc<IntVar>()
                        val error = alloc<KnriUtf8>()
                        val status = resultValueC(value, output.ptr, error.ptr)
                        if (status != 0) throw RuntimeException(message(error))
                        output.value
                    }

                    private fun $panicSymbol(value: Int): Int = memScoped {
                        val output = alloc<IntVar>()
                        val error = alloc<KnriUtf8>()
                        val status = panicValueC(value, output.ptr, error.ptr)
                        if (status != 0) throw RuntimeException(message(error))
                        output.value
                    }

                    private fun $dropCountSymbol(): Int = memScoped {
                        val output = alloc<IntVar>()
                        val error = alloc<KnriUtf8>()
                        val status = dropCountC(output.ptr, error.ptr)
                        if (status != 0) throw RuntimeException(message(error))
                        output.value
                    }

                    fun resultValue(value: Int): Int = $resultSymbol(value)
                    fun panicValue(value: Int): Int = $panicSymbol(value)
                    fun dropCount(): Int = $dropCountSymbol()

                    fun directResultValue(value: Int): Int = resultValue(value)
                    fun directPanicValue(value: Int): Int = panicValue(value)
                    fun directDropCount(): Int = dropCount()
                """.trimIndent() + "\n"
            )
        }

    private fun writeMain(directory: Path): Path = directory.resolve("main.kt").apply {
        writeText(
            """
                package $KOTLIN_PACKAGE

                private fun escaped(message: String?): String = message.orEmpty().replace("\u0000", "<NUL>")

                fun main() {
                    println("success=" + directResultValue(21))
                    try {
                        directResultValue(-7)
                    } catch (failure: RuntimeException) {
                        println("result=" + escaped(failure.message))
                    }
                    try {
                        directPanicValue(9)
                    } catch (failure: RuntimeException) {
                        println("panic=" + escaped(failure.message))
                    }
                    println("drops=" + directDropCount())
                    println("continued=true")
                }
            """.trimIndent() + "\n"
        )
    }

    private fun writeStrictSource(directory: Path, resultSymbol: String): Path = directory.resolve("strict.kt").apply {
        writeText(
            """
                package $KOTLIN_PACKAGE

                private fun $resultSymbol(value: Int): Int = value
                fun resultValue(value: Int): Int = $resultSymbol(value)
                fun directResultValue(value: Int): Int = resultValue(value)
                fun main() {
                    directResultValue(-7)
                }
            """.trimIndent() + "\n"
        )
    }

    private fun compile(
        compiler: Path,
        sources: List<Path>,
        plan: Path,
        localCrate: Path,
        cInteropLibrary: Path,
        output: Path,
        mode: String,
    ): ProcessResult = runProcess(
        output.parent,
        compiler.absolutePathString(),
        *sources.map(Path::absolutePathString).toTypedArray(),
        "-library", cInteropLibrary.absolutePathString(),
        "-target", "linux_x64",
        "-entry", "$KOTLIN_PACKAGE.main",
        "-Xnative-codegen=$mode",
        "-Xrust-interop-bridge-plan=${plan.absolutePathString()}",
        "-Xrust-interop-crate-path=$CRATE_NAME=${localCrate.absolutePathString()}",
        "-o", output.absolutePathString(),
    )

    private fun compileStrict(
        compiler: Path,
        source: Path,
        plan: Path,
        localCrate: Path,
        output: Path,
    ): ProcessResult = runProcess(
        output.parent,
        compiler.absolutePathString(),
        source.absolutePathString(),
        "-target", "linux_x64",
        "-entry", "$KOTLIN_PACKAGE.main",
        "-Xnative-codegen=rust-strict",
        "-Xrust-interop-bridge-plan=${plan.absolutePathString()}",
        "-Xrust-interop-crate-path=$CRATE_NAME=${localCrate.absolutePathString()}",
        "-o", output.absolutePathString(),
    )

    private fun operation(
        id: String,
        rustPath: String,
        kotlinName: String,
        parameters: List<RustInteropParameter> = listOf(
            RustInteropParameter("value", RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32))
        ),
        errorMode: RustInteropErrorMode,
        panicMode: RustInteropPanicMode,
    ) = RustInteropOperation(
        id = id,
        kind = RustInteropOperationKind.FUNCTION,
        rustPath = rustPath,
        kotlinName = kotlinName,
        receiver = RustInteropReceiver(RustInteropReceiverOwnership.NONE, null),
        parameters = parameters,
        returnType = RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32),
        errorPolicy = RustInteropErrorPolicy(
            errorMode,
            if (errorMode == RustInteropErrorMode.KOTLIN_EXCEPTION) "kotlin.RuntimeException" else null,
        ),
        panicPolicy = RustInteropPanicPolicy(
            panicMode,
            if (panicMode == RustInteropPanicMode.KOTLIN_EXCEPTION) "kotlin.RuntimeException" else null,
        ),
        threading = RustInteropOperationThreading.CALLER,
        asyncPolicy = RustInteropAsyncPolicy.SYNCHRONOUS,
        targetPolicy = RustInteropTargetPolicy(emptyList(), emptyList()),
    )

    private fun observations(output: String): List<String> = output.lineSequence()
        .filter { line -> EXPECTED_OBSERVATIONS.any { expected -> line.substringBefore('=') == expected.substringBefore('=') } }
        .toList()

    private fun runProgram(executable: Path): String {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        val result = runProcess(executable.parent, executable.absolutePathString())
        assertEquals(0, result.exitCode, result.output)
        return result.output
    }

    private fun runChecked(workingDirectory: Path, vararg command: String) {
        val result = runProcess(workingDirectory, *command)
        assertEquals(0, result.exitCode, result.output)
    }

    private fun runProcess(workingDirectory: Path, vararg command: String): ProcessResult {
        val process = ProcessBuilder(command.toList())
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-direct-exception-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val DISTRIBUTION_ENV = "KOTLIN_NATIVE_RUST_TEST_DIST"
        const val CRATE_NAME = "direct_exception_fixture"
        const val FIXTURE_VERSION = "1.0.0"
        const val KOTLIN_PACKAGE = "rust.exception.fixture"
        const val CINTEROP_PACKAGE = "rust.exception.fixture.cinterop"

        val EXPECTED_OBSERVATIONS = listOf(
            "success=42",
            "result=résultat<NUL>錯誤:-7",
            "panic=panique<NUL>失敗:9",
            "drops=3",
            "continued=true",
        )
    }
}
