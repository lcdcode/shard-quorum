// Vendored copy of Project Nayuki's QR Code generator library (MIT), pinned to
// upstream v1.8.0. See docs/reference/PROVENANCE.md for source, commit hash,
// and file checksums. Sources are verbatim - do NOT modify them; replace the
// whole set from a newer upstream tag if an update is ever needed.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(project(":sskr"))
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
