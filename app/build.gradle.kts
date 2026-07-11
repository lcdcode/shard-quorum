import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element
import java.io.File
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
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

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Privacy guard: fail the build if the merged manifest declares ANY permission
// outside this allowlist. ShardQuorum handles key material; it must never be
// able to open a socket. An allowlist fails closed: a permission pulled in by a
// future dependency (network or otherwise) breaks the build until reviewed,
// rather than slipping through a fixed denylist. Output paths (print spooler,
// SAF) run in OTHER processes and need no permission here.
val allowedPermissions = listOf(
    "android.permission.CAMERA",
)

// Stages the recovery-kit artifacts into a generated assets dir so they are
// bundled in the APK under assets/recovery-kit/. The sources are the repo's
// single canonical copies (the same files the release bundle ships), so the
// in-app kit can never drift from them. Typed + CC-friendly, like the guard below.
abstract class StageRecoveryKitAssetsTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    abstract val recoverHtml: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val specMarkdown: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val vectorsJson: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val readmeText: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val kitDir = outputDir.get().asFile.resolve("recovery-kit")
        kitDir.deleteRecursively()
        kitDir.mkdirs()
        // entry-name-in-assets -> source. README is renamed to its kit name.
        copyInto(kitDir, "ShardQuorum-recover.html", recoverHtml, hint = "run `node web/recover/build.js`")
        copyInto(kitDir, "RECOVERY-SPEC.md", specMarkdown)
        copyInto(kitDir, "recovery-vectors.json", vectorsJson)
        copyInto(kitDir, "README.txt", readmeText)
    }

    private fun copyInto(dir: File, name: String, source: RegularFileProperty, hint: String? = null) {
        val src = source.orNull?.asFile
        if (src == null || !src.exists()) {
            val suffix = hint?.let { " ($it)" } ?: ""
            throw GradleException(
                "Recovery kit source missing for '$name': ${src?.path ?: "<unset>"}$suffix. " +
                    "The app bundles the recovery artifacts; they must be present to build.",
            )
        }
        src.copyTo(File(dir, name), overwrite = true)
    }
}

// Typed task class so Gradle's configuration cache can serialize the task graph.
// Inline `doLast {}` lambdas capture script-scope references that CC can't handle.
abstract class VerifyNoNetworkTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowedPermissions: ListProperty<String>

    // The app's own applicationId. Permissions in this namespace (e.g. AGP's
    // injected ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) are
    // app-private signature permissions and cannot grant network egress, so
    // they are allowed without enumerating each one.
    @get:Input
    abstract val ownNamespace: Property<String>

    @TaskAction
    fun verify() {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val allowed = allowedPermissions.get().toSet()
        val ownPrefix = ownNamespace.get() + "."
        val nodes = doc.getElementsByTagName("uses-permission")
        val offenders = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "name",
            )
            if (name !in allowed && !name.startsWith(ownPrefix)) offenders += name
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Privacy constraint violated: merged manifest declares unexpected " +
                    "permission(s): $offenders. This app must remain fully offline; " +
                    "if a new permission is genuinely required, add it to " +
                    "allowedPermissions after review.",
            )
        }
    }
}

// Single staging task; every variant's merged assets consume its output.
val stageRecoveryKitAssets = tasks.register<StageRecoveryKitAssetsTask>("stageRecoveryKitAssets") {
    recoverHtml.set(rootProject.layout.projectDirectory.file("ShardQuorum-recover.html"))
    specMarkdown.set(rootProject.layout.projectDirectory.file("docs/RECOVERY-SPEC.md"))
    vectorsJson.set(rootProject.layout.projectDirectory.file("docs/recovery-vectors.json"))
    readmeText.set(rootProject.layout.projectDirectory.file("docs/recovery-kit-README.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/recoveryKitAssets"))
}

androidComponents {
    onVariants { variant ->
        // Bundle the staged recovery-kit artifacts; wires merge<Variant>Assets to depend on staging.
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageRecoveryKitAssets,
            StageRecoveryKitAssetsTask::outputDir,
        )

        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register<VerifyNoNetworkTask>("verifyNoNetwork$capitalized") {
            mergedManifest.set(
                variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST),
            )
            allowedPermissions.set(this@Build_gradle.allowedPermissions)
            ownNamespace.set(android.namespace)
        }
        afterEvaluate {
            // Gate every packaging path, not just assemble: bundle (AAB, the
            // typical store artifact) and the aggregate check task must not be
            // able to ship without the guard running.
            listOf("assemble$capitalized", "bundle$capitalized").forEach { taskName ->
                tasks.findByName(taskName)?.dependsOn(verifyTask)
            }
            tasks.named("check").configure { dependsOn(verifyTask) }
        }
    }
}
