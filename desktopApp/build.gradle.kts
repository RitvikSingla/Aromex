import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(projects.sharedLogic)
    implementation(projects.sharedUI)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.okhttp)

    // Renders the issued invoice PDF inline in the Sales History detail view (ticket #83) —
    // rasterizes each page to an image (no embedded browser). The customer-facing bill is shown
    // as-generated; a built HTML-style layout is the fallback when no PDF exists yet.
    implementation(libs.pdfbox)

    implementation(libs.compose.uiToolingPreview)

    // Firestore on Desktop uses the pure JVM google-cloud-firestore client
    // authenticated with a short-lived OAuth token brokered by the gateway
    // (POST /firestore-token). The service-account key never touches the
    // desktop — see FirestoreTokenBroker.kt.
    implementation(libs.google.cloud.firestore)
    implementation(libs.google.auth.library.oauth2)

    // ViewModel unit tests (JVM) — the Desktop ViewModel is the testable home for the
    // shared cart/gating/totals logic (ticket #62).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.humblesolutions.aromex.MainKt"

        // Force gRPC/Netty into pure-Java mode so no native dylibs are
        // extracted/dlopen'd at runtime (macOS hardened runtime rejects unsigned
        // extracted libraries).
        jvmArgs += listOf(
            "-Dio.grpc.netty.shaded.io.netty.transport.noNative=true",
            "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true",
            "-Dio.netty.transport.noNative=true",
            "-Dio.netty.handler.ssl.noOpenSsl=true",
            "-Dio.netty.tryReflectionSetAccessible=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Aromex"
            packageVersion = "1.0.0"
            description = "Aromex desktop application"
            vendor = "Humble Solutions"

            macOS {
                bundleID = "com.humblesolutions.aromex"
                packageName = "Aromex"
                dockName = "Aromex"

                signing {
                    sign.set(true)
                    // Reads from gradle.properties / -P flags:
                    //   compose.desktop.mac.signing.identity=Developer ID Application: <Name> (<TEAM_ID>)
                    //   compose.desktop.mac.signing.keychain=/path/to/keychain (optional)
                }

                notarization {
                    appleID.set(providers.environmentVariable("NOTARIZATION_APPLE_ID"))
                    password.set(providers.environmentVariable("NOTARIZATION_PASSWORD"))
                    teamID.set(providers.environmentVariable("NOTARIZATION_TEAM_ID"))
                }

                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("runtime-entitlements.plist"))
            }
        }
    }
}