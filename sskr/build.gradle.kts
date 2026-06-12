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
    testLogging {
        events("passed", "skipped", "failed")
    }
}
