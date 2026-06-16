import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lcdcode.shardquorum"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lcdcode.shardquorum"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations.add("en")
        base.archivesName = "shardquorum-$versionName"
    }

    buildTypes {
        release {
            // Keep F-Droid reproducible builds possible: no live git HEAD baked in.
            vcsInfo { include = false }

            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

configurations.all {
    resolutionStrategy {
        // Termux's aapt2 (build-tools 34) can't parse android-35 resources,
        // so pin AndroidX libs to their last compileSdk-34-compatible releases.
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
    }
}

dependencies {
    implementation(project(":sskr"))
    implementation(project(":qrcodegen"))
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

// Privacy guard: fail the build if the merged manifest ever declares a network
// permission. ShardQuorum handles key material; it must never be able to open
// a socket. Output paths (print spooler, SAF) run in OTHER processes.
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
)

// Typed task class so Gradle's configuration cache can serialize the task graph.
// Inline `doLast {}` lambdas capture script-scope references that CC can't handle.
abstract class VerifyNoNetworkTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val forbiddenPermissions: ListProperty<String>

    @TaskAction
    fun verify() {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val forbidden = forbiddenPermissions.get().toSet()
        val nodes = doc.getElementsByTagName("uses-permission")
        val offenders = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "name",
            )
            if (name in forbidden) offenders += name
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Privacy constraint violated: merged manifest declares forbidden " +
                    "permission(s): $offenders. This app must remain fully offline.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register<VerifyNoNetworkTask>("verifyNoNetwork$capitalized") {
            mergedManifest.set(
                variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST),
            )
            forbiddenPermissions.set(this@Build_gradle.forbiddenPermissions)
        }
        afterEvaluate {
            tasks.named("assemble$capitalized").configure { dependsOn(verifyTask) }
        }
    }
}
