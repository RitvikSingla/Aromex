import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.kotlinSerialization)
    // SKIE (Touchlab) — compile-time only; reshapes the generated iOS Swift API
    // (Kotlin Flow → AsyncSequence, sealed classes → Swift enums, suspend cancellation).
    // Pinned to 0.10.13, the release that adds Kotlin 2.4.0 support. No runtime dep;
    // Android/Desktop artifacts are unaffected. (Ticket #34)
    alias(libs.plugins.skie)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        version = "1.0.0"
        summary = "Aromex shared KMP module"
        homepage = "https://example.com"
        ios.deploymentTarget = "14.0"

        framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }

    jvm()

    androidLibrary {
       namespace = "com.humblesolutions.aromex.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.ktor.client.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.client.mock)
        }
        // HL access is a shared Ktor client (see ticket #26 / CLAUDE.md deviation note).
        // Each target ships the single Ktor engine on its classpath; the shared client
        // builds HttpClient() and Ktor auto-selects it — no expect/actual, and Swift
        // never has to construct an engine.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// SKIE (ticket #34): disable build-time analytics upload — no network calls during builds/CI.
skie {
    analytics {
        enabled.set(false)
    }
}