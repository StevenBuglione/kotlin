/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Base class for the `-P`-driven test data manager tasks: [CheckTestDataModuleTask] (`checkTestData`)
 * and [UpdateTestDataModuleTask] (`updateTestData`).
 *
 * Both tasks are [JavaExec] tasks that run the module's tests via `TestDataManagerRunner` with a
 * **fixed** mode. Their options are accepted **only** as Gradle properties (`-P`), never as `--option`
 * CLI flags.
 *
 * ## Why `-P` options instead of `@Option`
 *
 * CLI `@Option` values are part of Gradle's configuration-cache (CC) key, so iterating on, e.g.,
 * `--test-data-path` re-runs Gradle configuration (which can take 1–2 minutes on this project) for
 * each different value. Reading options as `-P` properties **only at execution time** keeps the CC
 * entry stable across different option values, so subsequent invocations skip reconfiguration entirely.
 *
 * ## Trade-off: opaque to Gradle's task cache
 *
 * The same mechanism that keeps the CC stable — not exposing options as `@Input` properties — also
 * hides them from Gradle's task-identity machinery. As a result, these tasks are never UP-TO-DATE,
 * never restored from the build cache, and the test runner is invoked on every invocation. The choice
 * is permanent and intentional: input-tracking the options would undo the CC benefit.
 *
 * ## Options
 *
 * All options are passed as Gradle properties; they are forwarded to the test runner as `-D<key>`
 * system properties at execution time.
 *
 * | Property                                                              | Effect                                          |
 * |-----------------------------------------------------------------------|-------------------------------------------------|
 * | `org.jetbrains.kotlin.testDataManager.options.testDataPath`           | Comma-separated test data paths (dir or file)   |
 * | `org.jetbrains.kotlin.testDataManager.options.testClassPattern`       | Regex pattern for test class names              |
 * | `org.jetbrains.kotlin.testDataManager.options.goldenOnly`             | Run only golden tests (empty variant chain)     |
 * | `org.jetbrains.kotlin.testDataManager.options.incremental`            | Only run variant tests for changed golden paths |
 *
 * @see CheckTestDataModuleTask
 * @see UpdateTestDataModuleTask
 */
abstract class AbstractTestDataModuleTask : JavaExec() {
    @get:Inject
    protected abstract val providers: ProviderFactory

    /**
     * The fixed mode this task runs in; forwarded to the runner. Each concrete subclass pins it.
     *
     * `@Internal` because the mode is a constant of the task type, not a tracked input — see the
     * class KDoc on why these tasks deliberately expose no `@Input` options.
     */
    @get:Internal
    protected abstract val mode: Mode

    init {
        group = "verification"
        mainClass.set("org.jetbrains.kotlin.analysis.test.data.manager.TestDataManagerRunner")
    }

    /**
     * Forwards the fixed [mode] and all `-P` options to the test runner as `-D` system properties,
     * then delegates to [JavaExec.exec].
     *
     * All options are forwarded regardless of [mode] for symmetry; the runner ignores options that are
     * irrelevant to the current mode (e.g. `incremental` is effective only in update mode).
     */
    @TaskAction
    override fun exec() {
        systemProperty(TestDataManagerOption.MODE, mode)
        forwardOption(TestDataManagerOption.TEST_DATA_PATH)
        forwardOption(TestDataManagerOption.TEST_CLASS_PATTERN)
        forwardOption(TestDataManagerOption.GOLDEN_ONLY)
        forwardOption(TestDataManagerOption.INCREMENTAL)
        super.exec()
    }

    private fun forwardOption(key: String) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }

    /**
     * How a test data manager task handles test data file mismatches:
     * - [CHECK] fails on mismatches without modifying anything.
     * - [UPDATE] rewrites mismatched files.
     *
     * The forwarded name is parsed by `TestDataManagerRunner` inside the test process.
     */
    protected enum class Mode {
        CHECK,
        UPDATE,
    }
}
