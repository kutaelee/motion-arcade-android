package com.motionarcade.vision.pose

import java.io.OutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePoseJvmBoundaryTest {
    @Test
    fun rawPoseTypesAreFinalAndJvmPackagePrivate() {
        rawTypes().forEach { type ->
            assertFalse("${type.name} must not be JVM-public", Modifier.isPublic(type.modifiers))
            assertTrue("${type.name} must remain final", Modifier.isFinal(type.modifiers))
            assertTrue(
                "${type.name} exported a public constructor",
                type.declaredConstructors.none { Modifier.isPublic(it.modifiers) },
            )
        }
    }

    @Test
    fun externalJavaCannotNameConstructOrReadRawPoseButCanConsumeSemanticSummary() {
        val rawAttack =
            compileJava(
                "RawPoseAttack",
                """
                package hostile;

                import com.motionarcade.vision.pose.LivePoseLandmark;
                import com.motionarcade.vision.pose.LivePoseObservationFrame;
                import com.motionarcade.vision.pose.LivePoseSessionLandmark;
                import com.motionarcade.vision.pose.LivePoseSessionResult;
                import com.motionarcade.vision.pose.FishingPosePoint;
                import com.motionarcade.vision.pose.FishingPoseSample;

                final class RawPoseAttack {
                  Object landmark = new LivePoseLandmark(0.25f, 0.5f, 0.0f, 1.0f, 1.0f);
                  Object callbackLandmark =
                      new LivePoseSessionLandmark(0.25f, 0.5f, 0.0f, 1.0f, 1.0f);
                  long read(LivePoseObservationFrame frame) { return frame.getSourceTimestampNs(); }
                  long timestamp(LivePoseSessionResult result) { return result.getTaskTimestampMs(); }
                  Object fishingPoint = new FishingPosePoint(0.25f, 0.5f, 0.0f, 1.0f);
                  int fishingSize(FishingPoseSample sample) { return sample.getLandmarks().size(); }
                }
                """.trimIndent(),
            )
        assertFalse("external raw-pose attack unexpectedly compiled: ${rawAttack.errors}", rawAttack.success)

        val semanticConsumer =
            compileJava(
                "SemanticConsumer",
                """
                package hostile;

                import com.motionarcade.vision.pose.LivePoseSemanticSink;
                import com.motionarcade.vision.pose.LivePoseSemanticSummary;
                import com.motionarcade.vision.motion.FishingMotionFrame;
                import com.motionarcade.vision.motion.FishingMotionFrameSink;

                final class SemanticConsumer implements LivePoseSemanticSink {
                  public void onPoseSemanticSummary(LivePoseSemanticSummary summary) {
                    int count = summary.getPoseCount();
                    long revision = summary.getRevision();
                    boolean approved = summary.getDirectGestureApprovalAllowed();
                  }
                }

                final class FishingConsumer implements FishingMotionFrameSink {
                  public void onFishingMotionFrame(FishingMotionFrame frame) {
                    int count = frame.getPoseCount();
                    int signalCount = frame.getSamples().size();
                    long captureTime = frame.getSourceTimestampNs();
                  }
                }
                """.trimIndent(),
            )
        assertTrue(
            "public semantic API stopped compiling: ${semanticConsumer.errors}",
            semanticConsumer.success,
        )
    }

    private fun rawTypes(): List<Class<*>> =
        listOf(
            LivePoseLandmark::class.java,
            LivePoseObservation::class.java,
            LivePoseObservationFrame::class.java,
            LivePoseSessionLandmark::class.java,
            LivePoseSessionResult::class.java,
            FishingPosePoint::class.java,
            FishingPoseSample::class.java,
        )

    private fun compileJava(className: String, source: String): CompilationResult {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val output = Files.createTempDirectory("live-pose-jvm-boundary")
        val sourceDirectory = output.resolve("hostile")
        Files.createDirectories(sourceDirectory)
        val sourceFile = sourceDirectory.resolve("$className.java")
        Files.writeString(sourceFile, source)
        val errors = StringBuilder()
        val result =
            compiler.run(
                null,
                null,
                object : OutputStream() {
                    override fun write(value: Int) {
                        errors.append(value.toChar())
                    }
                },
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                output.toString(),
                sourceFile.toString(),
            )
        Files.walk(output).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        return CompilationResult(success = result == 0, errors = errors.toString())
    }

    private data class CompilationResult(
        val success: Boolean,
        val errors: String,
    )
}
