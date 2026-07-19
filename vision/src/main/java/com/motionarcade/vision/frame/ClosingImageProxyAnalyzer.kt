package com.motionarcade.vision.frame

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

fun interface ImageProxyFrameProcessor {
    /** The processor must neither close nor retain [image]; the analyzer owns its lifetime. */
    fun process(image: ImageProxy, timestamp: AcceptedFrameTimestamp)
}

fun interface TimestampRejectionSink {
    fun record(rejection: FrameTimestampDecision.Rejected)

    companion object {
        val NONE = TimestampRejectionSink { }
    }
}

/** CameraX ownership boundary: every callback closes its ImageProxy exactly once. */
class ClosingImageProxyAnalyzer(
    private val timestampSequencer: MonotonicFrameSequencer,
    private val processor: ImageProxyFrameProcessor,
    private val rejectionSink: TimestampRejectionSink = TimestampRejectionSink.NONE,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        var primaryFailure: Throwable? = null
        try {
            when (val decision = timestampSequencer.accept(image.imageInfo.timestamp)) {
                is FrameTimestampDecision.Accepted -> processor.process(image, decision.frame)
                is FrameTimestampDecision.Rejected -> rejectionSink.record(decision)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                image.close()
            } catch (closeFailure: Throwable) {
                val originalFailure = primaryFailure
                if (originalFailure == null) {
                    throw closeFailure
                }
                originalFailure.addSuppressed(closeFailure)
            }
        }
    }
}
