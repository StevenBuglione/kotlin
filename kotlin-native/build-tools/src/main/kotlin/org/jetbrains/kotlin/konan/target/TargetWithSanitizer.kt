/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.target

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import java.io.Serializable

/**
 * Sanitizer variants used while building Kotlin/Native's native components.
 *
 * This deliberately does not extend [SanitizerKind]: root Gradle scripts may
 * load that class from the bootstrap compiler, so its shape must remain stable.
 */
enum class BuildToolsSanitizer(val targetSuffix: String, val clangFlag: String) {
    ADDRESS("_asan", "-fsanitize=address"),
    THREAD("_tsan", "-fsanitize=thread"),
    UNDEFINED("_ubsan", "-fsanitize=undefined"),
}

private fun SanitizerKind.toBuildToolsSanitizer(): BuildToolsSanitizer = when (this) {
    SanitizerKind.ADDRESS -> BuildToolsSanitizer.ADDRESS
    SanitizerKind.THREAD -> BuildToolsSanitizer.THREAD
}

/**
 * [Target][KonanTarget] with an optional build-tools-local sanitizer.
 *
 * Can be used as a gradle attribute: `attribute(TargetWithSanitizer.TARGET_ATTRIBUTE, target.withSanitizer())`
 */
class TargetWithSanitizer(
        val target: KonanTarget,
        val sanitizer: BuildToolsSanitizer?,
) : Named, Serializable {
    override fun getName(): String = "$target${sanitizer?.targetSuffix.orEmpty()}"

    override fun toString(): String = name

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        val otherTarget = other as? TargetWithSanitizer ?: return false
        return name == otherTarget.name
    }

    companion object {
        @JvmField
        val TARGET_ATTRIBUTE = Attribute.of("org.jetbrains.kotlin.target", TargetWithSanitizer::class.java)

        @JvmField
        val host = TargetWithSanitizer(HostManager.host, null)
    }
}

/**
 * Construct [TargetWithSanitizer] from a compiler-supported sanitizer.
 */
fun KonanTarget.withSanitizer(sanitizer: SanitizerKind? = null) =
        TargetWithSanitizer(this, sanitizer?.toBuildToolsSanitizer())

/** Construct [TargetWithSanitizer] from a build-only sanitizer variant. */
fun KonanTarget.withSanitizer(sanitizer: BuildToolsSanitizer) = TargetWithSanitizer(this, sanitizer)

/**
 * All known targets with their sanitizers.
 */
val PlatformManager.allTargetsWithSanitizers
    get() = this.enabled.flatMap { target ->
        val compilerSanitizers = target.supportedSanitizers().map { target.withSanitizer(it) }
        val buildOnlySanitizers = if (target == KonanTarget.LINUX_X64) {
            listOf(target.withSanitizer(BuildToolsSanitizer.UNDEFINED))
        } else {
            emptyList()
        }
        listOf(target.withSanitizer()) + compilerSanitizers + buildOnlySanitizers
    }
