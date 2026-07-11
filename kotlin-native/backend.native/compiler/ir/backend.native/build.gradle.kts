import org.jetbrains.kotlin.cpp.CppUsage
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.konan.target.TargetWithSanitizer
import java.io.File

plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("project-tests-convention")
    id("test-inputs-check-v2")
}

val testCppRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(CppUsage.USAGE_ATTRIBUTE, objects.named(CppUsage.LIBRARY_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.DYNAMIC_LIB))
        attribute(TargetWithSanitizer.TARGET_ATTRIBUTE, TargetWithSanitizer.host)
    }
}

dependencies {
    api(project(":compiler:ir.tree"))

    compileOnly(jpsModel())
    compileOnly(commonDependency("org.jetbrains.intellij.deps:log4j")) { isTransitive = false }

    implementation(commonDependency("com.fasterxml:aalto-xml")) { isTransitive = false }
    implementation(commonDependency("org.codehaus.woodstox:stax2-api")) { isTransitive = false }
    implementation(libs.guava)
    implementation(libs.intellij.fastutil) { isTransitive = false }
    implementation(intellijJDom())
    implementation(intellijCore())
    implementation(project(":compiler:cli"))
    implementation(project(":compiler:container"))
    implementation(project(":compiler:frontend"))
    implementation(project(":compiler:fir:fir-serialization"))
    implementation(project(":compiler:fir:fir-native"))
    implementation(project(":compiler:ir.backend.common"))
    implementation(project(":compiler:ir.backend.native"))
    implementation(project(":compiler:ir.inline"))
    implementation(project(":compiler:ir.objcinterop"))
    implementation(project(":compiler:ir.serialization.common"))
    implementation(project(":compiler:ir.serialization.native"))
    implementation(project(":compiler:resolution"))
    implementation(project(":native:unsafe-mem"))
    implementation(project(":core:compiler.common.native"))
    implementation(project(":core:descriptors"))
    implementation(project(":core:descriptors.jvm"))
    implementation(project(":core:deserialization"))
    implementation(project(":kotlin-native:llvmInterop"))
    implementation(project(":kotlin-util-klib"))
    implementation(project(":kotlin-util-klib-metadata"))
    implementation(project(":native:base"))
    implementation(project(":native:frontend.native"))
    implementation(project(":native:kotlin-native-utils"))
    implementation(project(":native:objcexport-header-generator"))
    implementation(project(":native:objcexport-header-generator-k1"))
    implementation(project(":native:binary-options"))
    implementation(project(":native:rust-interop"))
    implementation(project(":compiler:cli:cli-native-klib"))
    implementation(project(":native:native.config"))
    implementation(project(":native:cinterop.deserialization"))
    implementation(project(":kotlinx-metadata-klib"))
    compileOnly(project(":kotlin-metadata")) // Only to fix IDE reporting unresolved references (KTI-3323).

    testImplementation(kotlinTest("junit"))
    testCppRuntime(project(":kotlin-native:Interop:Runtime"))
    testCppRuntime(project(":kotlin-native:llvmInterop"))
}

open class RustBoundaryAbiTestArgumentProvider @Inject constructor(
    objectFactory: ObjectFactory,
) : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val nativeLibraries: ConfigurableFileCollection = objectFactory.fileCollection()

    override fun asArguments(): Iterable<String> = listOf(
        "-Djava.library.path=${nativeLibraries.files.joinToString(File.pathSeparator) { it.parentFile.absolutePath }}"
    )
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(objects.newInstance<RustBoundaryAbiTestArgumentProvider>().apply {
        nativeLibraries.from(testCppRuntime)
    })
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.optIn.addAll(
            listOf(
                    "kotlinx.cinterop.ExperimentalForeignApi",
                    "org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi",
                    "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI"
            )
    )
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

sourcesJar()
javadocJar()
