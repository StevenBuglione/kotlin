/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class DefFileNoCallbackFunctionsTest {
    @Test
    fun parsesNoCallbackFunctionsAsExactNames() {
        val file = File.createTempFile("no-callback-functions", ".def")
        try {
            file.writeText("noCallbackFunctions = leaf_one leaf_two\n")
            assertEquals(
                    listOf("leaf_one", "leaf_two"),
                    DefFile(file, emptyMap()).config.noCallbackFunctions.toList()
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun defaultsToEmptyList() {
        val file = File.createTempFile("no-callback-functions-empty", ".def")
        try {
            file.writeText("headers = sample.h\n")
            assertEquals(emptyList<String>(), DefFile(file, emptyMap()).config.noCallbackFunctions.toList())
        } finally {
            file.delete()
        }
    }
}
