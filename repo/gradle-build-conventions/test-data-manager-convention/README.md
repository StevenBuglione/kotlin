# Test Data Manager Convention Plugin

Gradle convention plugin for managing test data files in the Kotlin project.

## Overview

The test data manager provides automated checking and updating of test data files. It runs tests in a special mode that compares actual
output against expected files and can update them when differences are found.

## Plugin

### `test-data-manager`

Apply to modules that have managed test data:

```kotlin
plugins {
    id("test-data-manager")
}
```

This registers two tasks in the module:

- **`checkTestData`** ([CheckTestDataModuleTask]) — runs tests and **fails** on mismatches without modifying anything. Use for verification
  (e.g., CI, or sanity-checking generated files).
- **`updateTestData`** ([UpdateTestDataModuleTask]) — runs tests and **updates** files on mismatches.

Both accept their options only via `-P` Gradle properties (not `--option` CLI flags); see
[Configuration Cache](#configuration-cache) for why. There is no global orchestrator task — Gradle's task-name matching runs the task in every
applicable module.

## Usage

### Per-Module Execution

Run on a single module:

```bash
# Check mode - fails if test data doesn't match, writes nothing
./gradlew :analysis:analysis-api-fir:checkTestData

# Update mode - updates test data files
./gradlew :analysis:analysis-api-fir:updateTestData

# Filter by test data path
./gradlew :analysis:analysis-api-fir:updateTestData \
    -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=testData/myTest.kt

# Filter by test class pattern
./gradlew :analysis:analysis-api-fir:updateTestData \
    -Porg.jetbrains.kotlin.testDataManager.options.testClassPattern=.*Fir.*
```

### Across All Modules

Gradle's task-name matching runs the task in every module with the plugin:

```bash
# Check all test data
./gradlew checkTestData

# Update all test data
./gradlew updateTestData

# Filter by path or pattern (applies to all modules)
./gradlew updateTestData -Porg.jetbrains.kotlin.testDataManager.options.testDataPath=testData/myTest.kt

# Run only golden tests (skip variant-specific tests)
./gradlew updateTestData -Porg.jetbrains.kotlin.testDataManager.options.goldenOnly=true

# Incremental update — only run variant tests for changed golden paths
./gradlew updateTestData -Porg.jetbrains.kotlin.testDataManager.options.incremental=true
```

### Module Filtering

To limit the run to a subset of modules, supply explicit task paths:

```bash
# Single module
./gradlew :analysis:analysis-api-fir:updateTestData

# Multiple modules
./gradlew :analysis:analysis-api-fir:updateTestData :analysis:stubs:updateTestData
```

## Available Options

All options are passed as `-P` Gradle properties under the
`org.jetbrains.kotlin.testDataManager.options.*` namespace and forwarded to the test runner as `-D`
system properties at execution time.

| Gradle property                                                 | Effect                                                                     |
|-----------------------------------------------------------------|----------------------------------------------------------------------------|
| `org.jetbrains.kotlin.testDataManager.options.testDataPath`     | Comma-separated test data paths (dir or file)                              |
| `org.jetbrains.kotlin.testDataManager.options.testClassPattern` | Regex pattern for test class names                                         |
| `org.jetbrains.kotlin.testDataManager.options.goldenOnly`       | Run only golden tests (empty variant chain)                                |
| `org.jetbrains.kotlin.testDataManager.options.incremental`      | Only run variant tests for changed golden paths (effective in update mode) |

## Configuration Cache

With `--configuration-cache` enabled, two consecutive runs of the same task that differ only in the values of the `-P` options listed above
will reuse the same CC entry — Gradle prints
`Reusing configuration cache.` and skips reconfiguration entirely. This is the whole point of passing options as `-P` properties instead of
CLI `--option` flags: `@Option` values are part of the CC key, so iterating on `--test-data-path` would otherwise cause a full
reconfiguration (often 1–2 minutes)
on every value change.

### Trade-off: not Gradle-cacheable

The same mechanism that keeps the CC stable — not declaring options as `@Input` properties — also hides them from Gradle's task-identity
machinery. As a result, both tasks are **never** UP-TO-DATE and their result is never restored from the build cache: the test runner is
invoked on every invocation. Both are `JavaExec` tasks with no declared outputs that always re-run. The choice is permanent and
intentional — input-tracking the options would undo the CC benefit.

## Execution Order

Module ordering is determined by `mustRunAfter` dependencies inherited from each module's
`test` task. This ensures that golden modules (which establish baseline `.txt` files) run before dependent modules (which may create
prefixed variants like `.descriptors.txt`).

To configure ordering, set up `mustRunAfter` on your module's test task in `build.gradle.kts`:

```kotlin
tasks.named<Test>("test") {
    mustRunAfter(":analysis:analysis-api-fir:test")
}
```

Both tasks automatically inherit this ordering (with `:test` references rewritten to the corresponding manager task).

## See Also

- [analysis/test-data-manager](../../../analysis/test-data-manager/README.md) — Implementation module with `ManagedTest` interface and
  `assertEqualsToTestDataFile()` assertions
