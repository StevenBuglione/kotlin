/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlin.test.Test
import kotlin.test.assertEquals

class RustRendererTest {
    @Test
    fun rendersStructuredProgramDeterministically() {
        val file = RustSourceFile(
            attributes = listOf(RustAttribute("allow", "dead_code")),
            items = listOf(
                RustFunction(
                    name = "twice",
                    parameters = listOf(RustParameter("value", RustType.Named("i32"))),
                    returnType = RustType.Named("i32"),
                    body = RustBlock(
                        result = RustBinary(RustPath("value"), "*", RustInteger("2i32")),
                    ),
                    visibility = RustVisibility.PUBLIC,
                    attributes = listOf(RustAttribute("no_mangle")),
                    abi = "C",
                ),
                RustFunction(
                    name = "main",
                    body = RustBlock(
                        statements = listOf(
                            RustLet(
                                name = "value",
                                value = RustInteger("20i32"),
                                type = RustType.Named("i32"),
                                mutable = true,
                            ),
                            RustExpressionStatement(
                                RustIf(
                                    condition = RustBinary(RustPath("value"), ">", RustInteger("0i32")),
                                    thenBlock = RustBlock(
                                        statements = listOf(
                                            RustExpressionStatement(
                                                RustAssignment(
                                                    target = RustPath("value"),
                                                    value = RustBinary(RustPath("value"), "+", RustInteger("1i32")),
                                                )
                                            )
                                        )
                                    ),
                                )
                            ),
                            RustRawStatement("println!(\"{}\", value);"),
                        )
                    ),
                ),
            ),
        )

        val expected = """
            #![allow(dead_code)]

            #[no_mangle]
            pub extern "C" fn twice(value: i32) -> i32 {
                (value * 2i32)
            }

            fn main() {
                let mut value: i32 = 20i32;
                if (value > 0i32) {
                    value = (value + 1i32);
                };
                println!("{}", value);
            }
        """.trimIndent() + "\n"

        assertEquals(expected, RustRenderer.render(file))
        assertEquals(expected, RustRenderer.render(file))
    }

    @Test
    fun escapesRustStringLiterals() {
        val rendered = RustRenderer.render(
            RustSourceFile(
                items = listOf(
                    RustFunction(
                        name = "main",
                        body = RustBlock(
                            statements = listOf(
                                RustLet(
                                    name = "message",
                                    type = RustType.Reference(RustType.Named("str")),
                                    value = RustString("quote \" slash \\ line\n tab\t nul\u0000 bell\u0007"),
                                )
                            )
                        ),
                    )
                ),
            )
        )

        assertEquals(
            "fn main() {\n" +
                    "    let message: &str = \"quote \\\" slash \\\\ line\\n tab\\t nul\\0 bell\\u{7}\";\n" +
                    "}\n",
            rendered,
        )
    }
}
