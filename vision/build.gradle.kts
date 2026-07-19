import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.motionarcade.vision"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

configurations.configureEach {
    exclude(group = "com.google.android.datatransport")
    exclude(group = "com.google.firebase", module = "firebase-encoders")
    exclude(group = "com.google.firebase", module = "firebase-encoders-json")
    exclude(group = "com.google.firebase", module = "firebase-encoders-proto")
}

dependencies {
    api(project(":game-core"))
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport")
    }
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

val verifyPoseModel by tasks.registering {
    group = "verification"
    description = "Verifies the pinned on-device Pose Landmarker model identity."

    val model = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task")
    inputs.file(model)

    doLast {
        val file = model.asFile
        check(file.length() == 5_777_746L) {
            "Unexpected Pose Landmarker model size: ${file.length()}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        check(digest == "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a") {
            "Unexpected Pose Landmarker model SHA-256: $digest"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyPoseModel)
}
