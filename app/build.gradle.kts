import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.dsl.ManagedVirtualDevice
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.google.protobuf.gradle.id
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

abstract class DisableMediaPipeRemoteLoggingFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className ==
            "com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory"

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            val target = super.visitMethod(access, name, descriptor, signature, exceptions)
            val factoryDescriptor =
                "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)" +
                    "Lcom/google/mediapipe/tasks/core/logging/TasksStatsLogger;"
            if (name != "create" || descriptor != factoryDescriptor || target == null) {
                return target
            }
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitEnd() {
                    target.visitCode()
                    target.visitVarInsn(Opcodes.ALOAD, 0)
                    target.visitVarInsn(Opcodes.ALOAD, 1)
                    target.visitVarInsn(Opcodes.ALOAD, 2)
                    target.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/google/mediapipe/tasks/core/logging/TasksStatsDummyLogger",
                        "create",
                        "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)" +
                            "Lcom/google/mediapipe/tasks/core/logging/TasksStatsDummyLogger;",
                        false,
                    )
                    target.visitInsn(Opcodes.ARETURN)
                    target.visitMaxs(3, 3)
                    target.visitEnd()
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.motionarcade.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.motionarcade.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // Package one native ABI per installable APK. Besides removing unusable native payloads from
    // each device, this reduces the bytes read by the two mandatory whole-APK identity passes
    // without weakening or pre-warming their measured five-second owner boundary.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            localDevices {
                create("pixel6Api37") {
                    device = "Pixel 6"
                    apiLevel = 37
                    systemImageSource = "google"
                    testedAbi = "x86_64"
                    pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

configurations.configureEach {
    exclude(group = "com.google.android.datatransport")
    exclude(group = "com.google.firebase", module = "firebase-encoders")
    exclude(group = "com.google.firebase", module = "firebase-encoders-json")
    exclude(group = "com.google.firebase", module = "firebase-encoders-proto")
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // Native provenance validation requires APK members to remain byte-identical
        // to their dependency-verified AAR members. Keep this debug-only so release
        // variants retain the normal Android native-symbol stripping policy.
        variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    }
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(
            DisableMediaPipeRemoteLoggingFactory::class.java,
            InstrumentationScope.ALL,
        ) { }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }
}

dependencies {
    implementation(project(":game-core"))
    implementation(project(":games"))
    implementation(project(":vision"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

}
