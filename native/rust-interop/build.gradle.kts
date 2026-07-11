plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("gradle-plugin-published-compiler-dependency-configuration")
    id("project-tests-convention")
    id("test-inputs-check-v2")
}

description = "Canonical Kotlin/Native Rust interop bridge plans"

dependencies {
    api(kotlinStdlib())
    testImplementation(kotlinTest("junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

publish()

runtimeJar()
sourcesJar()
javadocJar()
