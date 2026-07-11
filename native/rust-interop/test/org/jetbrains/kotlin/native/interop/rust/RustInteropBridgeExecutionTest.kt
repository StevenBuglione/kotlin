/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import org.junit.Assume.assumeTrue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RustInteropBridgeExecutionTest {
    @Test
    fun generatedHandleBridgeContainsPanicsAndEnforcesExactlyOnceDestruction() {
        assumeTrue("Cargo is required for the generated Rust bridge execution test", commandAvailable("cargo"))
        assumeTrue("rustc is required for the generated Rust bridge execution test", commandAvailable("rustc"))

        withTemporaryDirectory { workspace ->
            val plan = RustInteropTomlParser.parsePlan(BRIDGE_DEFINITION, "fixture.rustinterop.toml")
            val artifacts = RustInteropBridgeArtifactGenerator.generate(plan, setOf(FIXTURE_CRATE_NAME))
            write(workspace.resolve("Cargo.toml"), artifacts.cargoManifest)
            write(workspace.resolve("src/lib.rs"), artifacts.rustSource)
            write(workspace.resolve("local-crates/$FIXTURE_CRATE_NAME/Cargo.toml"), FIXTURE_MANIFEST)
            write(workspace.resolve("local-crates/$FIXTURE_CRATE_NAME/src/lib.rs"), FIXTURE_SOURCE)

            runTool(workspace, "cargo", "generate-lockfile", "--offline")
            runTool(
                workspace,
                "cargo", "clippy", "--offline", "--locked", "--release", "--all-targets", "--", "-D", "warnings",
            )
            runTool(
                workspace,
                "cargo", "rustc", "--offline", "--locked", "--release", "--lib", "--", "--print", "native-static-libs",
            )

            val operations = plan.operations.associateBy { it.id }
            val harness = renderHarness(
                compileSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operations.getValue("compile")),
                isMatchSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operations.getValue("is-match")),
                patternSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operations.getValue("pattern")),
                closeSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operations.getValue("close")),
                dropCountSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operations.getValue("drop-count")),
                freeUtf8Symbol = Regex("""void (knri_[A-Za-z0-9_]+_free_utf8)\(""")
                    .find(artifacts.cHeader)?.groupValues?.get(1)
                    ?: error("Generated C header has no UTF-8 release symbol:\n${artifacts.cHeader}"),
            )
            val harnessSource = workspace.resolve("harness.rs")
            val harnessExecutable = workspace.resolve("harness" + executableSuffix())
            write(harnessSource, harness)
            runTool(
                workspace,
                "rustc", "--edition=2021", harnessSource.toString(),
                "-L", "native=${workspace.resolve("target/release")}",
                "-l", "static=kotlin_rust_interop",
                "-o", harnessExecutable.toString(),
            )

            val execution = runTool(workspace, harnessExecutable.toString())
            assertEquals(0, execution.exitCode, execution.output)
            assertContains(execution.output, "BRIDGE_HARNESS_OK")
        }
    }

    private fun renderHarness(
        compileSymbol: String,
        isMatchSymbol: String,
        patternSymbol: String,
        closeSymbol: String,
        dropCountSymbol: String,
        freeUtf8Symbol: String,
    ): String = """
        use std::ptr;
        use std::slice;

        const KNRI_OK: i32 = 0;
        const KNRI_PANIC: i32 = 2;
        const KNRI_CLOSED_HANDLE: i32 = 4;

        #[repr(C)]
        struct KnriUtf8 {
            data: *mut u8,
            len: usize,
        }

        impl KnriUtf8 {
            fn empty() -> Self {
                Self { data: ptr::null_mut(), len: 0 }
            }
        }

        #[link(name = "kotlin_rust_interop", kind = "static")]
        unsafe extern "C" {
            fn $compileSymbol(
                pattern_data: *const u8,
                pattern_len: usize,
                output: *mut u64,
                error: *mut KnriUtf8,
            ) -> i32;
            fn $isMatchSymbol(
                handle: u64,
                input_data: *const u8,
                input_len: usize,
                output: *mut bool,
                error: *mut KnriUtf8,
            ) -> i32;
            fn $patternSymbol(handle: u64, output: *mut KnriUtf8, error: *mut KnriUtf8) -> i32;
            fn $closeSymbol(handle: u64, error: *mut KnriUtf8) -> i32;
            fn $dropCountSymbol(output: *mut u64, error: *mut KnriUtf8) -> i32;
            fn $freeUtf8Symbol(data: *mut u8, len: usize);
        }

        unsafe fn take_utf8(value: KnriUtf8) -> Vec<u8> {
            let result = if value.data.is_null() {
                Vec::new()
            } else {
                slice::from_raw_parts(value.data, value.len).to_vec()
            };
            $freeUtf8Symbol(value.data, value.len);
            result
        }

        unsafe fn drop_count() -> u64 {
            let mut output = 0_u64;
            let mut error = KnriUtf8::empty();
            assert_eq!($dropCountSymbol(&mut output, &mut error), KNRI_OK);
            assert!(error.data.is_null());
            output
        }

        fn main() {
            unsafe {
                let pattern = b"a\0b";
                let mut handle = 0_u64;
                let mut error = KnriUtf8::empty();
                assert_eq!(
                    $compileSymbol(pattern.as_ptr(), pattern.len(), &mut handle, &mut error),
                    KNRI_OK,
                );
                assert_ne!(handle, 0);
                assert_eq!(drop_count(), 0);

                let mut pattern_output = KnriUtf8::empty();
                error = KnriUtf8::empty();
                assert_eq!($patternSymbol(handle, &mut pattern_output, &mut error), KNRI_OK);
                assert_eq!(take_utf8(pattern_output), pattern);

                let mut matched = false;
                error = KnriUtf8::empty();
                assert_eq!(
                    $isMatchSymbol(handle, pattern.as_ptr(), pattern.len(), &mut matched, &mut error),
                    KNRI_OK,
                );
                assert!(matched);

                let panic_input = b"panic";
                error = KnriUtf8::empty();
                assert_eq!(
                    $isMatchSymbol(
                        handle,
                        panic_input.as_ptr(),
                        panic_input.len(),
                        &mut matched,
                        &mut error,
                    ),
                    KNRI_PANIC,
                );
                let panic_message = String::from_utf8(take_utf8(error)).unwrap();
                assert!(panic_message.contains("fixture panic"), "{panic_message}");

                // A successful call after the panic proves no unwind escaped the generated C boundary.
                pattern_output = KnriUtf8::empty();
                error = KnriUtf8::empty();
                assert_eq!($patternSymbol(handle, &mut pattern_output, &mut error), KNRI_OK);
                assert_eq!(take_utf8(pattern_output), pattern);

                error = KnriUtf8::empty();
                assert_eq!($closeSymbol(handle, &mut error), KNRI_OK);
                assert_eq!(drop_count(), 1);

                matched = false;
                error = KnriUtf8::empty();
                assert_eq!(
                    $isMatchSymbol(handle, pattern.as_ptr(), pattern.len(), &mut matched, &mut error),
                    KNRI_CLOSED_HANDLE,
                );
                assert_eq!(String::from_utf8(take_utf8(error)).unwrap(), "closed handle");

                error = KnriUtf8::empty();
                assert_eq!($closeSymbol(handle, &mut error), KNRI_CLOSED_HANDLE);
                assert_eq!(String::from_utf8(take_utf8(error)).unwrap(), "closed handle");
                assert_eq!(drop_count(), 1);
            }
            println!("BRIDGE_HARNESS_OK");
        }
    """.trimIndent() + "\n"

    private fun commandAvailable(command: String): Boolean = try {
        val result = runTool(null, command, "--version", failOnNonZero = false, timeoutSeconds = 20)
        result.exitCode == 0
    } catch (_: Exception) {
        false
    }

    private fun runTool(
        workingDirectory: Path?,
        vararg command: String,
        failOnNonZero: Boolean = true,
        timeoutSeconds: Long = 300,
    ): ProcessResult {
        val process = ProcessBuilder(command.toList())
            .directory(workingDirectory?.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["CARGO_NET_OFFLINE"] = "true"
                environment()["CARGO_TERM_COLOR"] = "never"
            }
            .start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { output.appendLine(it) }
            }
        }.apply { start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join()
            error("Timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}\n$output")
        }
        reader.join()
        val result = ProcessResult(process.exitValue(), output.toString())
        if (failOnNonZero && result.exitCode != 0) {
            error("Command failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.output}")
        }
        return result
    }

    private fun write(path: Path, contents: String) {
        Files.createDirectories(path.parent)
        Files.write(path, contents.toByteArray(StandardCharsets.UTF_8))
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rust-interop-bridge-execution")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun executableSuffix(): String = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val FIXTURE_CRATE_NAME = "knri-fixture"

        val FIXTURE_MANIFEST = """
            [package]
            name = "$FIXTURE_CRATE_NAME"
            version = "1.0.0"
            edition = "2021"
            publish = false
        """.trimIndent() + "\n"

        val FIXTURE_SOURCE = """
            use std::fmt;
            use std::sync::atomic::{AtomicU64, Ordering};

            static DROP_COUNT: AtomicU64 = AtomicU64::new(0);

            pub struct Probe {
                pattern: String,
            }

            #[derive(Debug)]
            pub struct FixtureError;

            impl fmt::Display for FixtureError {
                fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                    formatter.write_str("fixture error")
                }
            }

            impl Probe {
                pub fn new(pattern: &str) -> Result<Self, FixtureError> {
                    Ok(Self { pattern: pattern.to_owned() })
                }

                pub fn is_match(&self, input: &str) -> bool {
                    if input == "panic" {
                        panic!("fixture panic");
                    }
                    self.pattern == input
                }

                pub fn as_str(&self) -> &str {
                    &self.pattern
                }
            }

            impl Drop for Probe {
                fn drop(&mut self) {
                    DROP_COUNT.fetch_add(1, Ordering::SeqCst);
                }
            }

            pub fn drop_count() -> u64 {
                DROP_COUNT.load(Ordering::SeqCst)
            }
        """.trimIndent() + "\n"

        val BRIDGE_DEFINITION = """
            schema = 1
            package = "fixture.bridge"

            [crate]
            name = "$FIXTURE_CRATE_NAME"
            version = "1.0.0"
            features = []
            default-features = true

            [[handle]]
            id = "probe"
            rust-type = "knri_fixture::Probe"
            kotlin-type = "Probe"
            threading = "send-sync"

            [[operation]]
            id = "compile"
            kind = "constructor"
            rust-path = "knri_fixture::Probe::new"
            kotlin-name = "Probe.compile"
            receiver = "none"
            parameters = ["pattern:string"]
            return = "handle:probe"
            error = "kotlin-exception:ProbeException"
            panic = "kotlin-exception:ProbeException"
            threading = "caller"
            async = false

            [[operation]]
            id = "is-match"
            kind = "method"
            rust-path = "knri_fixture::Probe::is_match"
            kotlin-name = "Probe.isMatch"
            receiver = "borrow:probe"
            parameters = ["input:string"]
            return = "bool"
            error = "none"
            panic = "kotlin-exception:ProbeException"
            threading = "caller"
            async = false

            [[operation]]
            id = "pattern"
            kind = "property-get"
            rust-path = "knri_fixture::Probe::as_str"
            kotlin-name = "Probe.pattern"
            receiver = "borrow:probe"
            parameters = []
            return = "string"
            error = "none"
            panic = "kotlin-exception:ProbeException"
            threading = "caller"
            async = false

            [[operation]]
            id = "close"
            kind = "close"
            rust-path = "core::mem::drop"
            kotlin-name = "Probe.close"
            receiver = "consume:probe"
            parameters = []
            return = "unit"
            error = "none"
            panic = "kotlin-exception:ProbeException"
            threading = "caller"
            async = false

            [[operation]]
            id = "drop-count"
            kind = "function"
            rust-path = "knri_fixture::drop_count"
            kotlin-name = "dropCount"
            receiver = "none"
            parameters = []
            return = "u64"
            error = "none"
            panic = "kotlin-exception:ProbeException"
            threading = "caller"
            async = false
        """.trimIndent()
    }
}
