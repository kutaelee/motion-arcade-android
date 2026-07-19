package com.motionarcade.vision.capability.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCloseInstalledApkViewTest {
    @Test
    fun parserToBuilderBindsSelectedContentAndWholeArtifactToOneSource() {
        val dex = "dex-content".repeat(1_000).toByteArray()
        val jni = ByteArray(150_000) { index -> (index * 31).toByte() }
        val irrelevant = "not-selected".repeat(50).toByteArray()
        val bytes = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", dex, METHOD_DEFLATED),
            NativeCloseApkTestFixture.Member(
                "lib/x86_64/libmediapipe.so",
                jni,
                METHOD_STORED,
            ),
            NativeCloseApkTestFixture.Member("assets/data.bin", irrelevant, METHOD_DEFLATED),
        )
        val source = ByteArraySource(bytes, maximumRead = 997)

        val images = validImages(plan("base", source))
        val view = images.packageOutputs.single().apkView

        assertFalse(view.signingBlockPresent)
        assertEquals(
            listOf("classes.dex", "lib/x86_64/libmediapipe.so", "assets/data.bin"),
            view.entries.map { it.name },
        )
        assertEquals(dex.size.toLong(), view.entries[0].uncompressedLength)
        assertArrayEquals(sha256(dex), requireNotNull(view.entries[0].contentSha256).copyBytes())
        assertArrayEquals(sha256(jni), requireNotNull(view.entries[1].contentSha256).copyBytes())
        assertNull(view.entries[2].contentSha256)
        assertEquals(bytes.size.toULong(), view.runtimeArtifactEntry.apkLength)
        assertArrayEquals(sha256(bytes), view.runtimeArtifactEntry.apkSha256.copyBytes())
        assertEquals(1, source.eofProbeCount)
    }

    @Test
    fun fragmentedReadsJoinAndZeroOrNonEofReadFailsClosed() {
        val bytes = validPackageBytes()
        val fragmented = ByteArraySource(bytes, maximumRead = 3)
        validImages(plan("base", fragmented))

        val zeroRead = ByteArraySource(bytes, maximumRead = 3, zeroAfterOffset = 12L)
        assertPlanFailure(planResult("base", zeroRead), NativeCloseApkFailure.READ_FAILED)

        val falseEof = object : ByteArraySource(bytes) {
            override fun readAt(
                offset: Long,
                destination: ByteArray,
                destinationOffset: Int,
                byteCount: Int,
            ): Int = if (offset == bytes.size.toLong()) 0 else
                super.readAt(offset, destination, destinationOffset, byteCount)
        }
        assertPlanFailure(planResult("base", falseEof), NativeCloseApkFailure.READ_FAILED)
    }

    @Test
    fun exactEocdCountSentinelAndCentralRangeCorpusFailsClosed() {
        val canonical = validPackageBytes()
        val eocd = canonical.size - EOCD_LENGTH
        val cases = listOf(
            byteArrayOf(9) + canonical,
            canonical + byteArrayOf(9),
            canonical.copyOf(canonical.size - 1),
            canonical.copyOf().also { putLe16(it, eocd + 20, 1) },
            canonical.copyOf().also { putLe16(it, eocd + 8, 1) },
            canonical.copyOf().also {
                putLe16(it, eocd + 8, 0xffff)
                putLe16(it, eocd + 10, 0xffff)
            },
            canonical.copyOf().also { putLe32(it, eocd + 12, UINT32_MAX) },
            canonical.copyOf().also { putLe32(it, eocd + 16, UINT32_MAX) },
        )
        cases.forEach { bytes ->
            assertPlanFailure(planResult("base", ByteArraySource(bytes)), NativeCloseApkFailure.EOCD_INVALID)
        }

        val centralOffset = le32(canonical, eocd + 16).toInt()
        val centralSize = le32(canonical, eocd + 12).toInt()
        val shiftedRange = canonical.copyOf().also {
            putLe32(it, eocd + 16, (centralOffset - 1).toLong())
            putLe32(it, eocd + 12, (centralSize + 1).toLong())
        }
        assertPlanFailure(
            planResult("base", ByteArraySource(shiftedRange)),
            NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
        )
        val declaredExtraRecord = canonical.copyOf().also {
            putLe16(it, eocd + 8, 3)
            putLe16(it, eocd + 10, 3)
        }
        assertPlanFailure(
            planResult("base", ByteArraySource(declaredExtraRecord)),
            NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
        )
    }

    @Test
    fun localRangeHoleOverlapOutOfRangeAndMetadataMismatchFailClosed() {
        val hole = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            gapAfterFirst = byteArrayOf(99),
        )
        assertPlanFailure(planResult("base", ByteArraySource(hole)), NativeCloseApkFailure.LOCAL_RECORD_INVALID)

        val canonical = validPackageBytes()
        val centralOffsets = NativeCloseApkTestFixture.centralRecordOffsets(canonical)
        val overlap = canonical.copyOf().also { putLe32(it, centralOffsets[1] + 42, 0L) }
        assertPlanFailure(
            planResult("base", ByteArraySource(overlap)),
            NativeCloseApkFailure.LOCAL_RECORD_INVALID,
        )
        val centralStart = NativeCloseApkTestFixture.centralOffset(canonical)
        val outOfRange = canonical.copyOf().also {
            putLe32(it, centralOffsets[0] + 42, centralStart.toLong())
        }
        assertPlanFailure(
            planResult("base", ByteArraySource(outOfRange)),
            NativeCloseApkFailure.LOCAL_RECORD_INVALID,
        )
        val mismatch = canonical.copyOf().also { putLe32(it, 14, 0x1234_5678L) }
        assertPlanFailure(
            planResult("base", ByteArraySource(mismatch)),
            NativeCloseApkFailure.LOCAL_RECORD_INVALID,
        )
    }

    @Test
    fun exactZipflingerAlignmentPaddingIsAcceptedAndMutationsFailClosed() {
        val first = NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED)
        val second = NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED)
        val padding = NativeCloseApkTestFixture.alignmentPadding(257) +
            NativeCloseApkTestFixture.alignmentPadding(11)
        validImages(
            plan(
                "base",
                ByteArraySource(
                    NativeCloseApkTestFixture.apk(first, second, gapAfterFirst = padding),
                ),
            ),
        )

        val mutations = listOf(
            padding.copyOf().also { putLe16(it, 4, 1) },
            padding.copyOf().also { putLe16(it, 10, 0) },
            padding.copyOf().also { putLe16(it, 26, 1) },
            padding.copyOf().also { putLe32(it, 18, 1) },
            padding.copyOf().also { it[30] = 1 },
        )
        mutations.forEach { mutated ->
            assertPlanFailure(
                planResult(
                    "base",
                    ByteArraySource(
                        NativeCloseApkTestFixture.apk(first, second, gapAfterFirst = mutated),
                    ),
                ),
                NativeCloseApkFailure.LOCAL_RECORD_INVALID,
            )
        }
    }

    @Test
    fun canonicalNamesDosUnixDirectoriesAndLocalPaddingAreClosed() {
        val duplicate = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(2), METHOD_STORED),
        )
        assertPlanFailure(planResult("base", ByteArraySource(duplicate)), NativeCloseApkFailure.DUPLICATE_ENTRY)
        listOf("../classes.dex", "folder/", "folder//x", "a\\b", "lib//liba.so").forEach { name ->
            val reason = if (name.startsWith("lib/")) {
                NativeCloseApkFailure.ENTRY_NAME_INVALID
            } else {
                NativeCloseApkFailure.ENTRY_NAME_INVALID
            }
            assertPlanFailure(
                planResult(
                    "base",
                    ByteArraySource(
                        NativeCloseApkTestFixture.apk(
                            NativeCloseApkTestFixture.Member(name, byteArrayOf(1), METHOD_STORED),
                        ),
                    ),
                ),
                reason,
            )
        }

        val canonical = validPackageBytes()
        val central = NativeCloseApkTestFixture.centralRecordOffsets(canonical).first()
        val dosDirectory = canonical.copyOf().also { putLe32(it, central + 38, 0x10L) }
        assertPlanFailure(
            planResult("base", ByteArraySource(dosDirectory)),
            NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
        )
        val unixDirectory = canonical.copyOf().also {
            it[central + 5] = 3
            putLe32(it, central + 38, 0x4000_0000L)
        }
        assertPlanFailure(
            planResult("base", ByteArraySource(unixDirectory)),
            NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
        )
        val nonzeroPadding = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            localExtra = byteArrayOf(1),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(nonzeroPadding)),
            NativeCloseApkFailure.LOCAL_RECORD_INVALID,
        )
    }

    @Test
    fun malformedLibUnexpectedAbiAndSelectedPreinflateLimitsFailBeforePayloadRead() {
        listOf(
            "lib/riscv64/liba.so" to NativeCloseApkFailure.JNI_SET_INVALID,
            "lib/x86/not-a-library" to NativeCloseApkFailure.JNI_SET_INVALID,
            "lib/x86/sub/liba.so" to NativeCloseApkFailure.JNI_SET_INVALID,
            "lib//liba.so" to NativeCloseApkFailure.ENTRY_NAME_INVALID,
        ).forEach { (name, expected) ->
            assertPlanFailure(
                planResult(
                    "base",
                    ByteArraySource(
                        NativeCloseApkTestFixture.apk(
                            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
                            NativeCloseApkTestFixture.Member(name, byteArrayOf(2), METHOD_STORED),
                        ),
                    ),
                ),
                expected,
            )
        }

        val oversizedDex = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member(
                "classes.dex",
                byteArrayOf(1),
                METHOD_DEFLATED,
                declaredUncompressedSize = MAX_ENTRY_BYTES + 1,
            ),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(oversizedDex)),
            NativeCloseApkFailure.DEX_IMAGE_INVALID,
        )

        val tooManyDex = (1..65).map { ordinal ->
            val name = if (ordinal == 1) "classes.dex" else "classes$ordinal.dex"
            NativeCloseApkTestFixture.Member(name, byteArrayOf(ordinal.toByte()), METHOD_STORED)
        } + NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(1), METHOD_STORED)
        assertPlanFailure(
            planResult("base", ByteArraySource(NativeCloseApkTestFixture.apk(*tooManyDex.toTypedArray()))),
            NativeCloseApkFailure.DEX_IMAGE_INVALID,
        )

        val tooManyJni = listOf(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
        ) + (0..256).map { index ->
            NativeCloseApkTestFixture.Member("lib/x86/lib$index.so", byteArrayOf(1), METHOD_STORED)
        }
        assertPlanFailure(
            planResult("base", ByteArraySource(NativeCloseApkTestFixture.apk(*tooManyJni.toTypedArray()))),
            NativeCloseApkFailure.JNI_SET_INVALID,
        )

        val dexTotal = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member(
                "classes.dex",
                byteArrayOf(1),
                METHOD_DEFLATED,
                declaredUncompressedSize = MAX_ENTRY_BYTES,
            ),
            NativeCloseApkTestFixture.Member(
                "classes2.dex",
                byteArrayOf(2),
                METHOD_DEFLATED,
                declaredUncompressedSize = MAX_ENTRY_BYTES,
            ),
            NativeCloseApkTestFixture.Member(
                "classes3.dex",
                byteArrayOf(3),
                METHOD_DEFLATED,
                declaredUncompressedSize = 1,
            ),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(1), METHOD_STORED),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(dexTotal)),
            NativeCloseApkFailure.DEX_IMAGE_INVALID,
        )

        val jniTotal = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member(
                "lib/x86/liba.so",
                byteArrayOf(1),
                METHOD_DEFLATED,
                declaredUncompressedSize = MAX_ENTRY_BYTES,
            ),
            NativeCloseApkTestFixture.Member(
                "lib/x86/libb.so",
                byteArrayOf(2),
                METHOD_DEFLATED,
                declaredUncompressedSize = MAX_ENTRY_BYTES,
            ),
            NativeCloseApkTestFixture.Member(
                "lib/x86/libc.so",
                byteArrayOf(3),
                METHOD_DEFLATED,
                declaredUncompressedSize = 1,
            ),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(jniTotal)),
            NativeCloseApkFailure.JNI_SET_INVALID,
        )
    }

    @Test
    fun retainedCentralNameBytesAreBoundedBeforeLocalPayloadWork() {
        val longNames = (0 until 65).map { index ->
            NativeCloseApkTestFixture.Member(
                "n$index-" + "a".repeat(64_995),
                byteArrayOf(1),
                METHOD_STORED,
            )
        }
        val bytes = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            *longNames.toTypedArray(),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(bytes)),
            NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
        )
    }

    @Test
    fun crossSplitCollisionAndGlobalDexLimitRejectBeforeCurrentPayloadMaterialization() {
        val baseSource = ByteArraySource(validPackageBytes("lib/x86/libsame.so"))
        val featureSource = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(3),
                    METHOD_STORED,
                    crcOverride = 0L,
                ),
                NativeCloseApkTestFixture.Member("lib/x86/libsame.so", byteArrayOf(4), METHOD_STORED),
            ),
        )
        val basePlan = plan("base", baseSource)
        val featurePlan = plan("feature", featureSource)
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(listOf(basePlan, featurePlan)),
            NativeCloseInstalledImagesFailure.JNI_SET_INVALID,
        )
        assertEquals(1, baseSource.eofProbeCount)
        assertEquals(1, featureSource.eofProbeCount)

        val sources = (1..65).map { ordinal ->
            val dexName = "classes.dex"
            val members = mutableListOf(
                NativeCloseApkTestFixture.Member(
                    dexName,
                    byteArrayOf(ordinal.toByte()),
                    METHOD_STORED,
                    crcOverride = if (ordinal == 65) 0L else null,
                ),
            )
            if (ordinal == 1) {
                members += NativeCloseApkTestFixture.Member(
                    "lib/x86/libonly.so",
                    byteArrayOf(1),
                    METHOD_STORED,
                )
            }
            ByteArraySource(NativeCloseApkTestFixture.apk(*members.toTypedArray()))
        }
        val plans = sources.mapIndexed { index, source ->
            plan(if (index == 0) "base" else "feature_$index", source)
        }
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(plans),
            NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID,
        )
        assertTrue(sources.all { it.eofProbeCount == 1 })

        val largeBase = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(1),
                    METHOD_DEFLATED,
                    declaredUncompressedSize = MAX_ENTRY_BYTES,
                ),
                NativeCloseApkTestFixture.Member("lib/x86/libonly.so", byteArrayOf(1), METHOD_STORED),
            ),
        )
        val largeFeature = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(2),
                    METHOD_DEFLATED,
                    declaredUncompressedSize = MAX_ENTRY_BYTES,
                ),
            ),
        )
        val tailFeature = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(3),
                    METHOD_STORED,
                    crcOverride = 0L,
                ),
            ),
        )
        val largePlans = listOf(
            plan("base", largeBase),
            plan("large_feature", largeFeature),
            plan("tail_feature", tailFeature),
        )
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(largePlans),
            NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID,
        )
        assertEquals(1, tailFeature.eofProbeCount)
    }

    @Test
    fun globalRetainedNameBudgetRejectsAcrossPackagesBeforeAnyBuilderRead() {
        fun retainedNames(prefix: String): Array<NativeCloseApkTestFixture.Member> =
            Array(33) { index ->
                NativeCloseApkTestFixture.Member(
                    "${prefix}_$index-" + "a".repeat(63_990),
                    byteArrayOf(1),
                    METHOD_STORED,
                )
            }

        val baseSource = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
                NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
                *retainedNames("base"),
            ),
        )
        val featureSource = ByteArraySource(
            NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    byteArrayOf(3),
                    METHOD_STORED,
                    crcOverride = 0L,
                ),
                *retainedNames("feature"),
            ),
        )
        val plans = listOf(plan("base", baseSource), plan("feature", featureSource))

        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(plans),
            NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED,
        )
        assertEquals(1, baseSource.eofProbeCount)
        assertEquals(1, featureSource.eofProbeCount)
    }

    @Test
    fun maximumPackageSetTimesMaximumZipCountRejectsBeforeSecondMetadataAllocation() {
        val bytes = NativeCloseApkTestFixture.apk(
            *Array(65_534) { index ->
                NativeCloseApkTestFixture.Member("asset_$index", byteArrayOf(), METHOD_STORED)
            },
        )
        val sources = List(129) { ByteArraySource(bytes) }
        val inputs = sources.mapIndexed { index, source ->
            val name = if (index == 0) "base" else "feature_$index"
            plan(name, source)
        }

        val result = NativeCloseInstalledImageTestHarness.build(inputs)

        assertNull(result.inspection)
        assertEquals(NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED, result.failure)
        assertEquals(1, sources[0].eofProbeCount)
        assertEquals(1, sources[1].eofProbeCount)
        assertTrue(sources.drop(2).all { it.readCallCount == 0 })
    }

    @Test
    fun invalidAndTrailingDeflateAndWrongCrcFailAtBuilder() {
        val content = "selected-deflate".repeat(100).toByteArray()
        val compressed = NativeCloseApkTestFixture.deflate(content)
        val invalid = compressed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x7f).toByte() }
        val trailing = compressed + byteArrayOf(0)
        val truncated = compressed.copyOf(compressed.size - 1)
        listOf(invalid, trailing, truncated).forEach { encoded ->
            val bytes = NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member(
                    "classes.dex",
                    content,
                    METHOD_DEFLATED,
                    compressedOverride = encoded,
                ),
                NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(1), METHOD_STORED),
            )
            assertImagesFailure(
                NativeCloseInstalledImageTestHarness.build(listOf(plan("base", ByteArraySource(bytes)))),
                NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID,
            )
        }

        val wrongCrc = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member(
                "classes.dex",
                content,
                METHOD_STORED,
                crcOverride = 0L,
            ),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(1), METHOD_STORED),
        )
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(listOf(plan("base", ByteArraySource(wrongCrc)))),
            NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID,
        )
    }

    @Test
    fun everyInertPayloadRequiresExactBytesSizeCrcAndDeflateEnd() {
        val inertContent = "unselected-inert-payload".repeat(32).toByteArray()
        val canonical = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            NativeCloseApkTestFixture.Member("assets/inert.bin", inertContent, METHOD_STORED),
        )
        val mutatedPayload = canonical.copyOf().also { bytes ->
            val offset = NativeCloseApkTestFixture.localPayloadOffsets(bytes)[2]
            bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
        }
        val payloadMutation = planResult("base", ByteArraySource(mutatedPayload))
        assertPlanFailure(payloadMutation, NativeCloseApkFailure.PAYLOAD_INVALID)
        assertEquals(
            NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID,
            payloadMutation.failure,
        )

        val wrongCrc = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            NativeCloseApkTestFixture.Member(
                "assets/inert.bin",
                inertContent,
                METHOD_STORED,
                crcOverride = 0L,
            ),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(wrongCrc)),
            NativeCloseApkFailure.PAYLOAD_INVALID,
        )

        val compressed = NativeCloseApkTestFixture.deflate(inertContent)
        val nonExactDeflateCases = listOf(
            NativeCloseApkTestFixture.Member(
                "assets/inert.bin",
                inertContent,
                METHOD_DEFLATED,
                compressedOverride = compressed + byteArrayOf(0),
            ),
            NativeCloseApkTestFixture.Member(
                "assets/inert.bin",
                inertContent,
                METHOD_DEFLATED,
                compressedOverride = compressed.copyOf(compressed.size - 1),
            ),
            NativeCloseApkTestFixture.Member(
                "assets/inert.bin",
                inertContent,
                METHOD_DEFLATED,
                declaredUncompressedSize = inertContent.size.toLong() + 1L,
            ),
        )
        nonExactDeflateCases.forEach { inert ->
            val bytes = NativeCloseApkTestFixture.apk(
                NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
                NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
                inert,
            )
            assertPlanFailure(
                planResult("base", ByteArraySource(bytes)),
                NativeCloseApkFailure.PAYLOAD_INVALID,
            )
        }
    }

    @Test
    fun cancellationIsObservedAcrossMaximumEmptyInertPayloadCorpus() {
        val bytes = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            *Array(65_532) { index ->
                NativeCloseApkTestFixture.Member("asset_$index", byteArrayOf(), METHOD_STORED)
            },
        )
        var observerCalls = 0
        var payloadValidationDetected = false
        var payloadCallsAfterDetection = 0
        val observer = NativeCloseInspectionObserver {
            observerCalls += 1
            val shouldSample = payloadValidationDetected || observerCalls % 1_024 == 0
            val inPayloadValidation = shouldSample && Thread.currentThread().stackTrace.any { frame ->
                frame.methodName.contains("validateAllPayloads")
            }
            if (inPayloadValidation) {
                payloadValidationDetected = true
                payloadCallsAfterDetection += 1
                payloadCallsAfterDetection < 10
            } else {
                true
            }
        }

        val result = NativeCloseInstalledImageTestHarness.build(
            listOf(plan("base", ByteArraySource(bytes), observer)),
        )

        assertPlanFailure(result, NativeCloseApkFailure.INSPECTION_ABORTED)
        assertEquals(NativeCloseInstalledImagesFailure.INSPECTION_ABORTED, result.failure)
        assertTrue(payloadValidationDetected)
        assertEquals(10, payloadCallsAfterDetection)
    }

    @Test
    fun cancellationIsObservedBetweenStoredPayloadChunks() {
        val largeInert = ByteArray(3 * 65_536 + 1) { index -> index.toByte() }
        val bytes = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            NativeCloseApkTestFixture.Member("assets/large.bin", largeInert, METHOD_STORED),
        )
        var payloadValidationCalls = 0
        val observer = NativeCloseInspectionObserver {
            val inPayloadValidation = Thread.currentThread().stackTrace.any { frame ->
                frame.methodName.contains("validateAllPayloads")
            }
            if (inPayloadValidation) {
                payloadValidationCalls += 1
                payloadValidationCalls < 15
            } else {
                true
            }
        }

        val result = NativeCloseInstalledImageTestHarness.build(
            listOf(plan("base", ByteArraySource(bytes), observer)),
        )

        assertPlanFailure(result, NativeCloseApkFailure.INSPECTION_ABORTED)
        assertEquals(NativeCloseInstalledImagesFailure.INSPECTION_ABORTED, result.failure)
        assertEquals(15, payloadValidationCalls)
    }

    @Test
    fun signingBlockZeroUnderOverflowNonexactAndBoundedPairCorpusFailsClosed() {
        val member = NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED)
        val jni = NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED)
        val valid = NativeCloseApkTestFixture.signingBlock(7L to byteArrayOf(1))
        val cases = listOf(
            valid.copyOf().also { it[it.lastIndex] = 0 },
            valid.copyOf().also { putLe64(it, 0, 36uL) },
            NativeCloseApkTestFixture.signingBlock(0L to byteArrayOf(1)),
            valid.copyOf().also { putLe64(it, 8, 3uL) },
            valid.copyOf().also { putLe64(it, 8, ULong.MAX_VALUE) },
            NativeCloseApkTestFixture.signingBlock(
                7L to byteArrayOf(1),
                pairRegionSuffix = byteArrayOf(0),
            ),
            NativeCloseApkTestFixture.signingBlock(
                7L to byteArrayOf(1),
                7L to byteArrayOf(2),
            ),
        )
        cases.forEach { block ->
            val bytes = NativeCloseApkTestFixture.apk(member, jni, signingBlock = block)
            assertPlanFailure(
                planResult("base", ByteArraySource(bytes)),
                NativeCloseApkFailure.SIGNING_BLOCK_INVALID,
            )
        }

        val manyPairs = ArrayList<Pair<Long, ByteArray>>(65_535)
        repeat(65_535) { index -> manyPairs += (index + 1L) to byteArrayOf() }
        val bounded = NativeCloseApkTestFixture.apk(
            member,
            jni,
            signingBlock = NativeCloseApkTestFixture.signingBlock(*manyPairs.toTypedArray()),
        )
        assertPlanFailure(
            planResult("base", ByteArraySource(bounded)),
            NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
        )
    }

    @Test
    fun inertSignaturesInsidePayloadAndSigningValueAreAccepted() {
        val inert = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(EOCD_SIGNATURE)
            .putInt(CENTRAL_SIGNATURE)
            .putInt(LOCAL_SIGNATURE)
            .array()
        val bytes = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", inert, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            signingBlock = NativeCloseApkTestFixture.signingBlock(
                7L to ("APK Sig Block 42".toByteArray() + inert),
            ),
        )
        val images = validImages(plan("base", ByteArraySource(bytes)))
        assertTrue(images.packageOutputs.single().apkView.signingBlockPresent)
    }

    @Test
    fun sourceIdentityChangeRejectsWhileLyingSourceCannotSplitPrivateSnapshot() {
        val a = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1), METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(2), METHOD_STORED),
            NativeCloseApkTestFixture.Member("assets/inert.bin", byteArrayOf(3), METHOD_STORED),
        )
        val b = a.copyOf().also {
            val inertPayload = NativeCloseApkTestFixture.localPayloadOffsets(it)[2]
            it[inertPayload] = 4
        }
        val versioned = SwitchingSource(a, b, identityChanges = true, switchAfterRead = true)
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(listOf(plan("base", versioned))),
            NativeCloseInstalledImagesFailure.SOURCE_CHANGED,
        )

        val lying = SwitchingSource(a, b, identityChanges = false, switchAfterRead = true)
        val inspection = validImages(plan("base", lying))
        assertArrayEquals(
            sha256(a),
            inspection.runtimeArtifactEntries.single().apkSha256.copyBytes(),
        )
    }

    @Test
    fun oversizedIdentityRejectsBeforeDefensiveCopyCanReachSourceReads() {
        val identityCalls = AtomicInteger()
        val readCalls = AtomicInteger()
        val source = object : NativeCloseApkSource {
            override val length: Long
                get() = validPackageBytes().size.toLong()

            override fun copyIdentitySnapshot(): ByteArray {
                identityCalls.incrementAndGet()
                return ByteArray(4_097)
            }

            override fun readAt(
                offset: Long,
                destination: ByteArray,
                destinationOffset: Int,
                byteCount: Int,
            ): Int {
                readCalls.incrementAndGet()
                throw AssertionError("oversized identity must stop before source reads")
            }
        }

        assertPlanFailure(
            planResult("base", source),
            NativeCloseApkFailure.SOURCE_IDENTITY_INVALID,
        )
        assertEquals(1, identityCalls.get())
        assertEquals(0, readCalls.get())
    }

    @Test
    fun sourceLengthRuntimeAssertionAndOomFailuresRemainTypedAndFailClosed() {
        val initialCases = listOf(
            LengthFailureCase(
                RuntimeException("fixture runtime"),
                NativeCloseApkFailure.READ_FAILED,
                NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID,
            ),
            LengthFailureCase(
                AssertionError("fixture assertion"),
                NativeCloseApkFailure.READ_FAILED,
                NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID,
            ),
            LengthFailureCase(
                OutOfMemoryError("fixture oom"),
                NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
                NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED,
            ),
        )

        initialCases.forEach { case ->
            val source = LengthFailureSource(case.throwable)
            val result = planResult("base", source)

            assertNull(result.inspection)
            assertEquals(case.expectedApkFailure, result.apkFailure)
            assertEquals(case.expectedImagesFailure, result.failure)
            assertEquals(1, source.lengthCalls)
            assertEquals(0, source.identityCalls)
            assertEquals(0, source.readCalls)
        }

        val bytes = validPackageBytes()
        val stableMetadataCases = listOf(
            LengthFailureCase(
                RuntimeException("fixture late runtime"),
                NativeCloseApkFailure.SOURCE_CHANGED,
                NativeCloseInstalledImagesFailure.SOURCE_CHANGED,
            ),
            LengthFailureCase(
                AssertionError("fixture late assertion"),
                NativeCloseApkFailure.SOURCE_CHANGED,
                NativeCloseInstalledImagesFailure.SOURCE_CHANGED,
            ),
            LengthFailureCase(
                OutOfMemoryError("fixture late oom"),
                NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
                NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED,
            ),
        )
        stableMetadataCases.forEach { case ->
            val source = LengthFailureSource(case.throwable, bytes, failOnLengthCall = 2)
            val result = planResult("base", source)

            assertNull(result.inspection)
            assertEquals(case.expectedApkFailure, result.apkFailure)
            assertEquals(case.expectedImagesFailure, result.failure)
            assertEquals(2, source.lengthCalls)
            assertEquals(1, source.identityCalls)
            assertEquals(2, source.readCalls)
        }
    }

    @Test
    fun sameCrcPayloadSwapCannotSplitSelectedDigestFromAcceptedWholeApkPass() {
        val payloadA = hex("34c49954070a6fcb4632b82dc517051a")
        val payloadB = hex("280019154e1c9f1ac82229c02b8e1119")
        assertFalse(payloadA.contentEquals(payloadB))
        assertEquals(crc32(payloadA), crc32(payloadB))
        val apkA = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", payloadA, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(7), METHOD_STORED),
        )
        val apkB = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", payloadB, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(7), METHOD_STORED),
        )
        assertEquals(apkA.size, apkB.size)
        val source = RandomPayloadSwapSource(
            stableApk = apkA,
            swappedApk = apkB,
            payloadOffset = NativeCloseApkTestFixture.localPayloadOffsets(apkA).first(),
            payloadLength = payloadA.size,
        )
        val plan = plan("base", source)
        source.armRandomPayloadSwap()

        val images = validImages(plan)
        val dexDigest = requireNotNull(
            images.packageOutputs.single().apkView.entries.first().contentSha256,
        ).copyBytes()

        assertArrayEquals(sha256(payloadA), dexDigest)
        assertFalse(sha256(payloadB).contentEquals(dexDigest))
        assertEquals(0, source.swappedPayloadReadCount)
        assertArrayEquals(
            sha256(apkA),
            images.runtimeArtifactEntries.single().apkSha256.copyBytes(),
        )
    }

    @Test
    fun randomStructureBAndSequentialSnapshotACannotForgeCodeImageFromBSelection() {
        val dexX = "true-a-dex-x".toByteArray()
        val inertY = "forged-dex-y".toByteArray()
        assertEquals(dexX.size, inertY.size)
        val apkA = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", dexX, METHOD_STORED),
            NativeCloseApkTestFixture.Member("assets/xbin", inertY, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(9), METHOD_STORED),
        )
        val apkB = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("assets/xbin", dexX, METHOD_STORED),
            NativeCloseApkTestFixture.Member("classes.dex", inertY, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(9), METHOD_STORED),
        )
        assertEquals(apkA.size, apkB.size)
        val source = SequentialSnapshotARandomStructureBSource(apkA, apkB)

        val inspection = validImages(plan("base", source))
        val dexDigest = requireNotNull(
            inspection.packageOutputs.single().apkView.entries.first().contentSha256,
        ).copyBytes()

        assertArrayEquals(sha256(apkA), inspection.runtimeArtifactEntries.single().apkSha256.copyBytes())
        assertArrayEquals(sha256(dexX), dexDigest)
        assertFalse(sha256(inertY).contentEquals(dexDigest))
        assertEquals(1, source.sequentialSnapshotReads)
        assertEquals(0, source.randomStructureReads)
    }

    @Test
    fun retainedSourceDestinationAndObserverMutationCannotSplitPrivateSnapshotHashes() {
        val payloadA = hex("34c49954070a6fcb4632b82dc517051a")
        val payloadB = hex("280019154e1c9f1ac82229c02b8e1119")
        assertEquals(crc32(payloadA), crc32(payloadB))
        val apkA = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", payloadA, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(7), METHOD_STORED),
        )
        val apkB = NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", payloadB, METHOD_STORED),
            NativeCloseApkTestFixture.Member("lib/x86/liba.so", byteArrayOf(7), METHOD_STORED),
        )
        val source = RetainedDestinationMutationSource(
            apkA,
            apkB,
            NativeCloseApkTestFixture.localPayloadOffsets(apkA).first(),
            payloadA.size,
        )
        val observer = NativeCloseInspectionObserver {
            source.onCheckpoint()
            true
        }

        val inspection = validImages(plan("base", source, observer))
        val dexDigest = requireNotNull(
            inspection.packageOutputs.single().apkView.entries.first().contentSha256,
        ).copyBytes()

        assertArrayEquals(sha256(apkA), inspection.runtimeArtifactEntries.single().apkSha256.copyBytes())
        assertArrayEquals(sha256(payloadA), dexDigest)
        assertFalse(sha256(payloadB).contentEquals(dexDigest))
        assertEquals(1, source.mutationCount)
        assertEquals(1, source.restoreCount)
    }

    @Test
    fun observerCancellationDuringSnapshotMetadataAndRepeatedInspectionAreDeterministic() {
        val bytes = validPackageBytes()
        val calls = AtomicInteger()
        val observer = NativeCloseInspectionObserver {
            calls.incrementAndGet()
            true
        }
        val repeatable = plan("base", ByteArraySource(bytes), observer)
        val first = NativeCloseInstalledImageTestHarness.build(listOf(repeatable))
        assertNotNull(first.inspection)
        assertTrue(calls.get() > 10)
        val second = NativeCloseInstalledImageTestHarness.build(listOf(repeatable))
        assertNotNull(second.inspection)
        assertEquals(first.inspection?.codeImage?.sha256, second.inspection?.codeImage?.sha256)

        val cancellableSource = ByteArraySource(bytes)
        val postSnapshotCalls = AtomicInteger()
        val cancellable = NativeCloseInspectionObserver {
            cancellableSource.eofProbeCount == 0 || postSnapshotCalls.incrementAndGet() <= 10
        }
        assertImagesFailure(
            NativeCloseInstalledImageTestHarness.build(
                listOf(plan("base", cancellableSource, cancellable)),
            ),
            NativeCloseInstalledImagesFailure.INSPECTION_ABORTED,
        )
        assertEquals(1, cancellableSource.eofProbeCount)
        assertEquals(2, cancellableSource.readCallCount)
        assertEquals(11, postSnapshotCalls.get())
    }

    private fun validPackageBytes(jniName: String = "lib/x86/liba.so"): ByteArray =
        NativeCloseApkTestFixture.apk(
            NativeCloseApkTestFixture.Member("classes.dex", byteArrayOf(1, 2, 3), METHOD_STORED),
            NativeCloseApkTestFixture.Member(jniName, byteArrayOf(4, 5), METHOD_STORED),
        )

    private fun plan(
        name: String,
        source: NativeCloseApkSource,
        observer: NativeCloseInspectionObserver = NativeCloseInspectionObserver { true },
    ): NativeCloseInstalledApkEngine.PackageInput =
        NativeCloseInstalledApkEngine.PackageInput(name, source, observer)

    private fun planResult(
        name: String,
        source: NativeCloseApkSource,
    ): NativeCloseInstalledImagesInspectionResult =
        NativeCloseInstalledImageTestHarness.build(listOf(plan(name, source)))

    private fun validImages(
        vararg plans: NativeCloseInstalledApkEngine.PackageInput,
    ): NativeCloseInstalledImagesInspection {
        val result = NativeCloseInstalledImageTestHarness.build(plans.toList())
        assertNull(result.failure)
        return requireNotNull(result.inspection)
    }

    private fun assertPlanFailure(
        result: NativeCloseInstalledImagesInspectionResult,
        expected: NativeCloseApkFailure,
    ) {
        assertNull(result.inspection)
        assertEquals(expected, result.apkFailure)
    }

    private fun assertImagesFailure(
        result: NativeCloseInstalledImagesInspectionResult,
        expected: NativeCloseInstalledImagesFailure,
    ) {
        assertNull(result.inspection)
        assertEquals(expected, result.failure)
    }

    private open class ByteArraySource(
        private val bytes: ByteArray,
        private val maximumRead: Int = Int.MAX_VALUE,
        private val zeroAfterOffset: Long? = null,
    ) : NativeCloseApkSource {
        var readCallCount: Int = 0
            private set
        var eofProbeCount: Int = 0
            private set

        override val length: Long
            get() = bytes.size.toLong()

        override fun copyIdentitySnapshot(): ByteArray = "immutable-fixture-v1".toByteArray()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            readCallCount += 1
            if (zeroAfterOffset != null && offset >= zeroAfterOffset) return 0
            if (offset == bytes.size.toLong()) {
                eofProbeCount += 1
                return -1
            }
            if (offset < 0L || offset > bytes.size.toLong()) return -1
            val count = minOf(byteCount, maximumRead, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
    }

    private data class LengthFailureCase(
        val throwable: Throwable,
        val expectedApkFailure: NativeCloseApkFailure,
        val expectedImagesFailure: NativeCloseInstalledImagesFailure,
    )

    private class LengthFailureSource(
        private val throwable: Throwable,
        private val bytes: ByteArray? = null,
        private val failOnLengthCall: Int = 1,
    ) : NativeCloseApkSource {
        var lengthCalls: Int = 0
            private set
        var identityCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set

        override val length: Long
            get() {
                lengthCalls += 1
                if (lengthCalls == failOnLengthCall) throw throwable
                return requireNotNull(bytes).size.toLong()
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
            val sourceBytes = requireNotNull(bytes)
            if (offset == sourceBytes.size.toLong()) return -1
            if (offset < 0L || offset > sourceBytes.size.toLong()) return -1
            val count = minOf(byteCount, sourceBytes.size - offset.toInt())
            sourceBytes.copyInto(
                destination,
                destinationOffset,
                offset.toInt(),
                offset.toInt() + count,
            )
            return count
        }
    }

    private class SwitchingSource(
        private val a: ByteArray,
        private val b: ByteArray,
        private val identityChanges: Boolean,
        private val switchAfterRead: Boolean = false,
    ) : NativeCloseApkSource {
        private var useB = false

        init {
            require(a.size == b.size)
        }

        override val length: Long
            get() = a.size.toLong()

        override fun copyIdentitySnapshot(): ByteArray =
            if (identityChanges && useB) byteArrayOf(2) else byteArrayOf(1)

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            val bytes = if (useB) b else a
            if (offset == bytes.size.toLong()) return -1
            if (offset < 0L || offset > bytes.size.toLong()) return -1
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            if (switchAfterRead) useB = true
            return count
        }
    }

    private class RandomPayloadSwapSource(
        private val stableApk: ByteArray,
        private val swappedApk: ByteArray,
        private val payloadOffset: Int,
        private val payloadLength: Int,
    ) : NativeCloseApkSource {
        private var armed = false
        var swappedPayloadReadCount: Int = 0
            private set

        init {
            require(stableApk.size == swappedApk.size)
        }

        fun armRandomPayloadSwap() {
            armed = true
        }

        override val length: Long
            get() = stableApk.size.toLong()

        override fun copyIdentitySnapshot(): ByteArray = byteArrayOf(1)

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset == stableApk.size.toLong()) return -1
            if (offset < 0L || offset > stableApk.size.toLong()) return -1
            val count = minOf(byteCount, stableApk.size - offset.toInt())
            val payloadEnd = payloadOffset + payloadLength
            val isPayloadOnlyRead = armed &&
                offset >= payloadOffset.toLong() &&
                offset + count.toLong() <= payloadEnd.toLong()
            val bytes = if (isPayloadOnlyRead) {
                swappedPayloadReadCount += 1
                swappedApk
            } else {
                stableApk
            }
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
    }

    private class SequentialSnapshotARandomStructureBSource(
        private val apkA: ByteArray,
        private val apkB: ByteArray,
    ) : NativeCloseApkSource {
        var sequentialSnapshotReads = 0
            private set
        var randomStructureReads = 0
            private set

        init {
            require(apkA.size == apkB.size)
        }

        override val length: Long
            get() = apkA.size.toLong()

        override fun copyIdentitySnapshot(): ByteArray = byteArrayOf(1)

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset == apkA.size.toLong()) return -1
            if (offset < 0L || offset > apkA.size.toLong()) return -1
            val bytes = if (offset == 0L) {
                sequentialSnapshotReads += 1
                apkA
            } else {
                randomStructureReads += 1
                apkB
            }
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
    }

    private class RetainedDestinationMutationSource(
        private val stableApk: ByteArray,
        private val swappedApk: ByteArray,
        private val payloadOffset: Int,
        private val payloadLength: Int,
    ) : NativeCloseApkSource {
        private var retainedDestination: ByteArray? = null
        private var retainedDestinationOffset = 0
        private var checkpointAfterRead = 0
        var mutationCount = 0
            private set
        var restoreCount = 0
            private set

        init {
            require(stableApk.size == swappedApk.size)
        }

        override val length: Long
            get() = stableApk.size.toLong()

        override fun copyIdentitySnapshot(): ByteArray = byteArrayOf(1)

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset == stableApk.size.toLong()) return -1
            if (offset < 0L || offset > stableApk.size.toLong()) return -1
            val count = minOf(byteCount, stableApk.size - offset.toInt())
            stableApk.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            if (offset == 0L && count == stableApk.size) {
                retainedDestination = destination
                retainedDestinationOffset = destinationOffset
                checkpointAfterRead = 0
            }
            return count
        }

        fun onCheckpoint() {
            val destination = retainedDestination ?: return
            checkpointAfterRead += 1
            when (checkpointAfterRead) {
                2 -> {
                    swappedApk.copyInto(
                        destination,
                        retainedDestinationOffset + payloadOffset,
                        payloadOffset,
                        payloadOffset + payloadLength,
                    )
                    mutationCount += 1
                }
                3 -> {
                    stableApk.copyInto(
                        destination,
                        retainedDestinationOffset + payloadOffset,
                        payloadOffset,
                        payloadOffset + payloadLength,
                    )
                    restoreCount += 1
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun crc32(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { pair -> pair.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val LOCAL_SIGNATURE = 0x04034b50
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val EOCD_SIGNATURE = 0x06054b50
        const val EOCD_LENGTH = 22
        const val UINT32_MAX = 0xffff_ffffL
        const val MAX_ENTRY_BYTES = 268_435_456L
    }
}

/** Test-only convenience around the explicitly non-authorizing batch inspection API. */
internal object NativeCloseInstalledImageTestHarness {
    fun build(
        inputs: List<NativeCloseInstalledApkEngine.PackageInput>,
    ): NativeCloseInstalledImagesInspectionResult =
        NativeCloseInstalledApkEngine.inspectPackageSet(inputs)
}

internal object NativeCloseApkTestFixture {
    data class Member(
        val name: String,
        val content: ByteArray,
        val method: Int,
        val compressedOverride: ByteArray? = null,
        val declaredUncompressedSize: Long? = null,
        val crcOverride: Long? = null,
    )

    private data class Central(
        val name: ByteArray,
        val method: Int,
        val crc32: Long,
        val compressed: ByteArray,
        val uncompressedLength: Long,
        val localOffset: Int,
    )

    fun apk(
        vararg members: Member,
        localExtra: ByteArray = byteArrayOf(),
        signingBlock: ByteArray? = null,
        gapAfterFirst: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(members.isNotEmpty())
        val locals = ByteArrayOutputStream()
        val central = ArrayList<Central>(members.size)
        members.forEachIndexed { index, member ->
            val name = member.name.toByteArray(Charsets.US_ASCII)
            val compressed = member.compressedOverride ?: if (member.method == METHOD_STORED) {
                member.content
            } else {
                deflate(member.content)
            }
            val crc = member.crcOverride ?: CRC32().also { it.update(member.content) }.value
            val uncompressed = member.declaredUncompressedSize ?: member.content.size.toLong()
            val localOffset = locals.size()
            locals.write(
                littleEndian(30)
                    .putInt(LOCAL_SIGNATURE)
                    .putShort(20)
                    .putShort(0)
                    .putShort(member.method)
                    .putShort(0)
                    .putShort(0)
                    .putInt(crc.toInt())
                    .putInt(compressed.size)
                    .putInt(uncompressed.toInt())
                    .putShort(name.size)
                    .putShort(localExtra.size)
                    .array(),
            )
            locals.write(name)
            locals.write(localExtra)
            locals.write(compressed)
            central += Central(name, member.method, crc, compressed, uncompressed, localOffset)
            if (index == 0) locals.write(gapAfterFirst)
        }
        val block = signingBlock ?: byteArrayOf()
        val centralOffset = locals.size() + block.size
        val directory = ByteArrayOutputStream()
        central.forEach { entry ->
            directory.write(
                littleEndian(46)
                    .putInt(CENTRAL_SIGNATURE)
                    .putShort(20)
                    .putShort(20)
                    .putShort(0)
                    .putShort(entry.method)
                    .putShort(0)
                    .putShort(0)
                    .putInt(entry.crc32.toInt())
                    .putInt(entry.compressed.size)
                    .putInt(entry.uncompressedLength.toInt())
                    .putShort(entry.name.size)
                    .putShort(0)
                    .putShort(0)
                    .putShort(0)
                    .putShort(0)
                    .putInt(0)
                    .putInt(entry.localOffset)
                    .array(),
            )
            directory.write(entry.name)
        }
        return ByteArrayOutputStream().also { output ->
            output.write(locals.toByteArray())
            output.write(block)
            output.write(directory.toByteArray())
            output.write(
                littleEndian(EOCD_LENGTH)
                    .putInt(EOCD_SIGNATURE)
                    .putShort(0)
                    .putShort(0)
                    .putShort(members.size)
                    .putShort(members.size)
                    .putInt(directory.size())
                    .putInt(centralOffset)
                    .putShort(0)
                    .array(),
            )
        }.toByteArray()
    }

    fun alignmentPadding(extraSize: Int): ByteArray {
        require(extraSize in 1..0xffff)
        return ByteBuffer.allocate(30 + extraSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(LOCAL_SIGNATURE)
            .putShort(0)
            .putShort(0)
            .putShort(0)
            .putShort(0x0821)
            .putShort(0x0221)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .putShort(0)
            .putShort(extraSize.toShort())
            .array()
    }

    fun centralOffset(bytes: ByteArray): Int = le32(bytes, bytes.size - EOCD_LENGTH + 16).toInt()

    fun centralRecordOffsets(bytes: ByteArray): List<Int> {
        val count = le16(bytes, bytes.size - EOCD_LENGTH + 10)
        var cursor = centralOffset(bytes)
        return List(count) {
            val start = cursor
            val nameLength = le16(bytes, cursor + 28)
            val extraLength = le16(bytes, cursor + 30)
            val commentLength = le16(bytes, cursor + 32)
            cursor += 46 + nameLength + extraLength + commentLength
            start
        }
    }

    fun localPayloadOffsets(bytes: ByteArray): List<Int> = centralRecordOffsets(bytes).map { central ->
        val local = le32(bytes, central + 42).toInt()
        local + 30 + le16(bytes, local + 26) + le16(bytes, local + 28)
    }

    fun deflate(content: ByteArray): ByteArray {
        val compressor = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            compressor.setInput(content)
            compressor.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!compressor.finished()) {
                val count = compressor.deflate(buffer)
                check(count > 0)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            compressor.end()
        }
    }

    fun signingBlock(
        vararg pairs: Pair<Long, ByteArray>,
        pairRegionSuffix: ByteArray = byteArrayOf(),
    ): ByteArray {
        val pairBytes = ByteArrayOutputStream()
        pairs.forEach { (id, value) ->
            pairBytes.write(littleEndian(8).putLong(4L + value.size).array())
            pairBytes.write(littleEndian(4).putInt(id.toInt()).array())
            pairBytes.write(value)
        }
        pairBytes.write(pairRegionSuffix)
        val size = 24L + pairBytes.size()
        return ByteArrayOutputStream().also { output ->
            output.write(littleEndian(8).putLong(size).array())
            output.write(pairBytes.toByteArray())
            output.write(littleEndian(8).putLong(size).array())
            output.write("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
    }

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.putShort(value: Int): ByteBuffer = putShort(value.toShort())

    private fun le16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun le32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private const val METHOD_STORED = 0
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_LENGTH = 22
}

private fun le32(bytes: ByteArray, offset: Int): Long =
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
    ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
}

private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
}

private fun putLe64(bytes: ByteArray, offset: Int, value: ULong) {
    ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong())
}
