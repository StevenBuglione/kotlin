/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("PackageDirectoryMismatch") // Old package for compatibility
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.RustInteropSettings
import javax.inject.Inject

internal abstract class DefaultRustInteropSettings @Inject constructor(
    private val params: Params,
) : RustInteropSettings {
    internal data class Params(
        val name: String,
        val services: Services,
    ) {
        internal open class Services @Inject constructor(
            val objectFactory: ObjectFactory,
            val projectLayout: ProjectLayout,
        )
    }

    override fun getName(): String = params.name

    override val crateName: Property<String> = params.services.objectFactory.property(String::class.java)

    override val crateVersion: Property<String> = params.services.objectFactory.property(String::class.java)

    override val packageName: Property<String> = params.services.objectFactory.property(String::class.java)

    override val definitionFile: RegularFileProperty = params.services.objectFactory.fileProperty().convention(
        params.services.projectLayout.projectDirectory.file("src/nativeInterop/rust/$name.rustinterop.toml")
    )

    override val features: SetProperty<String> = params.services.objectFactory.setProperty(String::class.java)
}

internal class DefaultRustInteropSettingsFactory(
    private val compilation: KotlinCompilation<*>,
) : NamedDomainObjectFactory<RustInteropSettings> {
    override fun create(name: String): RustInteropSettings {
        val params = DefaultRustInteropSettings.Params(
            name = name,
            services = compilation.project.objects.newInstance(DefaultRustInteropSettings.Params.Services::class.java),
        )
        return compilation.project.objects.newInstance(DefaultRustInteropSettings::class.java, params)
    }
}
