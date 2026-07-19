package com.motionarcade.vision.capability.domain

import java.lang.reflect.Modifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCloseInstalledImagesTest {
    @Test
    fun actualParserToBuilderPathMatchesIndependentPythonGoldenHashes() {
        val result = build(
            "base" to NativeCloseApkTestFixture.apk(
                member("classes.dex", "dex-one"),
                member("lib/x86_64/liba.so", "jni-a"),
            ),
            "feature_b" to NativeCloseApkTestFixture.apk(
                member("classes.dex", "dex-two"),
                member("lib/x86_64/libb.so", "jni-b"),
            ),
        )

        assertEquals(
            "2b6f516babf03693e0799370ccec52678f64c941ff2edb751f469d0cfe89e3db",
            result.codeImage.sha256.toLowerHex(),
        )
        assertEquals(1, result.jniSets.size)
        assertEquals(NativeCloseAbi.X86_64, result.jniSets.single().abi)
        assertEquals(
            "e8ff24d70f61c805b69ff75c089d38dea6d961221a8eb76d7c943160d8ca6be2",
            result.jniSets.single().sha256.toLowerHex(),
        )
        assertEquals(listOf("base", "feature_b"), result.runtimeArtifactEntries.map { it.logicalName })
    }

    @Test
    fun canonicalOrderIsPhysicalOrderIndependentAndByteAccessIsDefensive() {
        val first = build(
            "base" to NativeCloseApkTestFixture.apk(
                member("lib/x86/libb.so", "b"),
                member("classes2.dex", "second"),
                member("classes.dex", "first"),
                member("lib/arm64-v8a/liba.so", "a"),
            ),
        )
        val second = build(
            "base" to NativeCloseApkTestFixture.apk(
                member("classes.dex", "first"),
                member("lib/arm64-v8a/liba.so", "a"),
                member("classes2.dex", "second"),
                member("lib/x86/libb.so", "b"),
            ),
        )

        assertEquals(first.codeImage.sha256, second.codeImage.sha256)
        assertEquals(
            listOf(NativeCloseAbi.ARM64_V8A, NativeCloseAbi.X86),
            first.jniSets.map { it.abi },
        )
        val exposed = first.codeImage.copyCanonicalBytes()
        exposed[0] = (exposed[0].toInt() xor 0xff).toByte()
        assertFalse(exposed.contentEquals(first.codeImage.copyCanonicalBytes()))
    }

    @Test
    fun dexSequenceMissingDexAndMissingJniRemainTypedFailures() {
        val gap = plans(
            "base" to NativeCloseApkTestFixture.apk(
                member("classes.dex", "first"),
                member("classes3.dex", "third"),
                member("lib/x86/liba.so", "jni"),
            ),
        )
        assertFailure(
            NativeCloseInstalledImageTestHarness.build(gap),
            NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID,
        )
        assertFailure(
            NativeCloseInstalledImageTestHarness.build(
                plans(
                    "base" to NativeCloseApkTestFixture.apk(
                        member("lib/x86/liba.so", "jni"),
                    ),
                ),
            ),
            NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID,
        )
        assertFailure(
            NativeCloseInstalledImageTestHarness.build(
                plans(
                    "base" to NativeCloseApkTestFixture.apk(
                        member("classes.dex", "dex"),
                    ),
                ),
            ),
            NativeCloseInstalledImagesFailure.JNI_SET_INVALID,
        )
    }

    @Test
    fun packageSetRequiresBaseFirstAndStrictUniqueNames() {
        assertFailure(
            NativeCloseInstalledImageTestHarness.build(
                plans(
                    "feature" to NativeCloseApkTestFixture.apk(
                        member("classes.dex", "dex"),
                        member("lib/x86/liba.so", "jni"),
                    ),
                ),
            ),
            NativeCloseInstalledImagesFailure.PACKAGE_SET_INVALID,
        )
        assertFailure(
            NativeCloseInstalledImageTestHarness.build(
                plans(
                    "base" to NativeCloseApkTestFixture.apk(
                        member("classes.dex", "dex"),
                        member("lib/x86/liba.so", "jni"),
                    ),
                    "base" to NativeCloseApkTestFixture.apk(member("classes.dex", "other")),
                ),
            ),
            NativeCloseInstalledImagesFailure.PACKAGE_SET_INVALID,
        )
        listOf("", ".", "..", "bad/name", "bad\\name", "bad\u0000name").forEach { name ->
            val result = NativeCloseInstalledImageTestHarness.build(
                listOf(
                    NativeCloseInstalledApkEngine.PackageInput(
                        name,
                        Source(NativeCloseApkTestFixture.apk(member("classes.dex", "dex"))),
                    ),
                ),
            )
            assertNull(result.inspection)
            assertEquals(NativeCloseApkFailure.PACKAGE_OUTPUT_INVALID, result.apkFailure)
        }
    }

    @Test
    fun packageOutputNameByteLimitRejectsBeforeAnySourceCapture() {
        val bytes = NativeCloseApkTestFixture.apk(
            member("classes.dex", "dex"),
            member("lib/x86/liba.so", "jni"),
        )
        listOf(
            "x".repeat(129),
            "\uAC00".repeat(43),
        ).forEach { oversizedName ->
            val source = Source(bytes)
            val result = NativeCloseInstalledImageTestHarness.build(
                listOf(NativeCloseInstalledApkEngine.PackageInput(oversizedName, source)),
            )

            assertNull(result.inspection)
            assertEquals(NativeCloseApkFailure.PACKAGE_OUTPUT_INVALID, result.apkFailure)
            assertEquals(NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID, result.failure)
            assertEquals(0, source.lengthCalls)
            assertEquals(0, source.identityCalls)
            assertEquals(0, source.readCalls)
        }
    }

    @Test
    fun maximumSplitNamesAndJniNamesRejectAtCanonicalPreflight() {
        fun packageName(index: Int): String {
            if (index == 0) return "base"
            val prefix = "feature_${index}_"
            return prefix + "p".repeat(128 - prefix.length)
        }

        fun jniName(packageIndex: Int, slot: Int): String {
            val directory = "lib/x86/"
            val prefix = "lib${packageIndex}_${slot}_"
            val suffix = ".so"
            return directory + prefix +
                "a".repeat(4_096 - directory.length - prefix.length - suffix.length) + suffix
        }

        val inputs = (0 until 128).map { packageIndex ->
            val members = ArrayList<NativeCloseApkTestFixture.Member>(3)
            if (packageIndex == 0) {
                members += member("classes.dex", "dex")
            }
            repeat(2) { slot ->
                members += NativeCloseApkTestFixture.Member(
                    jniName(packageIndex, slot),
                    byteArrayOf((packageIndex + slot).toByte()),
                    METHOD_STORED,
                )
            }
            NativeCloseInstalledApkEngine.PackageInput(
                packageName(packageIndex),
                Source(NativeCloseApkTestFixture.apk(*members.toTypedArray())),
            )
        }

        val result = NativeCloseInstalledImageTestHarness.build(inputs)

        assertFailure(result, NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
        assertNull(result.apkFailure)
        assertTrue(inputs.all { input ->
            val source = input.source as Source
            source.lengthCalls == 2 && source.identityCalls == 2 && source.readCalls == 2
        })
    }

    @Test
    fun oneByteSelectedMutationChangesOnlyItsCanonicalImage() {
        val original = build(
            "base" to NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(1, 2, 3),
                    METHOD_STORED,
                ),
                member("lib/x86/liba.so", "jni"),
            ),
        )
        val changed = build(
            "base" to NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(1, 2, 4),
                    METHOD_STORED,
                ),
                member("lib/x86/liba.so", "jni"),
            ),
        )

        assertFalse(original.codeImage.sha256 == changed.codeImage.sha256)
        assertArrayEquals(
            original.jniSets.single().sha256.copyBytes(),
            changed.jniSets.single().sha256.copyBytes(),
        )
    }

    @Test
    fun parseOnlyInspectionIsDefensiveAndHasNoEvidenceClassOrAuthorityContract() {
        val inspection = build(
            "base" to NativeCloseApkTestFixture.apk(
                member("classes.dex", "dex"),
                member("lib/x86/liba.so", "jni"),
            ),
        )
        assertTrue(inspection.javaClass.name.endsWith("InspectionMaterial"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (inspection.packageOutputs as MutableList<Any?>).add(null)
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.motionarcade.vision.capability.domain.NativeCloseInstalledImageEvidence")
        }
        val boundary = Class.forName(
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledImageNonAuthorityBoundary",
        )
        assertFalse(Modifier.isPublic(boundary.modifiers))
        assertTrue(boundary.declaredMethods.isEmpty())
        assertTrue(boundary.declaredClasses.isEmpty())
    }

    @Test
    fun javapSurfaceExposesOnlyParseInspectionAndNoPlanInstalledImagesOrNativeCreateBridge() {
        assertEquals(
            setOf("inspectPackageSet"),
            NativeCloseInstalledApkEngine::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSet(),
        )
        val engine = javap(NativeCloseInstalledApkEngine::class.java.name)
        assertTrue(engine.contains("InspectionAttemptMaterial inspectPackageSet("))
        listOf("Evidence", "\$Plan", "\$InstalledImages", "authorize", "nativeCreate").forEach { token ->
            assertFalse("unexpected authority token $token in javap:\n$engine", engine.contains(token))
        }

        listOf(
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledImageEvidence",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledApkEntry",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledApkView",
            "com.motionarcade.vision.capability.domain.NativeClosePackageOutput",
            "com.motionarcade.vision.capability.domain.NativeCloseCanonicalCodeImage",
            "com.motionarcade.vision.capability.domain.NativeCloseCanonicalJniSet",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledImages",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledApkParser",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledImagesBuilder",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledApkParser\$InstalledApkEntryImpl",
            "com.motionarcade.vision.capability.domain.NativeCloseInstalledApkParser\$PlanResultImpl",
        ).forEach { oldName ->
            assertThrows(ClassNotFoundException::class.java) { Class.forName(oldName) }
        }
    }

    @Test
    fun samePackageJavaCanOnlyObtainNonAuthorizingInspectionAndCannotNameOldEvidence() {
        val benign = compileJava(
            "NativeCloseInspectionClasspathProbe",
            """
            package com.motionarcade.vision.capability.domain;
            import java.util.List;
            final class NativeCloseInspectionClasspathProbe {
                static NativeCloseInstalledApkEngine.InspectionAttemptMaterial inspect(
                        List<NativeCloseInstalledApkEngine.PackageInput> inputs) {
                    return NativeCloseInstalledApkEngine.INSTANCE.inspectPackageSet(inputs);
                }
            }
            """.trimIndent(),
        )
        assertTrue(benign.messages, benign.success)

        val attacks = listOf(
            "NameRemovedPlan" to
                """
                package com.motionarcade.vision.capability.domain;
                final class NameRemovedPlan {
                    NativeCloseInstalledImageEvidence.Plan forge() { return null; }
                }
                """.trimIndent(),
            "NameRemovedInstalledImages" to
                """
                package com.motionarcade.vision.capability.domain;
                final class NameRemovedInstalledImages {
                    NativeCloseInstalledImageEvidence.InstalledImages forge() { return null; }
                }
                """.trimIndent(),
            "CallRemovedAuthorityBridge" to
                """
                package com.motionarcade.vision.capability.domain;
                final class CallRemovedAuthorityBridge {
                    Object forge(NativeCloseInstalledApkEngine.InspectionAttemptMaterial parsed) {
                        return NativeCloseInstalledImageEvidence.nativeCreate(parsed);
                    }
                }
                """.trimIndent(),
            "CallRemovedPlanFacade" to
                """
                package com.motionarcade.vision.capability.domain;
                final class CallRemovedPlanFacade {
                    Object forge(
                            NativeCloseApkSource source,
                            NativeCloseInspectionObserver observer) {
                        return NativeCloseInstalledApkParser.INSTANCE.plan("base", source, observer);
                    }
                }
                """.trimIndent(),
        )
        attacks.forEach { (name, source) ->
            val result = compileJava(name, source)
            assertFalse("$name unexpectedly compiled: ${result.messages}", result.success)
        }
    }

    private fun build(vararg packages: Pair<String, ByteArray>): NativeCloseInstalledImagesInspection {
        val result = NativeCloseInstalledImageTestHarness.build(plans(*packages))
        assertNull(result.failure)
        return requireNotNull(result.inspection)
    }

    private fun plans(
        vararg packages: Pair<String, ByteArray>,
    ): List<NativeCloseInstalledApkEngine.PackageInput> =
        packages.map { (name, bytes) ->
            NativeCloseInstalledApkEngine.PackageInput(name, Source(bytes))
        }

    private fun member(name: String, value: String): NativeCloseApkTestFixture.Member =
        NativeCloseApkTestFixture.Member(name, value.toByteArray(), METHOD_STORED)

    private fun assertFailure(
        result: NativeCloseInstalledImagesInspectionResult,
        expected: NativeCloseInstalledImagesFailure,
    ) {
        assertNull(result.inspection)
        assertEquals(expected, result.failure)
    }

    private fun javap(className: String): String {
        val executable = Path.of(
            requireNotNull(System.getProperty("java.home")),
            "bin",
            if (System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")) {
                "javap.exe"
            } else {
                "javap"
            },
        )
        val process = ProcessBuilder(
            executable.toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            "-p",
            className,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("javap failed for $className: $output", 0, process.waitFor())
        return output
    }

    private data class CompilationResult(val success: Boolean, val messages: String)

    private fun compileJava(className: String, source: String): CompilationResult {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val output = Files.createTempDirectory("native-close-javac-attack")
        val sourceFile = object : SimpleJavaFileObject(
            URI.create("string:///com/motionarcade/vision/capability/domain/$className.java"),
            JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        return try {
            val fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8,
            )
            val success = fileManager.use {
                compiler.getTask(
                    null,
                    it,
                    diagnostics,
                    listOf(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        output.toString(),
                        "-proc:none",
                    ),
                    null,
                    listOf(sourceFile),
                ).call()
            }
            CompilationResult(
                success,
                diagnostics.diagnostics.joinToString("\n") { it.getMessage(Locale.ROOT) },
            )
        } finally {
            Files.walk(output).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class Source(private val bytes: ByteArray) : NativeCloseApkSource {
        var lengthCalls: Int = 0
            private set
        var identityCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set

        override val length: Long
            get() {
                lengthCalls += 1
                return bytes.size.toLong()
            }

        override fun copyIdentitySnapshot(): ByteArray {
            identityCalls += 1
            return byteArrayOf(1)
        }

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            readCalls += 1
            if (offset == bytes.size.toLong()) return -1
            if (offset < 0L || offset > bytes.size.toLong()) return -1
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
    }

    private companion object {
        const val METHOD_STORED = 0
    }
}
