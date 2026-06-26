import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import java.net.URI
import java.security.MessageDigest

val huggingFaceOAuthClientId: String = providers
    .gradleProperty("pocketmind.huggingFaceOAuthClientId")
    .orElse(providers.environmentVariable("POCKETMIND_HF_OAUTH_CLIENT_ID"))
    .orElse("")
    .get()

val zvecVersion = "0.5.1"
val zvecGeneratedRoot = layout.buildDirectory.dir("generated/zvec").get().asFile
val zvecJniLibsRoot = zvecGeneratedRoot.resolve("jniLibs")
val zvecArchiveLibName = "libzvec.so"
val zvecRuntimeLibName = "libzvec_c_api.so"
val zvecArm64Lib = zvecJniLibsRoot.resolve("arm64-v8a/$zvecRuntimeLibName")
val zvecArm64ArchiveLib = zvecJniLibsRoot.resolve("arm64-v8a/$zvecArchiveLibName")
val zvecIncludeRoot = zvecGeneratedRoot.resolve("include")
val zvecCApiHeader = zvecIncludeRoot.resolve("zvec/c_api.h")
val zvecNativeBaseUrl = "https://github.com/zvec-ai/zvec-dart/releases/download/v$zvecVersion"
val zvecArm64LibSha256 = "708a58bf32a232890fd3e761bf662c05357aca9e0a4ba2783e4a9f86b78bbe3f"
val zvecCApiHeaderSha256 = "ea6b3f3373f29799a885442bf51ec1604426055e5b7b56ea2e12d8ccd70b2af0"
val zvecAndroidArm64ZipSeed = providers
    .gradleProperty("pocketmind.zvecAndroidArm64Zip")
    .orElse(providers.environmentVariable("POCKETMIND_ZVEC_ANDROID_ARM64_ZIP"))
    .orNull
val zvecCApiHeaderSeed = providers
    .gradleProperty("pocketmind.zvecCApiHeader")
    .orElse(providers.environmentVariable("POCKETMIND_ZVEC_C_API_HEADER"))
    .orNull

fun downloadToFile(url: String, outputFile: File) {
    var lastFailure: Exception? = null
    repeat(3) { attempt ->
        try {
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.getInputStream().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            return
        } catch (error: Exception) {
            lastFailure = error
            if (attempt < 2) {
                Thread.sleep(2_000L * (attempt + 1))
            }
        }
    }
    throw GradleException("Failed to download $url", lastFailure)
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

fun verifySha256(file: File, expected: String) {
    val actual = sha256(file)
    check(actual == expected) {
        "Unexpected SHA-256 for ${file.name}: $actual (expected $expected)"
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bytedance.zgx.pocketmind"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.bytedance.zgx.pocketmind"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "HUGGING_FACE_OAUTH_CLIENT_ID", "\"$huggingFaceOAuthClientId\"")
        // RC perf collection entry points are gated off by default. Only the dedicated
        // rcPerfRelease variant flips this to true, so the production release never exposes the
        // harness receiver/activity/service.
        buildConfigField("Boolean", "RC_PERF_ENABLED", "false")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DZVEC_PREBUILT_DIR=${zvecJniLibsRoot.absolutePath}",
                    "-DZVEC_INCLUDE_DIR=${zvecIncludeRoot.absolutePath}",
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // release-like variant used only for RC performance collection. It inherits the
        // release minify/shrink/proguard path so measured numbers reflect the shipping shape,
        // but enables the controlled RC perf harness and is signed with the debug key so it can
        // be installed on a real device. It deliberately keeps the production applicationId (no
        // suffix) so the harness can exercise models the app already downloaded on the device,
        // and it never wipes app data or model directories.
        create("rcPerfRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "RC_PERF_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(zvecJniLibsRoot)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    (
        providers.gradleProperty("aiBehaviorActualTraceFile").orNull
            ?: providers.systemProperty("aiBehaviorActualTraceFile").orNull
    )?.let { outputPath ->
        systemProperty("aiBehaviorActualTraceFile", outputPath)
    }
}

val downloadZvecNativeLibs by tasks.registering {
    description = "Download zvec v$zvecVersion Android arm64 native library and C API header."
    inputs.property("zvecVersion", zvecVersion)
    inputs.property("zvecArm64LibSha256", zvecArm64LibSha256)
    inputs.property("zvecCApiHeaderSha256", zvecCApiHeaderSha256)
    inputs.property("zvecAndroidArm64ZipSeed", zvecAndroidArm64ZipSeed ?: "")
    inputs.property("zvecCApiHeaderSeed", zvecCApiHeaderSeed ?: "")
    outputs.file(zvecArm64Lib)
    outputs.file(zvecCApiHeader)

    doLast {
        if (!zvecArm64Lib.isFile) {
            val libDir = zvecArm64Lib.parentFile
            val zipFile = temporaryDir.resolve("libzvec-android-arm64-v8a.zip")
            libDir.mkdirs()
            if (zvecArm64ArchiveLib.isFile) {
                zvecArm64ArchiveLib.copyTo(zvecArm64Lib, overwrite = true)
            } else {
                val seedZip = zvecAndroidArm64ZipSeed?.let(::file)?.takeIf { it.isFile }
                if (seedZip == null) {
                    downloadToFile("$zvecNativeBaseUrl/libzvec-android-arm64-v8a.zip", zipFile)
                } else {
                    seedZip.copyTo(zipFile, overwrite = true)
                }
                copy {
                    from(zipTree(zipFile))
                    into(libDir)
                    include(zvecArchiveLibName)
                    rename { zvecRuntimeLibName }
                }
            }
            check(zvecArm64Lib.isFile) {
                "Downloaded zvec archive did not contain $zvecArchiveLibName"
            }
        }
        verifySha256(zvecArm64Lib, zvecArm64LibSha256)
        zvecArm64ArchiveLib.delete()

        if (!zvecCApiHeader.isFile) {
            zvecCApiHeader.parentFile.mkdirs()
            val seedHeader = zvecCApiHeaderSeed?.let(::file)?.takeIf { it.isFile }
            if (seedHeader == null) {
                downloadToFile(
                    "https://raw.githubusercontent.com/alibaba/zvec/v$zvecVersion/src/include/zvec/c_api.h",
                    zvecCApiHeader,
                )
            } else {
                seedHeader.copyTo(zvecCApiHeader, overwrite = true)
            }
            check(zvecCApiHeader.isFile) {
                "Failed to download zvec c_api.h"
            }
        }
        verifySha256(zvecCApiHeader, zvecCApiHeaderSha256)
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadZvecNativeLibs)
}

tasks.matching { task ->
    task.name != "downloadZvecNativeLibs" &&
        (
            task.name.startsWith("configureCMake") ||
                task.name.startsWith("buildCMake") ||
                task.name.endsWith("NativeLibs")
            )
}.configureEach {
    dependsOn(downloadZvecNativeLibs)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(libs.localagents.rag)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
