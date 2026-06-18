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
}

tasks.test {
    useJUnit()
    // The committed recovery vectors (docs/recovery-vectors.json) are the canonical
    // parity fixture for reimplementations. RecoveryVectorsTest regenerates them
    // from this module and fails on drift; run with -PupdateVectors to rewrite them.
    systemProperty("recoveryVectorsFile", rootProject.file("docs/recovery-vectors.json").absolutePath)
    if (project.hasProperty("updateVectors")) {
        systemProperty("updateVectors", "true")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
