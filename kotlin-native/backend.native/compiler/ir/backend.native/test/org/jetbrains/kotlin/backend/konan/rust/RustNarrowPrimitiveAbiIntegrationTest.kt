/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.junit.Assume.assumeTrue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RustNarrowPrimitiveAbiIntegrationTest {
    @Test
    fun narrowPrimitivesRoundTripAcrossLlvmAndRust() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust narrow ABI integration test", distributionPath != null)
        assumeTrue("The Rust narrow ABI integration fixture supports Linux x64 hosts only", isLinuxX64Host())
        val compiler = Paths.get(distributionPath!!).resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("narrow-primitive-abi.kt").apply {
                writeText(
                    """
                        fun byteIdentity(value: Byte): Byte = value

                        fun shortIdentity(value: Short): Short = value

                        fun charIdentity(value: Char): Char = value

                        fun ubyteIdentity(value: UByte): UByte = value

                        fun ushortIdentity(value: UShort): UShort = value

                        fun main() {
                            println(byteIdentity(Byte.MIN_VALUE))
                            println(byteIdentity(Byte.MAX_VALUE))
                            println(shortIdentity(Short.MIN_VALUE))
                            println(shortIdentity(Short.MAX_VALUE))
                            println(charIdentity('\u0000').code)
                            println(charIdentity('\uD800').code)
                            println(charIdentity('\uDFFF').code)
                            println(charIdentity('\uFFFF').code)
                            println(ubyteIdentity(UByte.MIN_VALUE))
                            println(ubyteIdentity(UByte.MAX_VALUE))
                            println(ushortIdentity(UShort.MIN_VALUE))
                            println(ushortIdentity(UShort.MAX_VALUE))
                        }
                    """.trimIndent()
                )
            }
            val expectedLines = listOf(
                "-128",
                "127",
                "-32768",
                "32767",
                "0",
                "55296",
                "57343",
                "65535",
                "0",
                "255",
                "0",
                "65535",
            )

            for (profileAndArguments in PROFILES) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("narrow-primitive-abi-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(expectedLines, outputLines(llvmExecution.output))

                val hybridOutputName = "narrow-primitive-abi-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                assertEquals(llvmExecution.output, runProgram(executable(hybridOutput)).output)

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                val marker = workspace.resolve("rust-bitcode-preflight-ok")
                assertEquals(
                    "rust-boundary-normalized.bc",
                    Files.readAllBytes(marker).toString(StandardCharsets.UTF_8).trim(),
                )
                val generatedSource = Files.readAllBytes(workspace.resolve("src/lib.rs")).toString(StandardCharsets.UTF_8)
                assertNarrowSignature(generatedSource, "byteIdentity", "kotlin.Byte", "i8")
                assertNarrowSignature(generatedSource, "shortIdentity", "kotlin.Short", "i16")
                assertNarrowSignature(generatedSource, "charIdentity", "kotlin.Char", "u16")
                assertNarrowSignature(generatedSource, "ubyteIdentity", "kotlin.UByte", "u8")
                assertNarrowSignature(generatedSource, "ushortIdentity", "kotlin.UShort", "u16")
            }

            val strictSource = directory.resolve("narrow-primitive-strict.kt").apply {
                writeText(
                    """
                        fun byteIdentity(value: Byte): Byte = value
                        fun shortIdentity(value: Short): Short = value
                        fun charIdentity(value: Char): Char = value
                        fun ubyteIdentity(value: UByte): UByte = value
                        fun ushortIdentity(value: UShort): UShort = value

                        fun main() {
                            byteIdentity(Byte.MIN_VALUE)
                            shortIdentity(Short.MIN_VALUE)
                            charIdentity('\uD800')
                            ubyteIdentity(UByte.MAX_VALUE)
                            ushortIdentity(UShort.MAX_VALUE)
                        }
                    """.trimIndent()
                )
            }
            val strictOutput = directory.resolve("narrow-primitive-strict")
            val strictCompilation = compile(compiler, strictSource, strictOutput, "rust-strict", emptyList())
            assertEquals(0, strictCompilation.exitCode, strictCompilation.output)
            assertEquals(emptyList(), outputLines(runProgram(executable(strictOutput)).output))
        }
    }

    @Test
    fun narrowArithmeticConversionsAndFallbacksMatchLlvm() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust narrow ABI integration test", distributionPath != null)
        assumeTrue("The Rust narrow ABI integration fixture supports Linux x64 hosts only", isLinuxX64Host())
        val compiler = Paths.get(distributionPath!!).resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("narrow-arithmetic.kt").apply {
                writeText(
                    """
                        fun bytePlus(left: Byte, right: Byte): Int = left + right
                        fun byteMinus(left: Byte, right: Byte): Int = left - right
                        fun byteTimes(left: Byte, right: Byte): Int = left * right
                        fun byteUnaryMinus(value: Byte): Int = -value
                        fun byteDivMinusOne(value: Byte): Int = value / -1
                        fun byteRemMinusOne(value: Byte): Int = value % -1

                        fun shortPlus(left: Short, right: Short): Int = left + right
                        fun shortMinus(left: Short, right: Short): Int = left - right
                        fun shortTimes(left: Short, right: Short): Int = left * right
                        fun shortUnaryMinus(value: Short): Int = -value
                        fun shortDivMinusOne(value: Short): Int = value / -1
                        fun shortRemMinusOne(value: Short): Int = value % -1

                        fun ubytePlus(left: UByte, right: UByte): UInt = left + right
                        fun ushortPlus(left: UShort, right: UShort): UInt = left + right

                        fun charPlusOne(value: Char): Char = value + 1
                        fun charDifference(left: Char, right: Char): Int = left - right
                        fun charIncrement(value: Char): Char = value.inc()
                        fun charDecrement(value: Char): Char = value.dec()

                        fun byteToLong(value: Byte): Long = value.toLong()
                        fun longToByte(value: Long): Byte = value.toByte()
                        fun shortToUInt(value: Short): UInt = value.toUInt()
                        fun uintToShort(value: UInt): Short = value.toShort()
                        fun charToInt(value: Char): Int = value.code
                        fun intToChar(value: Int): Char = value.toChar()

                        fun generatedNarrow(value: Byte): Int = value + 1
                        fun variableByteDiv(value: Byte, divisor: Byte): Int = value / divisor
                        fun zeroShortRem(value: Short): Int = value % 0

                        fun main() {
                            println(bytePlus(Byte.MIN_VALUE, Byte.MAX_VALUE))
                            println(byteMinus(Byte.MIN_VALUE, Byte.MAX_VALUE))
                            println(byteTimes(Byte.MIN_VALUE, Byte.MAX_VALUE))
                            println(byteUnaryMinus(Byte.MIN_VALUE))
                            println(byteDivMinusOne(Byte.MIN_VALUE))
                            println(byteRemMinusOne(Byte.MIN_VALUE))
                            println(shortPlus(Short.MIN_VALUE, Short.MAX_VALUE))
                            println(shortMinus(Short.MIN_VALUE, Short.MAX_VALUE))
                            println(shortTimes(Short.MIN_VALUE, 2.toShort()))
                            println(shortUnaryMinus(Short.MIN_VALUE))
                            println(shortDivMinusOne(Short.MIN_VALUE))
                            println(shortRemMinusOne(Short.MIN_VALUE))
                            println(ubytePlus(UByte.MAX_VALUE, 1u.toUByte()))
                            println(ushortPlus(UShort.MAX_VALUE, 1u.toUShort()))
                            println(charPlusOne('\uFFFF').code)
                            println(charDifference('\uFFFF', '\u0000'))
                            println(charIncrement('\uFFFF').code)
                            println(charDecrement('\u0000').code)
                            println(byteToLong(Byte.MIN_VALUE))
                            println(longToByte(Long.MAX_VALUE))
                            println(shortToUInt(Short.MIN_VALUE))
                            println(uintToShort(UInt.MAX_VALUE))
                            println(charToInt('\uFFFF'))
                            println(intToChar(-1).code)
                            println(generatedNarrow(Byte.MAX_VALUE))
                            println(variableByteDiv(20.toByte(), 4.toByte()))
                            try {
                                zeroShortRem(1.toShort())
                                println("missing Short exception")
                            } catch (_: ArithmeticException) {
                                println("Short zero")
                            }
                        }
                    """.trimIndent()
                )
            }
            val expectedLines = listOf(
                "-1", "-255", "-16256", "128", "128", "0",
                "-1", "-65535", "-65536", "32768", "32768", "0",
                "256", "65536", "0", "65535", "0", "65535",
                "-128", "-1", "4294934528", "-1", "65535", "65535",
                "128", "5", "Short zero",
            )
            val generatedLeaves = listOf(
                "byteToLong", "longToByte", "intToChar", "charIncrement", "charDecrement",
            )
            val loweredFallbackLeaves = listOf(
                "bytePlus", "byteMinus", "byteTimes", "byteUnaryMinus", "byteDivMinusOne", "byteRemMinusOne",
                "shortPlus", "shortMinus", "shortTimes", "shortUnaryMinus", "shortDivMinusOne", "shortRemMinusOne",
                "ubytePlus", "ushortPlus", "charPlusOne", "charDifference", "shortToUInt", "uintToShort", "charToInt",
                "generatedNarrow",
            )

            for (profileAndArguments in PROFILES) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("narrow-arithmetic-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(expectedLines, outputLines(llvmExecution.output))

                val hybridOutputName = "narrow-arithmetic-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                assertEquals(llvmExecution.output, runProgram(executable(hybridOutput)).output)

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                assertEquals(
                    "rust-boundary-normalized.bc",
                    Files.readAllBytes(workspace.resolve("rust-bitcode-preflight-ok")).toString(StandardCharsets.UTF_8).trim(),
                )
                val generatedSource = Files.readAllBytes(workspace.resolve("src/lib.rs")).toString(StandardCharsets.UTF_8)
                generatedLeaves.forEach { assertGeneratedFunction(generatedSource, it) }
                loweredFallbackLeaves.forEach { assertLlvmFallback(generatedSource, it) }
                assertLlvmFallback(generatedSource, "variableByteDiv")
                assertLlvmFallback(generatedSource, "zeroShortRem")
            }

            val strictCompilation = compile(
                compiler,
                source,
                directory.resolve("narrow-arithmetic-strict"),
                "rust-strict",
                emptyList(),
            )
            assertTrue(strictCompilation.exitCode != 0, "Strict compilation unexpectedly succeeded")
            assertContains(
                strictCompilation.output,
                "[UNSUPPORTED_TYPE] variableByteDiv: Unsupported Kotlin type",
            )
            assertContains(
                strictCompilation.output,
                "[UNSUPPORTED_TYPE] zeroShortRem: Unsupported Kotlin type",
            )
            assertContains(strictCompilation.output, source.fileName.toString())
        }
    }

    private fun assertNarrowSignature(source: String, functionName: String, kotlinType: String, rustType: String) {
        val body = exportedFunction(source, functionName, kotlinType)
        assertContains(body, "value_0: $rustType")
        assertTrue(
            Regex("""\) -> $rustType\s*\{""").containsMatchIn(body),
            "Generated Rust function '$functionName' does not return $rustType:\n$body",
        )
        assertTrue("__llvm" !in body, "Function '$functionName' unexpectedly fell back to LLVM:\n$body")
    }

    private fun exportedFunction(source: String, functionName: String, kotlinType: String): String {
        val exportNamePrefix = "kfun:#$functionName($kotlinType){}$kotlinType"
        val start = source.indexOf("#[export_name = \"$exportNamePrefix")
        assertTrue(start >= 0, "Generated Rust symbol '$exportNamePrefix' was not found in:\n$source")
        val nextExport = source.indexOf("#[export_name = \"", start + 1)
        return if (nextExport < 0) source.substring(start) else source.substring(start, nextExport)
    }

    private fun assertGeneratedFunction(source: String, functionName: String) {
        val start = source.indexOf("#[export_name = \"kfun:#$functionName")
        assertTrue(start >= 0, "Generated Rust function '$functionName' was not found in:\n$source")
        val nextExport = source.indexOf("#[export_name = \"", start + 1)
        val body = if (nextExport < 0) source.substring(start) else source.substring(start, nextExport)
        assertTrue("__llvm" !in body, "Function '$functionName' unexpectedly fell back to LLVM:\n$body")
    }

    private fun assertLlvmFallback(source: String, functionName: String) {
        val start = source.indexOf("#[link_name = \"kfun:#$functionName")
        assertTrue(start >= 0, "LLVM fallback '$functionName' was not found in:\n$source")
        val end = source.indexOf(";\n", start)
        assertTrue(end >= 0, "LLVM fallback '$functionName' has no declaration terminator")
        assertContains(source.substring(start, end + 1), "__llvm")
    }

    private fun compile(
        compiler: Path,
        source: Path,
        output: Path,
        mode: String,
        extraArguments: List<String>,
    ): ProcessResult = runProcess(
        compiler.absolutePathString(),
        source.absolutePathString(),
        "-target", "linux_x64",
        "-Xnative-codegen=$mode",
        *extraArguments.toTypedArray(),
        "-o", output.absolutePathString(),
    )

    private fun executable(output: Path): Path = output.resolveSibling(output.fileName.toString() + ".kexe")

    private fun runProgram(executable: Path): ProcessResult {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        return runProcess(executable.absolutePathString()).also {
            assertEquals(0, it.exitCode, it.output)
        }
    }

    private fun outputLines(output: String): List<String> = output.lineSequence().filter(String::isNotBlank).toList()

    private fun runProcess(vararg command: String): ProcessResult {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-narrow-abi-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun isLinuxX64Host(): Boolean =
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
                System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val DISTRIBUTION_ENV = "KOTLIN_NATIVE_RUST_TEST_DIST"
        val PROFILES: List<Pair<String, List<String>>> = listOf(
            "debug" to emptyList(),
            "opt" to listOf("-opt"),
        )
    }
}
