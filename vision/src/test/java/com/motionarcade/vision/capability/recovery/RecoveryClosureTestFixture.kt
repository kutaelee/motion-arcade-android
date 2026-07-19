package com.motionarcade.vision.capability.recovery

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import android.graphics.PixelFormat
import android.graphics.Rect
import com.motionarcade.core.contract.GameMode
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import com.motionarcade.vision.capability.runtime.FrameAdmission
import com.motionarcade.vision.capability.runtime.FrameReservation
import com.motionarcade.vision.capability.runtime.OpenRuntimeCommand
import com.motionarcade.vision.capability.runtime.FreshPoseRuntimeFactory
import com.motionarcade.vision.capability.runtime.NativeCreateAuthorizer
import com.motionarcade.vision.capability.runtime.PoseRuntime
import com.motionarcade.vision.capability.runtime.PoseRuntimeInputSubmissionPort
import com.motionarcade.vision.capability.runtime.PoseRuntimeSubmitEvidence
import com.motionarcade.vision.capability.runtime.ProbeAttemptContext
import com.motionarcade.vision.capability.runtime.ProbeClock
import com.motionarcade.vision.capability.runtime.ProbeStateMachine
import com.motionarcade.vision.capability.runtime.ResultAdmission
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import com.motionarcade.vision.capability.runtime.RuntimeCloseEvidence
import com.motionarcade.vision.capability.runtime.RuntimeCreateRequest
import com.motionarcade.vision.capability.runtime.RuntimeGeneration
import com.motionarcade.vision.capability.runtime.RuntimeIdentity
import com.motionarcade.vision.capability.runtime.RuntimeOpenExecution
import com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary
import com.motionarcade.vision.capability.runtime.RuntimeCallbackPort
import com.motionarcade.vision.capability.runtime.RuntimeResultCallback
import com.motionarcade.vision.capability.runtime.RuntimeRoute
import com.motionarcade.vision.capability.runtime.RuntimeRouteKey
import com.motionarcade.vision.capability.runtime.RuntimeRouteKind
import com.motionarcade.vision.capability.runtime.RuntimeSubmitAdmission
import com.motionarcade.vision.capability.runtime.RuntimeTestDeepFixture
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.IntSupplier
import java.util.function.LongSupplier

/**
 * Downstream state-machine fixture only. Its reflective tokens do not model or verify an Android
 * adapter, durable journal commit, or pre-native options verifier and must not support such claims.
 */
internal object RecoveryClosureTestFixture {
    private val nextRouteIdentity = AtomicLong(10_000L)
    private val nextRuntimeIdentity = AtomicLong(20_000L)
    private val pendingAnalyzerInvocations =
        Collections.synchronizedMap(
            IdentityHashMap<RecoveryCameraAnalyzerSource, PendingAnalyzerInvocation>(),
        )

    private class PendingAnalyzerInvocation(
        val release: CountDownLatch,
        val completed: CountDownLatch,
        val thread: Thread,
    )

    fun cleanAuthority(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        outcome: RouteAttemptOutcome,
    ): RecoveryCleanClosureAuthority =
        requireNotNull(
            harness(
                key = key,
                outcome = outcome,
                runtimeArtifactId = current.recoveryBuildId,
                gameMode = current.mode.toGameMode(),
                committedActive =
                    current.copy(
                        active =
                            requireNotNull(current.active).copy(
                                state = JournalActiveState.ACTIVE,
                            ),
                    ),
            ).completeCleanClosure(),
        )

    fun syntheticPreNativeAuthorityForReducerOnly(
        current: RecoveryJournalPayloadV5,
        key: RecoveryAttemptRouteKey,
        generation: Long = nextRouteIdentity.getAndIncrement(),
    ): RecoveryPreNativeCreateDenialAuthority {
        val issuer = privateIssuer(RecoveryPreNativeCreateDenialBoundary::class.java)
        return construct(
            "com.motionarcade.vision.capability.recovery.RecoveryPreNativeCreateDenialBoundary\$IssuedRecoveryPreNativeCreateDenialAuthority",
            issuer,
            generation,
            current.recoveryBuildId.digest,
            current.mode,
            key,
        ) as RecoveryPreNativeCreateDenialAuthority
    }

    fun harness(
        key: RecoveryAttemptRouteKey,
        outcome: RouteAttemptOutcome,
        currentThermalStatus: AtomicLong = AtomicLong(0L),
        renderPort: RecoveryRenderPlatformPort = cleanRenderPort(),
        cameraPort: RecoveryCameraPipelinePlatformPort = cleanCameraPort(),
        runtimeArtifactId: RuntimeArtifactId = RecoveryJournalTestFixtures.runtime(
            nextRouteIdentity.get().toInt(),
        ),
        gameMode: GameMode = GameMode.SOLO,
        suppliedThermalMonitor: ProcessThermalSafetyMonitor? = null,
        trackRepresentativeResources: Boolean = true,
        committedActive: RecoveryJournalPayloadV5? = null,
        frameSourceFactory: (Long) -> ImageProxy = { imageProxy() },
        frameBufferFactory: (Long) -> ByteBuffer = { representativeBuffer() },
        autoDriveOutcome: Boolean = true,
    ): Harness {
        val thermal = suppliedThermalMonitor ?: thermalMonitor(currentThermalStatus)
        val activePayload =
            committedActive ?: activePayload(key, runtimeArtifactId, gameMode)
        val teardownPayload =
            activePayload.copy(
                active =
                    requireNotNull(activePayload.active).copy(
                        state = JournalActiveState.TEARDOWN_PENDING,
                    ),
            )
        val poisonStorage = MutablePostSealStoragePort(teardownPayload)
        val poisonSink = postSealPoisonSink(poisonStorage)
        val opened =
            openRuntime(
                key,
                runtimeArtifactId,
                gameMode,
                activePayload,
                thermal,
                poisonSink,
            )
        val render = renderOwner(renderPort, opened.routeIdentity)
        val camera = cameraPipelineOwner(cameraPort)
        val session =
            requireNotNull(
                RecoveryCleanClosureBoundary.begin(
                    opened.guardedOpen,
                    render,
                    camera,
                ),
            )
        if (autoDriveOutcome) {
            driveOutcome(
                opened.machine,
                opened.open,
                opened.runtimeIdentity,
                opened.clock,
                outcome,
                session,
                trackRepresentativeResources,
                opened.nativeCallbacks,
                frameSourceFactory,
                frameBufferFactory,
            )
        }
        return Harness(
            key = key,
            machine = opened.machine,
            open = opened.open,
            session = session,
            thermal = thermal,
            render = render,
            camera = camera,
            currentThermalStatus = currentThermalStatus,
            runtimeIdentity = opened.runtimeIdentity,
            clock = opened.clock,
            nativeCallbacks = opened.nativeCallbacks,
            poisonSink = poisonSink,
            poisonStorage = poisonStorage,
            committedTeardown = teardownPayload,
            committedTeardownReceipt =
                durableTeardownReceipt(
                    activePayload,
                    teardownPayload,
                    opened.routeIdentity,
                ),
        )
    }

    fun unboundRuntime(
        key: RecoveryAttemptRouteKey,
        outcome: RouteAttemptOutcome,
        runtimeArtifactId: RuntimeArtifactId = RecoveryJournalTestFixtures.runtime(
            nextRouteIdentity.get().toInt(),
        ),
        gameMode: GameMode = GameMode.SOLO,
    ): UnboundRuntime {
        val thermal = thermalMonitor()
        val active = activePayload(key, runtimeArtifactId, gameMode)
        val poisonSink = postSealPoisonSink()
        val opened = openRuntime(key, runtimeArtifactId, gameMode, active, thermal, poisonSink)
        driveOutcome(
            opened.machine,
            opened.open,
            opened.runtimeIdentity,
            opened.clock,
            outcome,
            nativeCallbacks = opened.nativeCallbacks,
        )
        return UnboundRuntime(
            machine = opened.machine,
            open = opened.open,
            guardedOpen = opened.guardedOpen,
            thermal = thermal,
            poisonSink = poisonSink,
            routeIdentity = opened.routeIdentity,
            committedActive = active,
        )
    }

    data class GuardedOpenAttempt(
        val guardedOpen: RecoveryThermallyGuardedRuntimeOpen?,
        val nativeCreateCalls: Int,
    )

    fun attemptGuardedOpen(
        key: RecoveryAttemptRouteKey,
        thermalMonitor: ProcessThermalSafetyMonitor,
        runtimeArtifactId: RuntimeArtifactId = RecoveryJournalTestFixtures.runtime(
            nextRouteIdentity.get().toInt(),
        ),
        gameMode: GameMode = GameMode.SOLO,
    ): GuardedOpenAttempt {
        val active = activePayload(key, runtimeArtifactId, gameMode)
        val nativeCalls = java.util.concurrent.atomic.AtomicInteger()
        val prepared =
            tryOpenRuntime(
                key,
                runtimeArtifactId,
                gameMode,
                active,
                thermalMonitor,
                postSealPoisonSink(),
            ) { nativeCalls.incrementAndGet() }
        return GuardedOpenAttempt(prepared?.guardedOpen, nativeCalls.get())
    }

    private fun openRuntime(
        key: RecoveryAttemptRouteKey,
        runtimeArtifactId: RuntimeArtifactId,
        gameMode: GameMode,
        committedActive: RecoveryJournalPayloadV5,
        thermalMonitor: ProcessThermalSafetyMonitor,
        poisonSink: RecoveryDurablePostSealPoisonSink,
    ): PreparedRuntime =
        requireNotNull(
            tryOpenRuntime(
                key,
                runtimeArtifactId,
                gameMode,
                committedActive,
                thermalMonitor,
                poisonSink,
            ),
        )

    private fun tryOpenRuntime(
        key: RecoveryAttemptRouteKey,
        runtimeArtifactId: RuntimeArtifactId,
        gameMode: GameMode,
        committedActive: RecoveryJournalPayloadV5,
        thermalMonitor: ProcessThermalSafetyMonitor,
        poisonSink: RecoveryDurablePostSealPoisonSink,
        onNativeCreate: () -> Unit = {},
    ): PreparedRuntime? {
        val routeIdentity = nextRouteIdentity.getAndIncrement()
        val clock = MutableTestClock()
        val runtimeIdentity = RuntimeIdentity(nextRuntimeIdentity.getAndIncrement())
        val runtime = CleanRuntime(runtimeIdentity)
        val routeKey =
            RuntimeRouteKey(
                attemptEpoch = com.motionarcade.vision.capability.runtime.ProbeAttemptEpoch(
                    key.attemptEpoch,
                ),
                routeOrdinal = Math.toIntExact(routeIdentity),
                runtimeGeneration = RuntimeGeneration(routeIdentity),
            )
        val request =
            RuntimeCreateRequest(
                attempt =
                    ProbeAttemptContext(
                        mode = gameMode,
                        attemptEpoch = routeKey.attemptEpoch,
                        runtimeArtifactId = runtimeArtifactId,
                        probeBaseScopeId = key.probeBaseScopeId,
                    ),
                route = RuntimeRoute(routeKey, routeKind(key)),
            )
        val command = OpenRuntimeCommand(request)
        val grant = RuntimeTestDeepFixture.nativeGrant(command, routeKey.routeOrdinal)
        lateinit var nativeCallbacks: RuntimeCallbackPort
        val guardedOpen =
            RecoveryCleanClosureBoundary.openThermallyGuardedRuntime(
                    command = command,
                    authorizationRequest = grant.request,
                    authorizer = NativeCreateAuthorizer { grant.authorization },
                    callbacks = RuntimeTestDeepFixture.noOpCallbacks(),
                    runtimeFactory =
                        FreshPoseRuntimeFactory { _, callbacks, owner ->
                            onNativeCreate()
                            nativeCallbacks = callbacks
                            check(
                                owner.bindInputAware(
                                    runtime,
                                    PoseRuntimeInputSubmissionPort { _, input ->
                                        check(input is MPImage)
                                        PoseRuntimeSubmitEvidence.RETURNED
                                    },
                                ),
                            )
                            runtime
                        },
                    clock = clock,
                    committedActiveReceipt =
                        durableActiveReceipt(committedActive, routeIdentity),
                    thermalMonitor = thermalMonitor,
                    postSealPoisonSink = poisonSink,
                ) ?: return null
        val open = privateField(guardedOpen, "openExecution") as RuntimeOpenExecution
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(open))
        return PreparedRuntime(
            machine = machine,
            open = open,
            routeIdentity = routeIdentity,
            runtimeIdentity = runtimeIdentity,
            clock = clock,
            guardedOpen = guardedOpen,
            nativeCallbacks = nativeCallbacks,
        )
    }

    private data class PreparedRuntime(
        val machine: ProbeStateMachine,
        val open: RuntimeOpenExecution,
        val routeIdentity: Long,
        val runtimeIdentity: RuntimeIdentity,
        val clock: MutableTestClock,
        val guardedOpen: RecoveryThermallyGuardedRuntimeOpen,
        val nativeCallbacks: RuntimeCallbackPort,
    )

    data class UnboundRuntime(
        val machine: ProbeStateMachine,
        val open: RuntimeOpenExecution,
        val guardedOpen: RecoveryThermallyGuardedRuntimeOpen,
        val thermal: ProcessThermalSafetyMonitor,
        val poisonSink: RecoveryDurablePostSealPoisonSink,
        val routeIdentity: Long,
        val committedActive: RecoveryJournalPayloadV5,
    )

    class Harness internal constructor(
        val key: RecoveryAttemptRouteKey,
        val machine: ProbeStateMachine,
        val open: RuntimeOpenExecution,
        val session: RecoveryClosureSession,
        val thermal: ProcessThermalSafetyMonitor,
        val render: RecoveryRenderOwner,
        val camera: RecoveryCameraPipelineOwner,
        val currentThermalStatus: AtomicLong,
        val runtimeIdentity: RuntimeIdentity,
        val clock: MutableTestClock,
        val nativeCallbacks: RuntimeCallbackPort,
        val poisonSink: RecoveryDurablePostSealPoisonSink,
        val poisonStorage: MutablePostSealStoragePort,
        val committedTeardown: RecoveryJournalPayloadV5,
        val committedTeardownReceipt: RecoveryDurableTeardownJournalReceipt,
    ) {
        fun closeExternalOwners(): Boolean {
            return RecoveryCleanClosureBoundary.closeCameraPipeline(session) != null
        }

        fun closeNative(): RecoveryRuntimeCloseAdmission? {
            check(
                RecoveryCleanClosureBoundary.bindCommittedTeardown(
                    session,
                    committedTeardownReceipt,
                ),
            )
            val claim = requireNotNull(RecoveryCleanClosureBoundary.beginRuntimeClose(session))
            val execution =
                requireNotNull(RecoveryCleanClosureBoundary.executeRuntimeClose(session, claim))
            return RecoveryCleanClosureBoundary.admitRuntimeClose(session, claim, execution)
        }

        fun completeCleanClosure(): RecoveryCleanClosureAuthority? {
            check(closeExternalOwners())
            check(
                closeNative() ==
                    com.motionarcade.vision.capability.recovery.RecoveryRuntimeCloseAdmission
                        .SEALED_PENDING_FINALIZATION,
            )
            requireNotNull(RecoveryCleanClosureBoundary.endThermalMeasurement(session))
            requireNotNull(RecoveryCleanClosureBoundary.closeRenderOwner(session))
            return RecoveryCleanClosureBoundary.mintCleanClosureAuthority(session)
        }

        fun drive(
            outcome: RouteAttemptOutcome,
            trackFrameGraph: Boolean = true,
            frameSourceFactory: (Long) -> ImageProxy = { imageProxy() },
            frameBufferFactory: (Long) -> ByteBuffer = { representativeBuffer() },
        ) {
            driveOutcome(
                machine,
                open,
                runtimeIdentity,
                clock,
                outcome,
                session,
                trackFrameGraph,
                nativeCallbacks,
                frameSourceFactory,
                frameBufferFactory,
            )
        }
    }

    fun thermalMonitor(
        currentStatus: AtomicLong = AtomicLong(0L),
        executor: Executor = Executor { command -> command.run() },
        nanoTimeReader: LongSupplier = LongSupplier { System.nanoTime() },
    ): ProcessThermalSafetyMonitor =
        thermalMonitor(IntSupplier { currentStatus.get().toInt() }, executor, nanoTimeReader)

    fun thermalMonitor(
        currentStatusReader: IntSupplier,
        executor: Executor,
        nanoTimeReader: LongSupplier = LongSupplier { System.nanoTime() },
    ): ProcessThermalSafetyMonitor {
        val issuer = privateIssuer(ProcessThermalSafetyBoundary::class.java)
        val monitor = construct(
            "com.motionarcade.vision.capability.recovery.ProcessThermalSafetyBoundary\$IssuedProcessThermalSafetyMonitor",
            issuer,
            nextRouteIdentity.getAndIncrement(),
            currentStatusReader,
            executor,
        ) as ProcessThermalSafetyMonitor
        setPrivateField(monitor, "registrationConfirmed", true)
        setPrivateField(monitor, "nanoTimeReader", nanoTimeReader)
        return monitor
    }

    class MutablePostSealStoragePort(
        initialPayload: RecoveryJournalPayloadV5?,
    ) : RecoveryPostSealPoisonStoragePort {
        private var payload: RecoveryJournalPayloadV5? = initialPayload
        val freshStrictReadCalls = AtomicInteger(0)
        val checkedReplaceCalls = AtomicInteger(0)
        var nextWriteEvidence = RecoveryPostSealPoisonWriteEvidence.CHECKED_REPLACE_FSYNCED
        var mutateAfterSuccessfulReplace: ((RecoveryJournalPayloadV5) -> RecoveryJournalPayloadV5)? =
            null

        @Synchronized
        override fun freshStrictRead(): RecoveryJournalPayloadV5? {
            freshStrictReadCalls.incrementAndGet()
            return payload
        }

        @Synchronized
        override fun checkedReplaceAndFsync(
            expectedCurrentPayload: RecoveryJournalPayloadV5,
            expectedCurrentPayloadSha256: com.motionarcade.vision.capability.domain.Sha256Digest,
            poisonedPayload: RecoveryJournalPayloadV5,
        ): RecoveryPostSealPoisonWriteEvidence {
            checkedReplaceCalls.incrementAndGet()
            if (payload != expectedCurrentPayload ||
                RecoveryClosureTestFixture.payloadDigest(expectedCurrentPayload) !=
                expectedCurrentPayloadSha256 ||
                nextWriteEvidence != RecoveryPostSealPoisonWriteEvidence.CHECKED_REPLACE_FSYNCED
            ) {
                return RecoveryPostSealPoisonWriteEvidence.FAILED_OR_UNCERTAIN
            }
            payload = mutateAfterSuccessfulReplace?.invoke(poisonedPayload) ?: poisonedPayload
            return RecoveryPostSealPoisonWriteEvidence.CHECKED_REPLACE_FSYNCED
        }

        @Synchronized
        fun forceCurrent(value: RecoveryJournalPayloadV5?) {
            payload = value
        }

        @Synchronized
        fun current(): RecoveryJournalPayloadV5? = payload
    }

    fun postSealPoisonSink(
        storagePort: RecoveryPostSealPoisonStoragePort =
            MutablePostSealStoragePort(initialPayload = null),
    ): RecoveryDurablePostSealPoisonSink {
        val issuer = privateIssuer(RecoveryPostSealPoisonPersistenceBoundary::class.java)
        return construct(
            "com.motionarcade.vision.capability.recovery.RecoveryPostSealPoisonPersistenceBoundary\$IssuedRecoveryDurablePostSealPoisonSink",
            issuer,
            nextRouteIdentity.getAndIncrement(),
            storagePort,
        ) as RecoveryDurablePostSealPoisonSink
    }

    fun renderOwner(
        port: RecoveryRenderPlatformPort = cleanRenderPort(),
        generation: Long = nextRouteIdentity.getAndIncrement(),
    ): RecoveryRenderOwner {
        val issuer = privateIssuer(RecoveryRenderOwnerBoundary::class.java)
        return construct(
            "com.motionarcade.vision.capability.recovery.RecoveryRenderOwnerBoundary\$IssuedRecoveryRenderOwner",
            issuer,
            generation,
            port,
        ) as RecoveryRenderOwner
    }

    fun cameraPipelineOwner(
        port: RecoveryCameraPipelinePlatformPort = cleanCameraPort(),
        generation: Long = nextRouteIdentity.getAndIncrement(),
    ): RecoveryCameraPipelineOwner {
        val issuer = privateIssuer(RecoveryCameraPipelineBoundary::class.java)
        return (construct(
            "com.motionarcade.vision.capability.recovery.RecoveryCameraPipelineBoundary\$IssuedRecoveryCameraPipelineOwner",
            issuer,
            generation,
            port,
        ) as RecoveryCameraPipelineOwner).also { owner ->
            setPrivateField(owner, "analyzerInstalled", true)
        }
    }

    fun cleanRenderPort(): RecoveryRenderPlatformPort =
        object : RecoveryRenderPlatformPort {
            override fun removeListener() = Unit

            override fun drainDiscardThroughDeadline() = Unit
        }

    fun cleanCameraPort(): RecoveryCameraPipelinePlatformPort =
        object : RecoveryCameraPipelinePlatformPort {
            private var closed = false

            override fun detachAnalyzerAndUnbindSource() {
                closed = true
            }

            override fun isDetachedAndUnbound(): Boolean = closed
        }

    fun durableActiveReceipt(
        payload: RecoveryJournalPayloadV5,
        seed: Long = nextRouteIdentity.getAndIncrement(),
    ): RecoveryDurableActiveJournalReceipt {
        val issuer = privateIssuer(RecoveryDurableJournalReceiptBoundary::class.java)
        val root = constructIdentity("DurableDirectoryIdentity", seed + 1L, seed + 2L)
        val mode = constructIdentity("DurableDirectoryIdentity", seed + 1L, seed + 3L)
        val file = constructIdentity("DurableFileIdentity", seed + 1L, seed + 4L)
        val (rootPath, journalPath) = receiptPaths(seed)
        return construct(
            "com.motionarcade.vision.capability.recovery.RecoveryDurableJournalReceiptBoundary\$IssuedRecoveryDurableActiveJournalReceipt",
            issuer,
            seed,
            payload,
            payloadDigest(payload),
            rootPath,
            root,
            mode,
            journalPath,
            file,
        ) as RecoveryDurableActiveJournalReceipt
    }

    fun durableTeardownReceipt(
        activePayload: RecoveryJournalPayloadV5,
        teardownPayload: RecoveryJournalPayloadV5,
        seed: Long = nextRouteIdentity.getAndIncrement(),
    ): RecoveryDurableTeardownJournalReceipt {
        val issuer = privateIssuer(RecoveryDurableJournalReceiptBoundary::class.java)
        val root = constructIdentity("DurableDirectoryIdentity", seed + 1L, seed + 2L)
        val mode = constructIdentity("DurableDirectoryIdentity", seed + 1L, seed + 3L)
        val previous = constructIdentity("DurableFileIdentity", seed + 1L, seed + 4L)
        val file = constructIdentity("DurableFileIdentity", seed + 1L, seed + 5L)
        val (rootPath, journalPath) = receiptPaths(seed)
        check(activePayload.active?.state == JournalActiveState.ACTIVE)
        return construct(
            "com.motionarcade.vision.capability.recovery.RecoveryDurableJournalReceiptBoundary\$IssuedRecoveryDurableTeardownJournalReceipt",
            issuer,
            seed + 1L,
            teardownPayload,
            payloadDigest(teardownPayload),
            rootPath,
            root,
            mode,
            journalPath,
            previous,
            file,
        ) as RecoveryDurableTeardownJournalReceipt
    }

    private fun constructIdentity(
        simpleName: String,
        device: Long,
        inode: Long,
    ): Any =
        construct(
            "com.motionarcade.vision.capability.recovery.RecoveryDurableJournalReceiptBoundary\$$simpleName",
            device,
            inode,
        )

    private fun payloadDigest(payload: RecoveryJournalPayloadV5) =
        when (val encoded = RecoveryJournalV5Codec.encodePayload(payload)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(encoded.value)
            is CapabilityDomainResult.Invalid -> error(encoded.violations.toString())
        }

    private fun receiptPaths(seed: Long): Pair<String, String> {
        val root =
            Paths.get(System.getProperty("java.io.tmpdir"), "motion-arcade-no-backup-$seed")
                .toAbsolutePath()
                .normalize()
        return root.toString() to root.resolve("journal.bin").toString()
    }

    private fun activePayload(
        key: RecoveryAttemptRouteKey,
        runtimeArtifactId: RuntimeArtifactId,
        gameMode: GameMode,
    ): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5(
            schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
            recoveryBuildId = runtimeArtifactId,
            mode = gameMode.toRecoveryMode(),
            lastEpoch = key.attemptEpoch,
            active =
                ActiveV5(
                    probeBaseScopeId = key.probeBaseScopeId,
                    attemptEpoch = key.attemptEpoch,
                    delegate = key.delegate,
                    role = key.role,
                    state = JournalActiveState.ACTIVE,
                    retryUsed = false,
                    retryContextId = null,
                ),
            manualRetryContext = null,
            entries = emptyList(),
            pendingStoreCommit = null,
            modeControl = ModeControlV5.None,
        )

    fun imageProxy(
        width: Int = 1,
        height: Int = 1,
        format: Int = PixelFormat.RGBA_8888,
        pixelStride: Int = 4,
        rowStride: Int = Math.multiplyExact(width, 4),
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = width,
        cropBottom: Int = height,
        rotationDegrees: Int = 0,
        planeCount: Int = 1,
        sourceBuffer: ByteBuffer = rgbaSourceBuffer(width, height, rowStride),
        onWidthRead: () -> Unit = {},
        onClose: () -> Unit = {},
    ): ImageProxy {
        val plane =
            Proxy.newProxyInstance(
                ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getBuffer" -> sourceBuffer
                    "getPixelStride" -> pixelStride
                    "getRowStride" -> rowStride
                    "toString" -> "RecoveryClosurePlaneProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as ImageProxy.PlaneProxy
        val imageInfo =
            Proxy.newProxyInstance(
                ImageInfo::class.java.classLoader,
                arrayOf(ImageInfo::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getRotationDegrees" -> rotationDegrees
                    "getTimestamp" -> 1L
                    "toString" -> "RecoveryClosureImageInfo"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as ImageInfo
        val crop =
            Rect().apply {
                left = cropLeft
                top = cropTop
                right = cropRight
                bottom = cropBottom
            }
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "close" -> {
                    onClose()
                    null
                }
                "getWidth" -> {
                    onWidthRead()
                    width
                }
                "getHeight" -> height
                "getFormat" -> format
                "getPlanes" -> Array(planeCount) { plane }
                "getCropRect" -> crop
                "getImageInfo" -> imageInfo
                "toString" -> "RecoveryClosureImageProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        } as ImageProxy
    }

    fun analyzerSource(
        owner: RecoveryCameraPipelineOwner,
        sourceProxy: ImageProxy,
    ): RecoveryCameraAnalyzerSource? {
        val captured = AtomicReference<RecoveryCameraAnalyzerSource?>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val sink =
            RecoveryCameraAnalyzerSourceSink { source ->
                captured.set(source)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) {
                    "test analyzer source was not released"
                }
            }
        val analyzerClass =
            Class.forName(
                "com.motionarcade.vision.capability.recovery." +
                    "RecoveryCameraPipelineBoundary\$OwnedRecoveryCameraAnalyzer",
            )
        val constructor =
            analyzerClass.declaredConstructors.single().also { it.isAccessible = true }
        val analyzer = constructor.newInstance(owner, sink) as ImageAnalysis.Analyzer
        val thread =
            Thread(
                {
                    try {
                        analyzer.analyze(sourceProxy)
                    } finally {
                        completed.countDown()
                    }
                },
                "recovery-test-owned-camera-analyzer",
            ).apply { isDaemon = true }
        thread.start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (entered.await(10, TimeUnit.MILLISECONDS)) {
                val source = requireNotNull(captured.get())
                pendingAnalyzerInvocations[source] =
                    PendingAnalyzerInvocation(release, completed, thread)
                return source
            }
            if (completed.count == 0L) {
                thread.join(2_000L)
                check(!thread.isAlive)
                return null
            }
        }
        release.countDown()
        thread.interrupt()
        thread.join(2_000L)
        error("owned analyzer did not enter or complete before the test deadline")
    }

    fun finishAnalyzerSource(source: RecoveryCameraAnalyzerSource) {
        val invocation =
            requireNotNull(pendingAnalyzerInvocations.remove(source)) {
                "source has no pending exact analyzer invocation"
            }
        invocation.release.countDown()
        check(invocation.completed.await(2, TimeUnit.SECONDS)) {
            "owned analyzer did not finish before the test deadline"
        }
        invocation.thread.join(2_000L)
        check(!invocation.thread.isAlive)
    }

    private fun rgbaSourceBuffer(
        width: Int,
        height: Int,
        rowStride: Int,
    ): ByteBuffer {
        val capacity = if (width > 0 && height > 0 && rowStride > 0) {
            Math.multiplyExact(rowStride, height)
        } else {
            0
        }
        return ByteBuffer.allocateDirect(capacity).apply {
            var value = 1
            while (hasRemaining()) {
                put((value and 0xff).toByte())
                value += 1
            }
            clear()
        }
    }

    fun mpImage(
        width: Int = 1,
        height: Int = 1,
    ): MPImage =
        ByteBufferImageBuilder(
            ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4)),
            width,
            height,
            MPImage.IMAGE_FORMAT_RGBA,
        ).build()

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }

    private fun driveOutcome(
        machine: ProbeStateMachine,
        open: RuntimeOpenExecution,
        runtimeIdentity: RuntimeIdentity,
        clock: MutableTestClock,
        outcome: RouteAttemptOutcome,
        session: RecoveryClosureSession? = null,
        trackFrameGraph: Boolean = false,
        nativeCallbacks: RuntimeCallbackPort? = null,
        frameSourceFactory: (Long) -> ImageProxy = { imageProxy() },
        frameBufferFactory: (Long) -> ByteBuffer = { representativeBuffer() },
    ) {
        when (outcome) {
            RouteAttemptOutcome.MEASURED ->
                driveMeasured(
                    machine,
                    open,
                    runtimeIdentity,
                    clock,
                    session,
                    trackFrameGraph,
                    nativeCallbacks,
                    frameSourceFactory,
                    frameBufferFactory,
                )
            RouteAttemptOutcome.CLEAN_TERMINAL ->
                driveResultDeadline(
                    machine,
                    open,
                    clock,
                    session,
                    trackFrameGraph,
                    nativeCallbacks,
                    frameSourceFactory,
                    frameBufferFactory,
                )
            RouteAttemptOutcome.INCOMPLETE -> machine.abortIncomplete()
            RouteAttemptOutcome.RESOURCE_UNCERTAIN -> error("clean authority cannot be uncertain")
        }
        check(machine.pendingCloseOutcome() == outcome)
    }

    private fun driveMeasured(
        machine: ProbeStateMachine,
        open: RuntimeOpenExecution,
        runtimeIdentity: RuntimeIdentity,
        clock: MutableTestClock,
        session: RecoveryClosureSession?,
        trackFrameGraph: Boolean,
        nativeCallbacks: RuntimeCallbackPort?,
        frameSourceFactory: (Long) -> ImageProxy,
        frameBufferFactory: (Long) -> ByteBuffer,
    ) {
        check(machine.startWarmup())
        for (index in 0L until ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS.toLong()) {
            completeFrame(
                machine,
                open,
                runtimeIdentity,
                index,
                session,
                trackFrameGraph,
                nativeCallbacks,
                frameSourceFactory,
                frameBufferFactory,
            )
        }
        completeFrame(
            machine,
            open,
            runtimeIdentity,
            50L,
            session,
            trackFrameGraph,
            nativeCallbacks,
            frameSourceFactory,
            frameBufferFactory,
        )
        val end = requireNotNull(machine.measurementEndNs)
        clock.nowNs = end
        check(machine.reserveFrame(51L, source(51L)) is FrameAdmission.OutsideMeasurementWindow)
        clock.nowNs = end + ProbeStateMachine.MEASUREMENT_DRAIN_NS + 1L
        machine.runWatchdog()
    }

    private fun driveResultDeadline(
        machine: ProbeStateMachine,
        open: RuntimeOpenExecution,
        clock: MutableTestClock,
        session: RecoveryClosureSession?,
        trackFrameGraph: Boolean,
        nativeCallbacks: RuntimeCallbackPort?,
        frameSourceFactory: (Long) -> ImageProxy,
        frameBufferFactory: (Long) -> ByteBuffer,
    ) {
        check(machine.startWarmup())
        val frame =
            if (trackFrameGraph) {
                prepareFrame(
                    requireNotNull(session),
                    0L,
                    frameSourceFactory,
                    frameBufferFactory,
                )
            } else {
                null
            }
        val reservation = reserve(machine, 0L)
        val authorization = requireNotNull(machine.startSubmission(reservation.token))
        val execution =
            requireNotNull(
                if (frame != null) {
                    RecoveryCleanClosureBoundary.submitFrame(
                        requireNotNull(session),
                        frame,
                        authorization,
                    )
                } else {
                    RuntimeOwnerBoundary.submitWithInput(
                        machine,
                        authorization,
                        open,
                        mpImage(),
                    )
                },
            )
        check(machine.admitSubmitExecution(execution) is RuntimeSubmitAdmission.Returned)
        if (frame != null) {
            check(
                RecoveryCleanClosureBoundary.completeReturnedSubmission(
                    requireNotNull(session),
                    frame,
                ),
            )
        }
        clock.nowNs = ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS + 1L
        machine.runWatchdog()
        if (frame != null) {
            check(RecoveryCleanClosureBoundary.closeFrameWithoutCallback(requireNotNull(session), frame))
        }
    }

    private fun completeFrame(
        machine: ProbeStateMachine,
        open: RuntimeOpenExecution,
        runtimeIdentity: RuntimeIdentity,
        frameId: Long,
        session: RecoveryClosureSession?,
        trackFrameGraph: Boolean,
        nativeCallbacks: RuntimeCallbackPort?,
        frameSourceFactory: (Long) -> ImageProxy,
        frameBufferFactory: (Long) -> ByteBuffer,
    ) {
        val frame =
            if (trackFrameGraph) {
                prepareFrame(
                    requireNotNull(session),
                    frameId,
                    frameSourceFactory,
                    frameBufferFactory,
                )
            } else {
                null
            }
        val reservation = reserve(machine, frameId)
        val authorization = requireNotNull(machine.startSubmission(reservation.token))
        val execution =
            requireNotNull(
                if (frame != null) {
                    RecoveryCleanClosureBoundary.submitFrame(
                        requireNotNull(session),
                        frame,
                        authorization,
                    )
                } else {
                    RuntimeOwnerBoundary.submitWithInput(
                        machine,
                        authorization,
                        open,
                        mpImage(),
                    )
                },
            )
        check(machine.admitSubmitExecution(execution) is RuntimeSubmitAdmission.Returned)
        if (frame != null) {
            check(
                RecoveryCleanClosureBoundary.completeReturnedSubmission(
                    requireNotNull(session),
                    frame,
                ),
            )
        }
        val callback =
            RuntimeResultCallback(
                runtimeIdentity = runtimeIdentity,
                taskTimestampMs = reservation.submission.taskTimestampMs.value,
                poseCount = 1,
            )
        val callbackOutput = if (frame != null) mpImage() else null
        if (callbackOutput != null) {
            requireNotNull(nativeCallbacks).onResultWithOutput(callback, callbackOutput)
        }
        val result = machine.admitResult(callback)
        check(result is ResultAdmission.Reserved)
        if (frame != null) {
            check(
                RecoveryCleanClosureBoundary.completeCallbackOutput(
                    requireNotNull(session),
                    frame,
                    requireNotNull(result.cleanup).token,
                    callback,
                    requireNotNull(callbackOutput),
                ) is com.motionarcade.vision.capability.runtime.CallbackResolution.Completed,
            )
        } else {
            check(
                machine.completeCallbackOutput(
                    requireNotNull(result.cleanup).token,
                    com.motionarcade.vision.capability.runtime.CallbackOutputCleanupEvidence.CLOSED,
                ) is com.motionarcade.vision.capability.runtime.CallbackResolution.Completed,
            )
        }
        if (frame != null) {
            check(RecoveryCleanClosureBoundary.closeCompletedFrame(requireNotNull(session), frame))
        }
    }

    private fun prepareFrame(
        session: RecoveryClosureSession,
        frameId: Long,
        frameSourceFactory: (Long) -> ImageProxy,
        @Suppress("UNUSED_PARAMETER")
        frameBufferFactory: (Long) -> ByteBuffer,
    ): RecoveryCameraFrameLease {
        val camera =
            privateField(session, "cameraPipelineOwner") as RecoveryCameraPipelineOwner
        val source = requireNotNull(analyzerSource(camera, frameSourceFactory(frameId)))
        val frame =
            try {
                requireNotNull(RecoveryCleanClosureBoundary.ownFrame(session, source))
            } finally {
                finishAnalyzerSource(source)
            }
        requireNotNull(RecoveryCleanClosureBoundary.buildSubmittedInput(session, frame))
        return frame
    }

    private fun representativeBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(4).apply {
            while (hasRemaining()) put(0x5a.toByte())
            clear()
        }

    private fun reserve(
        machine: ProbeStateMachine,
        frameId: Long,
    ): FrameReservation {
        val admission = machine.reserveFrame(frameId, source(frameId))
        check(admission is FrameAdmission.Reserved)
        return admission.reservation
    }

    private fun source(value: Long): SourceTimestampNs =
        when (val result = SourceTimestampNs.from(value)) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error(result.violations.toString())
        }

    private fun routeKind(key: RecoveryAttemptRouteKey): RuntimeRouteKind =
        when (key.role) {
            JournalRouteRole.CANDIDATE ->
                if (key.delegate == com.motionarcade.vision.capability.domain.ProbeDelegate.CPU) {
                    RuntimeRouteKind.CPU_CANDIDATE
                } else {
                    RuntimeRouteKind.GPU_CANDIDATE
                }
            JournalRouteRole.SELECTED ->
                if (key.delegate == com.motionarcade.vision.capability.domain.ProbeDelegate.CPU) {
                    RuntimeRouteKind.SELECTED_CPU
                } else {
                    RuntimeRouteKind.SELECTED_GPU
                }
            JournalRouteRole.FALLBACK_CANDIDATE -> RuntimeRouteKind.FALLBACK_CPU_CANDIDATE
            JournalRouteRole.FALLBACK_SELECTED -> RuntimeRouteKind.FALLBACK_CPU_SELECTED
        }

    private fun privateIssuer(owner: Class<*>): Any {
        val field = owner.getDeclaredField("issuerIdentity")
        field.isAccessible = true
        return requireNotNull(field.get(null))
    }

    private fun privateField(owner: Any, name: String): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(owner)
    }

    private fun setPrivateField(owner: Any, name: String, value: Any?) {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(owner, value)
    }

    private fun construct(className: String, vararg arguments: Any): Any {
        val type = Class.forName(className)
        val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
        constructor.isAccessible = true
        return constructor.newInstance(*arguments)
    }

    internal class MutableTestClock(var nowNs: Long = 0L) : ProbeClock {
        override fun nowNs(): Long = nowNs
    }

    private class CleanRuntime(override val identity: RuntimeIdentity) : PoseRuntime() {
        override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
    }

    private fun RecoveryJournalMode.toGameMode(): GameMode =
        when (this) {
            RecoveryJournalMode.SOLO -> GameMode.SOLO
            RecoveryJournalMode.DUAL -> GameMode.DUAL
        }

    private fun GameMode.toRecoveryMode(): RecoveryJournalMode =
        when (this) {
            GameMode.SOLO -> RecoveryJournalMode.SOLO
            GameMode.DUAL -> RecoveryJournalMode.DUAL
        }
}
