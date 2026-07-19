package com.motionarcade.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipePrivacyInstrumentationTest {
    @Test
    fun packagedMediaPipeLoggerFactoryReturnsLocalNoOpLogger() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = Class.forName(
            "com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory",
        )
        val create = factory.getMethod(
            "create",
            Context::class.java,
            String::class.java,
            String::class.java,
        )

        val logger = create.invoke(null, context, "POSE_LANDMARKER", "LIVE_STREAM")

        assertEquals(
            "com.google.mediapipe.tasks.core.logging.TasksStatsDummyLogger",
            logger.javaClass.name,
        )
    }

    @Test
    fun packagedPoseModelCreatesTwoPoseLandmarkerWithoutRemoteTransport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseOptionsClass = Class.forName("com.google.mediapipe.tasks.core.BaseOptions")
        val baseOptionsBuilder = baseOptionsClass.getMethod("builder").invoke(null)
        baseOptionsBuilder.javaClass
            .getMethod("setModelAssetPath", String::class.java)
            .invoke(baseOptionsBuilder, "pose_landmarker_lite.task")
        val baseOptions = baseOptionsBuilder.javaClass.getMethod("build").invoke(baseOptionsBuilder)

        val runningModeClass = Class.forName(
            "com.google.mediapipe.tasks.vision.core.RunningMode",
        )
        val imageMode = requireNotNull(runningModeClass.enumConstants).first {
            (it as Enum<*>).name == "IMAGE"
        }
        val optionsClass = Class.forName(
            "com.google.mediapipe.tasks.vision.poselandmarker." +
                "PoseLandmarker\$PoseLandmarkerOptions",
        )
        val optionsBuilder = optionsClass.getMethod("builder").invoke(null)
        optionsBuilder.javaClass
            .getMethod("setBaseOptions", baseOptionsClass)
            .invoke(optionsBuilder, baseOptions)
        optionsBuilder.javaClass
            .getMethod("setRunningMode", runningModeClass)
            .invoke(optionsBuilder, imageMode)
        optionsBuilder.javaClass
            .getMethod("setNumPoses", Int::class.javaObjectType)
            .invoke(optionsBuilder, 2)
        val options = optionsBuilder.javaClass.getMethod("build").invoke(optionsBuilder)

        val landmarkerClass = Class.forName(
            "com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker",
        )
        val landmarker = landmarkerClass
            .getMethod("createFromOptions", Context::class.java, optionsClass)
            .invoke(null, context, options)
        (landmarker as AutoCloseable).close()
    }
}
