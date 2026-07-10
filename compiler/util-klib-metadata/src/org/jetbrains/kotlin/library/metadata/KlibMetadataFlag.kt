/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.library.metadata

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.konan.properties.Properties
import org.jetbrains.kotlin.library.KLIB_PROPERTY_METADATA_FLAGS

/**
 * Flags are stored in two places (see KT-87443):
 *  - since 2.5.0, in the manifest as a space-separated list of [nameInManifest]s under the
 *    [KLIB_PROPERTY_METADATA_FLAGS] property;
 *  - in the metadata header `flags` bitmask ([KlibMetadataProtoBuf.Header.flags]) via [mask] - this is a legacy and
 *    is to be dropped together with the header file in KT-87197.
 */
enum class KlibMetadataFlag(val mask: Int, val nameInManifest: String) {
    PRE_RELEASE(mask = 0x2, nameInManifest = "pre_release");
}

fun Set<KlibMetadataFlag>.toHeaderMask(): Int = fold(0) { acc, flag -> acc or flag.mask }

fun Set<KlibMetadataFlag>.toManifestValue(): String? =
    takeIf { it.isNotEmpty() }?.map { it.nameInManifest }?.sorted()?.joinToString(" ")

fun addMetadataFlagsToManifest(manifestProperties: Properties, languageVersionSettings: LanguageVersionSettings) {
    computeKlibMetadataFlags(languageVersionSettings).toManifestValue()?.let {
        manifestProperties.setProperty(KLIB_PROPERTY_METADATA_FLAGS, it)
    }
}

fun addMetadataFlagsToHeader(header: KlibMetadataProtoBuf.Header.Builder, languageVersionSettings: LanguageVersionSettings) {
    val headerFlags = computeKlibMetadataFlags(languageVersionSettings).toHeaderMask()
    if (headerFlags != 0) {
        header.flags = headerFlags
    }
}

fun computeKlibMetadataFlags(languageVersionSettings: LanguageVersionSettings): Set<KlibMetadataFlag> =
    buildSet {
        if (languageVersionSettings.isPreRelease()) add(KlibMetadataFlag.PRE_RELEASE)
    }
