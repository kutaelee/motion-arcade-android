package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarFile
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeJvmSurfaceTest {
    @Test
    fun authorityContractsAreSealedAndJavaProxyCannotImplementThem() {
        val contracts = listOf(
            NativeCreateProofSnapshot::class.java,
            OneShotNativeCreatePermit::class.java,
            NativeCreateAuthorization::class.java,
            RuntimeCreationOwner::class.java,
            RuntimeOpenExecution::class.java,
            RuntimeMachineBinding::class.java,
            RuntimeSubmitAuthorization::class.java,
            RuntimeSubmitExecution::class.java,
            ProbeCloseClaim::class.java,
            RuntimeCloseExecution::class.java,
            ProbeCloseCompletion::class.java,
            OrphanRuntimeCloseExecution::class.java,
        )

        contracts.forEach { contract ->
            assertTrue("${contract.name} must remain JVM sealed", contract.isSealed)
            assertTrue(requireNotNull(contract.permittedSubclasses).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                Proxy.newProxyInstance(
                    contract.classLoader,
                    arrayOf(contract),
                ) { _, _, _ -> null }
            }
        }
    }

    @Test
    fun ordinaryReflectionCannotAccessAnyAuthorityImplementationConstructor() {
        authorityImplementationNames().forEach { className ->
            val type = Class.forName(className)
            val declaringClassIsPrivate = type.enclosingClass?.let {
                Modifier.isPrivate(type.modifiers)
            } ?: false
            val constructorsArePrivate = type.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            }
            assertTrue(
                "$className must be protected by a private declaring class or private ctor",
                declaringClassIsPrivate || constructorsArePrivate,
            )
            type.declaredConstructors.forEach { constructor ->
                assertFalse("ordinary reflection accessed $constructor", canAccess(constructor))
                assertTrue(constructor.parameterTypes.none {
                    it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                })
                val arguments = constructor.parameterTypes.map(::dummyArgument).toTypedArray()
                try {
                    constructor.newInstance(*arguments)
                    fail("ordinary reflection forged $className")
                } catch (_: IllegalAccessException) {
                    // Expected: tests deliberately do not suppress JVM access checks here.
                }
            }
        }
    }

    @Test
    fun ownerBoundaryHasNoMintOrClaimBridgeAndPoseRuntimeHasNoSubmitBridge() {
        val forbiddenFragments = listOf(
            "access\$",
            "boundTo",
            "issueFor",
            "recorded",
            "claimOpenedRuntime",
            "submitFromOwner",
            "submitToDependency",
            "\$default",
        )
        val boundaryMethods = RuntimeOwnerBoundary::class.java.declaredMethods.map { it.name }
        val poseMethods = PoseRuntime::class.java.declaredMethods.map { it.name }
        forbiddenFragments.forEach { forbidden ->
            assertTrue(boundaryMethods.none { it.contains(forbidden) })
            assertTrue(poseMethods.none { it.contains(forbidden) })
        }
        assertEquals(0, RuntimeOwnerBoundary::class.java.declaredMethods.count {
            it.isSynthetic && (it.name.contains("issue", true) || it.name.contains("mint", true))
        })
        val publicMethods = RuntimeOwnerBoundary::class.java.methods.toList()
        assertTrue(publicMethods.none { method ->
            method.name == "submit" && method.parameterTypes.any {
                it == SubmitRuntimeCommand::class.java
            }
        })
        assertTrue(publicMethods.none { method ->
            method.name == "close" && method.parameterTypes.any {
                it == CloseRuntimeCommand::class.java
            }
        })
        assertTrue(publicMethods.any { method ->
            method.name == "submit" && method.parameterTypes.contentEquals(arrayOf(
                ProbeStateMachine::class.java,
                RuntimeSubmitAuthorization::class.java,
                RuntimeOpenExecution::class.java,
            ))
        })
        assertTrue(publicMethods.any { method ->
            method.name == "close" && method.parameterTypes.contentEquals(arrayOf(
                ProbeCloseClaim::class.java,
                RuntimeOpenExecution::class.java,
            ))
        })
        assertTrue(ProbeStateMachine::class.java.methods.none { method ->
            method.name == "completeClose" && method.parameterTypes.any {
                it == RuntimeCloseResult::class.java
            }
        })
        assertTrue(ProbeRoutePlanner::class.java.methods.none { method ->
            method.parameterTypes.any {
                it == RuntimeCloseResult::class.java || it == RouteCompletion::class.java
            }
        })
        assertTrue(ProbeRoutePlanner::class.java.methods.any { method ->
            method.name == "acceptCloseCompletion" &&
                method.parameterTypes.contentEquals(arrayOf(ProbeCloseCompletion::class.java))
        })
    }

    @Test
    fun directJavaCannotMintNativeGrantImplementEnvelopesOrCallPoseSubmitBridge() {
        val native = compileJava(
            "NativeForge",
            """
                package com.motionarcade.vision.capability.runtime;
                import com.motionarcade.vision.capability.domain.Sha256Digest;
                final class NativeForge {
                  Object forge(RuntimeCreateRequest request, Sha256Digest digest) {
                    return new VerifiedNativeCreateProofSnapshot(
                        request, digest, new Object(), 1L);
                  }
                }
            """.trimIndent(),
        )
        assertFalse(native.success)

        val envelopes = compileJava(
            "EnvelopeForge",
            """
                package com.motionarcade.vision.capability.runtime;
                final class EnvelopeForge implements RuntimeSubmitExecution {
                  public SubmitRuntimeCommand getCommand() { return null; }
                  public RuntimeSubmitResult getResult() { return null; }
                }
            """.trimIndent(),
        )
        assertFalse(envelopes.success)

        val openEnvelope = compileJava(
            "OpenEnvelopeForge",
            """
                package com.motionarcade.vision.capability.runtime;
                final class OpenEnvelopeForge implements RuntimeOpenExecution {
                  public RuntimeOpenResult getResult() { return null; }
                  public boolean getOwnsRuntime() { return true; }
                }
            """.trimIndent(),
        )
        assertFalse(openEnvelope.success)

        val bridge = compileJava(
            "PoseBridgeAttack",
            """
                package com.motionarcade.vision.capability.runtime;
                final class PoseBridgeAttack {
                  PoseRuntimeSubmitEvidence attack(PoseRuntime runtime, RuntimeSubmission submission) {
                    return runtime.submitFromOwner(submission);
                  }
                }
            """.trimIndent(),
        )
        assertFalse(bridge.success)
        val closeEnvelope = compileJava(
            "CloseEnvelopeForge",
            """
                package com.motionarcade.vision.capability.runtime;
                final class CloseEnvelopeForge implements RuntimeCloseExecution {
                  public CloseRuntimeCommand getCommand() { return null; }
                  public RuntimeCloseResult getResult() { return null; }
                }
            """.trimIndent(),
        )
        assertFalse(closeEnvelope.success)
        val machineBinding = compileJava(
            "MachineBindingForge",
            """
                package com.motionarcade.vision.capability.runtime;
                final class MachineBindingForge implements RuntimeMachineBinding {}
            """.trimIndent(),
        )
        assertFalse(machineBinding.success)
        val submitAuthorization = compileJava(
            "SubmitAuthorizationForge",
            """
                package com.motionarcade.vision.capability.runtime;
                final class SubmitAuthorizationForge implements RuntimeSubmitAuthorization {
                  public SubmitRuntimeCommand getCommand() { return null; }
                }
            """.trimIndent(),
        )
        assertFalse(submitAuthorization.success)
        assertTrue(
            (native.messages + envelopes.messages + openEnvelope.messages + bridge.messages +
                closeEnvelope.messages + machineBinding.messages + submitAuthorization.messages)
                .isNotBlank(),
        )
    }

    @Test
    fun failedOrdinarySubmitForgeCannotReleaseSubmissionCloseFence() {
        val routeKey = RuntimeRouteKey(ProbeAttemptEpoch(1uL), 1, RuntimeGeneration(1L))
        val runtime = object : PoseRuntime() {
            override val identity = RuntimeIdentity(7L)
            override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
        }
        val opened = RuntimeTestDeepFixture.openForRoute(routeKey, runtime)
        val machine = RuntimeTestDeepFixture.stateMachine(opened)
        assertTrue(machine.startWarmup())
        val source = SourceTimestampNs.from(1L) as CapabilityDomainResult.Valid
        val frame = machine.reserveFrame(0L, source.value) as FrameAdmission.Reserved
        val authorization = requireNotNull(machine.startSubmission(frame.reservation.token))
        val command = authorization.command
        val result = RuntimeSubmitResult.Returned(command.key)
        val implementation = Class.forName(
            "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary" +
                "\$RecordedRuntimeSubmitExecution",
        )
        val constructors = implementation.declaredConstructors.toList()

        assertTrue(constructors.isNotEmpty())
        constructors.forEach { constructor ->
            assertFalse(canAccess(constructor))
            assertThrows(IllegalAccessException::class.java) {
                constructor.newInstance(
                    *constructor.parameterTypes.map(::dummyArgument).toTypedArray(),
                )
            }
        }
        val bridge = Class.forName(
            "com.motionarcade.vision.capability.runtime.BoundaryRuntimeSubmitExecution",
        )
        val forged = Proxy.newProxyInstance(
            bridge.classLoader,
            arrayOf(bridge),
        ) { _, method, _ ->
            when (method.name) {
                "getCommand" -> command
                "getResult" -> result
                else -> null
            }
        } as RuntimeSubmitExecution
        assertTrue(machine.admitSubmitExecution(forged) is RuntimeSubmitAdmission.Stale)
        assertTrue(machine.submissionExternalCallInFlight())
        machine.abortIncomplete()
        assertTrue(machine.submissionExternalCallInFlight())
        assertNull(machine.pendingCloseOutcome())
    }

    @Test
    fun exactClassCloseClaimExecutionAndCompletionStillRequireIssuerIdentityLedgers() {
        val routeKey = RuntimeRouteKey(ProbeAttemptEpoch(2uL), 1, RuntimeGeneration(1L))
        val runtime = CountingRuntime(RuntimeIdentity(8L))
        val opened = RuntimeTestDeepFixture.openForRoute(routeKey, runtime)
        val machine = RuntimeTestDeepFixture.stateMachine(opened)
        machine.abortIncomplete()
        val claim = requireNotNull(machine.beginClose())
        val claimType = Class.forName(
            "com.motionarcade.vision.capability.runtime.IssuedProbeCloseClaim",
        )
        val claimConstructor = claimType.declaredConstructors.single()
        claimConstructor.isAccessible = true
        val copiedClaim = claimConstructor.newInstance(
            claim.command,
            claim.outcome,
        ) as ProbeCloseClaim

        assertNull(RuntimeOwnerBoundary.close(copiedClaim, opened))
        assertEquals(0, runtime.closeCalls)
        val genuineExecution = requireNotNull(RuntimeOwnerBoundary.close(claim, opened))
        assertEquals(1, runtime.closeCalls)
        val closeBridge = Class.forName(
            "com.motionarcade.vision.capability.runtime.BoundaryRuntimeCloseExecution",
        )
        val proxyExecution = Proxy.newProxyInstance(
            closeBridge.classLoader,
            arrayOf(closeBridge),
        ) { _, method, _ ->
            when (method.name) {
                "getCommand" -> claim.command
                "getResult" -> boundaryCloseClean(claim.command)
                else -> null
            }
        } as RuntimeCloseExecution
        assertNull(machine.completeClose(claim, proxyExecution))
        assertTrue(machine.callbackAdmissionIsOpen())

        val genuineCompletion = requireNotNull(machine.completeClose(claim, genuineExecution))
        val completionType = Class.forName(
            "com.motionarcade.vision.capability.runtime.IssuedProbeCloseCompletion",
        )
        val completionConstructor = completionType.declaredConstructors.single()
        completionConstructor.isAccessible = true
        val copiedCompletion = completionConstructor.newInstance(
            claim,
            genuineExecution,
            ProbeCloseCompletionKind.SEALED,
            claim.outcome,
            null,
            null,
        ) as ProbeCloseCompletion
        assertNull(machine.consumeCloseCompletion(copiedCompletion, claim))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(machine.consumeCloseCompletion(genuineCompletion, claim)).kind,
        )
        assertNull(machine.consumeCloseCompletion(genuineCompletion, claim))
    }

    @Test
    fun privateMachineBindingBridgeProxyCannotConstructAStateMachine() {
        val bindingBridge = Class.forName(
            "com.motionarcade.vision.capability.runtime.BoundaryRuntimeMachineBinding",
        )
        val forged = Proxy.newProxyInstance(
            bindingBridge.classLoader,
            arrayOf(bindingBridge),
        ) { _, _, _ -> null } as RuntimeMachineBinding
        val routeKey = RuntimeRouteKey(ProbeAttemptEpoch(3uL), 1, RuntimeGeneration(1L))

        assertThrows(IllegalArgumentException::class.java) {
            ProbeStateMachine(
                forged,
                routeKey,
                RuntimeIdentity(9L),
                RuntimeRole.CANDIDATE,
                ProbeClock { 0L },
                1L,
            )
        }
    }

    @Test
    fun privateBridgeProxyCannotOpenOrConsumePlannerPendingRoute() {
        val attempt = ProbeAttemptContext(
            GameMode.SOLO,
            ProbeAttemptEpoch(5uL),
            RuntimeArtifactId(digest(1)),
            ProbeBaseScopeId(digest(2)),
        )
        val planner = ProbeRoutePlanner(attempt)
        val command = (planner.start() as RouteTransition.OpenRequested).command
        val forgedResult = boundaryOpenedResult(command.request.route.key, 77L)
        val bridge = Class.forName(
            "com.motionarcade.vision.capability.runtime.BoundaryRuntimeOpenExecution",
        )
        val forged = Proxy.newProxyInstance(
            bridge.classLoader,
            arrayOf(bridge),
        ) { _, method, _ ->
            when (method.name) {
                "getResult" -> forgedResult
                "getOwnsRuntime" -> true
                else -> null
            }
        } as RuntimeOpenExecution

        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.UNTRUSTED_OPEN_EXECUTION),
            planner.acceptOpenExecution(forged),
        )
        val runtime = object : PoseRuntime() {
            override val identity = RuntimeIdentity(77L)
            override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
        }
        assertTrue(planner.acceptOpenExecution(
            RuntimeTestDeepFixture.open(command, runtime),
        ) is RouteTransition.RuntimeOpened)
    }

    @Test
    fun deepFixtureExistsOnlyInUnitTestOutputNotMainDebugArtifact() {
        val mainProtection = requireNotNull(PoseRuntime::class.java.protectionDomain)
        val mainCodeSource = requireNotNull(mainProtection.codeSource)
        val mainLocation = Path.of(mainCodeSource.location.toURI())
        val forbiddenSuffix = "RuntimeTestDeepFixture.class"
        val mainEntries = artifactEntries(mainLocation)

        assertTrue(mainEntries.any { it.endsWith("PoseRuntime.class") })
        assertTrue(mainEntries.none { it.endsWith(forbiddenSuffix) })
        val fixtureProtection = requireNotNull(Class.forName(
            "com.motionarcade.vision.capability.runtime.RuntimeTestDeepFixture",
        ).protectionDomain)
        val fixtureCodeSource = requireNotNull(fixtureProtection.codeSource)
        assertTrue(fixtureCodeSource.location != mainCodeSource.location)
    }

    private fun authorityImplementationNames(): List<String> = listOf(
        "com.motionarcade.vision.capability.runtime.VerifiedNativeCreateProofSnapshot",
        "com.motionarcade.vision.capability.runtime.IssuedOneShotNativeCreatePermit",
        "com.motionarcade.vision.capability.runtime.GrantedNativeCreateAuthorization",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RuntimeCreationOwnerImpl",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$CreationBinding",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$OwnedRuntimeResource",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RuntimeMachineBindingImpl",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$OpenedRuntimeOpenExecution",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$FailedRuntimeOpenExecution",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RecordedRuntimeSubmitExecution",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RecordedRuntimeCloseExecution",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$OrphanCloseAuthorityImpl",
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RecordedOrphanRuntimeCloseExecution",
    )

    private fun dummyArgument(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 1.toByte()
        java.lang.Short.TYPE -> 1.toShort()
        java.lang.Integer.TYPE -> 1
        java.lang.Long.TYPE -> 1L
        java.lang.Float.TYPE -> 1F
        java.lang.Double.TYPE -> 1.0
        java.lang.Character.TYPE -> 'a'
        else -> null
    }

    private fun digest(seed: Int): Sha256Digest =
        (Sha256Digest.fromBytes(ByteArray(Sha256Digest.BYTE_COUNT) { seed.toByte() }) as
            CapabilityDomainResult.Valid).value

    private fun canAccess(constructor: java.lang.reflect.Constructor<*>): Boolean =
        java.lang.reflect.AccessibleObject::class.java
            .getMethod("canAccess", Any::class.java)
            .invoke(constructor, null) as Boolean

    private class CountingRuntime(
        override val identity: RuntimeIdentity,
    ) : PoseRuntime() {
        var closeCalls = 0

        override fun close(): RuntimeCloseEvidence {
            closeCalls += 1
            return RuntimeCloseEvidence.CLEAN
        }
    }

    private class CompilationResult(
        val success: Boolean,
        val messages: String,
    )

    private fun compileJava(className: String, source: String): CompilationResult {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val output = Files.createTempDirectory("runtime-javac-attack")
        val sourceFile = object : SimpleJavaFileObject(
            URI.create(
                "string:///com/motionarcade/vision/capability/runtime/$className.java",
            ),
            JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        val fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, Charsets.UTF_8)
        val success = try {
            compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf(
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    output.toString(),
                ),
                null,
                listOf(sourceFile),
            ).call()
        } finally {
            fileManager.close()
            Files.walk(output).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        return CompilationResult(
            success,
            diagnostics.diagnostics.joinToString("\n") { it.getMessage(Locale.ROOT) },
        )
    }

    private fun artifactEntries(location: Path): List<String> {
        if (Files.isDirectory(location)) {
            return Files.walk(location).use { paths ->
                paths.filter(Files::isRegularFile)
                    .map { location.relativize(it).toString().replace('\\', '/') }
                    .toList()
            }
        }
        return JarFile(location.toFile()).use { jar ->
            jar.entries().asSequence().map { it.name }.toList()
        }
    }
}
