package com.motionarcade.vision.capability.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Data-only random-access APK seam. Implementations are untrusted parser inputs, not authority.
 * The engine copies one complete, size-capped observation into memory that this interface never
 * receives, then performs every structural and content decision from that private snapshot.
 */
internal interface NativeCloseApkSource {
    val length: Long

    /** Stable file-object identity/version bytes, not a path or authorization token. */
    fun copyIdentitySnapshot(): ByteArray

    /** Returns -1 only at exact EOF; zero is never a successful read. */
    fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, byteCount: Int): Int
}

/** Cooperative deadline/cancellation hook. Returning false fails the inspection closed. */
internal fun interface NativeCloseInspectionObserver {
    fun shouldContinue(): Boolean
}

internal enum class NativeCloseApkFailure {
    INVALID_LENGTH,
    PACKAGE_OUTPUT_INVALID,
    SOURCE_IDENTITY_INVALID,
    SOURCE_CHANGED,
    INSPECTION_ABORTED,
    RESOURCE_LIMIT_EXCEEDED,
    READ_FAILED,
    EOCD_INVALID,
    CENTRAL_DIRECTORY_INVALID,
    ENTRY_NAME_INVALID,
    DUPLICATE_ENTRY,
    LOCAL_RECORD_INVALID,
    PAYLOAD_INVALID,
    SIGNING_BLOCK_INVALID,
    DEX_IMAGE_INVALID,
    JNI_SET_INVALID,
}

/**
 * Batch, parse-only inspection engine. Its output is diagnostic material and is intentionally
 * constructible/forgeable by ordinary JVM code. No native-create API consumes it.
 */
internal object NativeCloseInstalledApkEngine {
    fun inspectPackageSet(inputs: List<PackageInput>): InspectionAttemptMaterial = try {
        InspectionAttemptMaterial(inspectPackageSetOrReject(inputs), null, null)
    } catch (failure: ImagesRejected) {
        InspectionAttemptMaterial(null, failure.reason, null)
    } catch (failure: ApkRejected) {
        InspectionAttemptMaterial(null, mapApkFailure(failure.reason), failure.reason)
    } catch (_: OutOfMemoryError) {
        InspectionAttemptMaterial(
            null,
            NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED,
            NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
        )
    } catch (_: Exception) {
        InspectionAttemptMaterial(
            null,
            NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED,
            null,
        )
    }

    private fun inspectPackageSetOrReject(inputs: List<PackageInput>): InspectionMaterial {
        if (inputs.isEmpty() || inputs.size > MAX_PACKAGE_OUTPUTS) {
            rejectImages(NativeCloseInstalledImagesFailure.PACKAGE_SET_INVALID)
        }
        val validatedInputs = validatePackageSet(inputs)
        val metadataBudget = MetadataBudget()
        val snapshotBudget = SnapshotBudget()
        val allPayloadBudget = AllPayloadBudget()
        val selectedValidation = GlobalSelectedValidation()
        val preparedPackages = ArrayList<PreparedPackage>(validatedInputs.size)
        validatedInputs.forEach { validated ->
            checkpoint(validated.input.observer)
            val captured = capturePackage(validated, snapshotBudget)
            val structure = StructuralParser(
                Reader(captured.snapshot, validated.input.observer),
                metadataBudget,
            ).parse()
            selectedValidation.add(
                validated.input.packageOutputName,
                structure.entries,
                validated.input.observer,
            )
            preparedPackages += preparePackage(captured, structure)
            checkpoint(validated.input.observer)
        }
        selectedValidation.finish()
        preparedPackages.forEach { prepared ->
            allPayloadBudget.reserve(
                prepared.structure.entries,
                prepared.captured.input.input.observer,
            )
        }
        val outputs = preparedPackages.map(::materialize)

        val dexInputs = ArrayList<ImageEntry>()
        val jniInputs = HashMap<NativeCloseAbi, MutableList<ImageEntry>>()
        outputs.forEachIndexed { outputIndex, output ->
            val observer = validatedInputs[outputIndex].input.observer
            checkpoint(observer)
            val packageNameBytes = output.packageNameBytes
            output.apkView.entries.forEach { entry ->
                checkpoint(observer)
                val digest = entry.contentSha256
                if (digest != null) {
                    when {
                        DEX_NAME.matches(entry.name) -> dexInputs += ImageEntry(
                            output.packageOutputName,
                            packageNameBytes,
                            entry.name,
                            entry.nameBytes,
                            entry.uncompressedLength.toULong(),
                            digest,
                        )
                        entry.name.startsWith(JNI_PREFIX) -> {
                            val abi = parseJniAbi(entry.name)
                            jniInputs.getOrPut(abi) { ArrayList() } += ImageEntry(
                                output.packageOutputName,
                                packageNameBytes,
                                entry.name,
                                entry.nameBytes,
                                entry.uncompressedLength.toULong(),
                                digest,
                            )
                        }
                    }
                }
                checkpoint(observer)
            }
            checkpoint(observer)
        }
        val codeImage = buildCodeImage(dexInputs)
        val jniSets = jniInputs.entries
            .sortedWith { left, right -> compareBytes(abiBytes(left.key), abiBytes(right.key)) }
            .map { (abi, entries) -> buildJniSet(abi, entries) }
        if (jniSets.isEmpty() || jniSets.size > NativeCloseAbi.entries.size) {
            rejectImages(NativeCloseInstalledImagesFailure.JNI_SET_INVALID)
        }
        return InspectionMaterial(codeImage, jniSets, outputs)
    }

    private fun validatePackageSet(inputs: List<PackageInput>): List<ValidatedInput> {
        val validatedInputs = inputs.map { input ->
            checkpoint(input.observer)
            val validated = ValidatedInput(input, validatePackageOutputName(input.packageOutputName))
            checkpoint(input.observer)
            validated
        }
        if (validatedInputs.first().input.packageOutputName != BASE_OUTPUT_NAME) {
            rejectImages(NativeCloseInstalledImagesFailure.PACKAGE_SET_INVALID)
        }
        val names = HashSet<String>(inputs.size)
        validatedInputs.forEachIndexed { index, validated ->
            val input = validated.input
            checkpoint(input.observer)
            if ((index > 0 && input.packageOutputName == BASE_OUTPUT_NAME) ||
                !names.add(input.packageOutputName)
            ) {
                rejectImages(NativeCloseInstalledImagesFailure.PACKAGE_SET_INVALID)
            }
            checkpoint(input.observer)
        }
        return validatedInputs
    }

    private class GlobalSelectedValidation {
        private var dexCount = 0
        private var dexBytes = 0L
        private val jniCount = HashMap<NativeCloseAbi, Int>()
        private val jniBytes = HashMap<NativeCloseAbi, Long>()
        private val jniLogicalPaths = HashSet<String>()

        fun add(
            packageOutputName: String,
            entries: List<EntryMetadata>,
            observer: NativeCloseInspectionObserver,
        ) {
            val dexNames = ArrayList<String>()
            entries.forEach { entry ->
                checkpoint(observer)
                when (val kind = entry.kind) {
                    EntryKind.Dex -> {
                        dexCount = checkedIntAdd(dexCount, 1, NativeCloseApkFailure.DEX_IMAGE_INVALID)
                        dexBytes = checkedLongAdd(
                            dexBytes,
                            entry.uncompressedSize,
                            NativeCloseApkFailure.DEX_IMAGE_INVALID,
                        )
                        dexNames += entry.name
                        if (dexCount > MAX_DEX_ENTRIES || dexBytes > MAX_IMAGE_BYTES) {
                            reject(NativeCloseApkFailure.DEX_IMAGE_INVALID)
                        }
                    }
                    is EntryKind.Jni -> {
                        val abi = kind.abi
                        jniCount[abi] = checkedIntAdd(
                            jniCount[abi] ?: 0,
                            1,
                            NativeCloseApkFailure.JNI_SET_INVALID,
                        )
                        jniBytes[abi] = checkedLongAdd(
                            jniBytes[abi] ?: 0L,
                            entry.uncompressedSize,
                            NativeCloseApkFailure.JNI_SET_INVALID,
                        )
                        if (!jniLogicalPaths.add(entry.name)) {
                            reject(NativeCloseApkFailure.JNI_SET_INVALID)
                        }
                        if ((jniCount[abi] ?: 0) > MAX_JNI_ENTRIES ||
                            (jniBytes[abi] ?: Long.MAX_VALUE) > MAX_IMAGE_BYTES
                        ) {
                            reject(NativeCloseApkFailure.JNI_SET_INVALID)
                        }
                    }
                    EntryKind.Other -> Unit
                }
                checkpoint(observer)
            }
            val ordinals = dexNames.map(::dexOrdinal).sorted()
            if (ordinals != (1..ordinals.size).toList()) {
                reject(NativeCloseApkFailure.DEX_IMAGE_INVALID)
            }
        }

        fun finish() {
            if (dexCount !in 1..MAX_DEX_ENTRIES || dexBytes > MAX_IMAGE_BYTES) {
                reject(NativeCloseApkFailure.DEX_IMAGE_INVALID)
            }
            if (jniCount.isEmpty() || jniCount.size > NativeCloseAbi.entries.size) {
                reject(NativeCloseApkFailure.JNI_SET_INVALID)
            }
            jniCount.forEach { (abi, count) ->
                if (count !in 1..MAX_JNI_ENTRIES ||
                    (jniBytes[abi] ?: Long.MAX_VALUE) > MAX_IMAGE_BYTES
                ) {
                    reject(NativeCloseApkFailure.JNI_SET_INVALID)
                }
            }
        }
    }

    private fun preparePackage(
        captured: CapturedPackage,
        structure: Structure,
    ): PreparedPackage = PreparedPackage(captured, structure)

    private fun materialize(prepared: PreparedPackage): PackageOutputMaterial {
        val observer = prepared.captured.input.input.observer
        val payloadDigests = Reader.validateAllPayloads(
            prepared.captured.snapshot,
            prepared.structure.entries,
            observer,
        ).iterator()
        val entries = prepared.structure.entries.map { entry ->
            checkpoint(observer)
            if (!payloadDigests.hasNext()) reject(NativeCloseApkFailure.PAYLOAD_INVALID)
            val digest = payloadDigests.next()
            val material = InstalledApkEntryMaterial(
                name = entry.name,
                nameBytes = entry.nameBytes,
                uncompressedLength = entry.uncompressedSize,
                contentSha256 = digest,
            )
            checkpoint(observer)
            material
        }
        if (payloadDigests.hasNext()) reject(NativeCloseApkFailure.PAYLOAD_INVALID)
        val artifact = RuntimeArtifactEntryV1(
            logicalName = prepared.captured.input.input.packageOutputName,
            apkLength = prepared.captured.snapshot.size.toULong(),
            apkSha256 = Sha256Digest.trusted(prepared.captured.apkSha256),
        )
        return PackageOutputMaterial(
            prepared.captured.input.input.packageOutputName,
            prepared.captured.input.packageNameBytes,
            entries,
            prepared.structure.signingBlockPresent,
            artifact,
        )
    }

    private class StructuralParser(
        private val reader: Reader,
        private val metadataBudget: MetadataBudget,
    ) {
        fun parse(): Structure {
            val fileLength = reader.fileLength
            val eocdOffset = fileLength - EOCD_LENGTH
            val eocd = reader.readExact(eocdOffset, EOCD_LENGTH.toInt())
            if (u32(eocd, 0) != EOCD_SIGNATURE) reject(NativeCloseApkFailure.EOCD_INVALID)
            val disk = u16(eocd, 4)
            val centralStartDisk = u16(eocd, 6)
            val diskEntries = u16(eocd, 8)
            val totalEntries = u16(eocd, 10)
            val centralSize = u32(eocd, 12)
            val centralOffset = u32(eocd, 16)
            val commentLength = u16(eocd, 20)
            if (disk != 0 || centralStartDisk != 0 || diskEntries != totalEntries ||
                totalEntries !in 1..MAX_ZIP_ENTRY_COUNT || commentLength != 0 ||
                centralSize == UINT32_MAX || centralOffset == UINT32_MAX ||
                checkedAdd(centralOffset, centralSize) != eocdOffset || centralOffset > eocdOffset
            ) {
                reject(NativeCloseApkFailure.EOCD_INVALID)
            }
            metadataBudget.reserveEntries(totalEntries, reader)
            val centralEntries = parseCentralDirectory(centralOffset, eocdOffset, totalEntries)
            val localEntries = parseLocalRecords(centralEntries, centralOffset)
            val physical = localEntries.sortedBy(LocalEntry::start)
            var expectedStart = 0L
            physical.forEach { entry ->
                reader.checkpoint()
                if (entry.start < expectedStart) reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                if (entry.start > expectedStart) {
                    validateAlignmentPadding(expectedStart, entry.start)
                }
                expectedStart = entry.end
                reader.checkpoint()
            }
            if (expectedStart > centralOffset) reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
            val signingBlockPresent = expectedStart != centralOffset
            if (signingBlockPresent) validateSigningBlock(expectedStart, centralOffset)
            validatePerPackageSelectedLimits(physical)
            val metadataEntries = physical.map { entry ->
                reader.checkpoint()
                val metadata = entry.metadata
                reader.checkpoint()
                metadata
            }
            return Structure(
                metadataEntries,
                signingBlockPresent,
            )
        }

        /**
         * AGP zipflinger aligns selected members with zero-only local records that intentionally
         * have no central-directory entry. Accept only that exact non-semantic grammar; any name,
         * payload, metadata drift, non-zero padding, or excessive gap remains fail-closed.
         */
        private fun validateAlignmentPadding(start: Long, end: Long) {
            val length = end - start
            if (length !in 1L..MAX_ALIGNMENT_PADDING_BYTES) {
                reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
            }
            var cursor = start
            var records = 0
            while (cursor < end) {
                reader.checkpoint()
                if (records >= MAX_ALIGNMENT_PADDING_RECORDS || end - cursor < LOCAL_HEADER_LENGTH) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                val header = reader.readWithin(
                    cursor,
                    LOCAL_HEADER_LENGTH,
                    end,
                    NativeCloseApkFailure.LOCAL_RECORD_INVALID,
                )
                val version = u16(header, 4)
                val flags = u16(header, 6)
                val method = u16(header, 8)
                val modifiedTime = u16(header, 10)
                val modifiedDate = u16(header, 12)
                val crc32 = u32(header, 14)
                val compressedSize = u32(header, 18)
                val uncompressedSize = u32(header, 22)
                val nameLength = u16(header, 26)
                val extraLength = u16(header, 28)
                val recordEnd = checkedAdd(cursor, checkedAdd(LOCAL_HEADER_LENGTH, extraLength.toLong()))
                if (u32(header, 0) != LOCAL_SIGNATURE || version != 0 || flags != 0 || method != 0 ||
                    modifiedTime != ALIGNMENT_PADDING_TIME || modifiedDate != ALIGNMENT_PADDING_DATE ||
                    crc32 != 0L || compressedSize != 0L || uncompressedSize != 0L ||
                    nameLength != 0 || extraLength == 0 || recordEnd > end
                ) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                val extra = reader.readWithin(
                    checkedAdd(cursor, LOCAL_HEADER_LENGTH),
                    extraLength.toLong(),
                    recordEnd,
                    NativeCloseApkFailure.LOCAL_RECORD_INVALID,
                )
                if (extra.any { it != 0.toByte() }) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                cursor = recordEnd
                records += 1
                reader.checkpoint()
            }
            if (cursor != end || records == 0) {
                reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
            }
        }

        private fun parseCentralDirectory(
            centralOffset: Long,
            eocdOffset: Long,
            totalEntries: Int,
        ): List<CentralEntry> {
            var cursor = centralOffset
            val entries = ArrayList<CentralEntry>(totalEntries)
            val names = HashSet<String>(totalEntries)
            repeat(totalEntries) {
                reader.checkpoint()
                val header = reader.readWithin(
                    cursor,
                    CENTRAL_HEADER_LENGTH,
                    eocdOffset,
                    NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
                )
                if (u32(header, 0) != CENTRAL_SIGNATURE) {
                    reject(NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID)
                }
                val creatorOs = u16(header, 4) ushr 8
                val flags = u16(header, 8)
                val method = u16(header, 10)
                val crc32 = u32(header, 16)
                val compressedSize = u32(header, 20)
                val uncompressedSize = u32(header, 24)
                val nameLength = u16(header, 28)
                val extraLength = u16(header, 30)
                val commentLength = u16(header, 32)
                val diskStart = u16(header, 34)
                val externalAttributes = u32(header, 38)
                val localOffset = u32(header, 42)
                val variableLength = checkedAdd(
                    nameLength.toLong(),
                    checkedAdd(extraLength.toLong(), commentLength.toLong()),
                )
                val recordEnd = checkedAdd(cursor, checkedAdd(CENTRAL_HEADER_LENGTH, variableLength))
                if (recordEnd > eocdOffset || diskStart != 0 ||
                    compressedSize == UINT32_MAX || uncompressedSize == UINT32_MAX ||
                    localOffset == UINT32_MAX || extraLength != 0 || commentLength != 0 ||
                    flags !in ALLOWED_FLAGS || method !in ALLOWED_METHODS
                ) {
                    reject(NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID)
                }
                val unixFileType = (externalAttributes ushr 16) and UNIX_FILE_TYPE_MASK
                if ((externalAttributes and DOS_DIRECTORY_BIT) != 0L ||
                    (creatorOs == UNIX_CREATOR_OS && unixFileType == UNIX_DIRECTORY_TYPE)
                ) {
                    reject(NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID)
                }
                metadataBudget.reserveNameBytes(nameLength, reader)
                val nameBytes = reader.readWithin(
                    checkedAdd(cursor, CENTRAL_HEADER_LENGTH),
                    nameLength.toLong(),
                    recordEnd,
                    NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID,
                )
                val name = canonicalAsciiName(nameBytes)
                if (!names.add(name)) reject(NativeCloseApkFailure.DUPLICATE_ENTRY)
                val kind = classifyEntry(name)
                if (kind != EntryKind.Other &&
                    (uncompressedSize !in 1L..MAX_ENTRY_BYTES)
                ) {
                    reject(
                        if (kind == EntryKind.Dex) NativeCloseApkFailure.DEX_IMAGE_INVALID
                        else NativeCloseApkFailure.JNI_SET_INVALID,
                    )
                }
                entries += CentralEntry(
                    nameBytes,
                    name,
                    flags,
                    method,
                    crc32,
                    compressedSize,
                    uncompressedSize,
                    localOffset,
                    kind,
                )
                cursor = recordEnd
                reader.checkpoint()
            }
            if (cursor != eocdOffset) reject(NativeCloseApkFailure.CENTRAL_DIRECTORY_INVALID)
            return entries
        }

        private fun parseLocalRecords(
            entries: List<CentralEntry>,
            centralOffset: Long,
        ): List<LocalEntry> {
            val localOffsets = HashSet<Long>(entries.size)
            return entries.map { entry ->
                reader.checkpoint()
                if (!localOffsets.add(entry.localOffset)) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                val header = reader.readWithin(
                    entry.localOffset,
                    LOCAL_HEADER_LENGTH,
                    centralOffset,
                    NativeCloseApkFailure.LOCAL_RECORD_INVALID,
                )
                if (u32(header, 0) != LOCAL_SIGNATURE) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                val flags = u16(header, 6)
                val method = u16(header, 8)
                val crc32 = u32(header, 14)
                val compressedSize = u32(header, 18)
                val uncompressedSize = u32(header, 22)
                val nameLength = u16(header, 26)
                val extraLength = u16(header, 28)
                val nameOffset = checkedAdd(entry.localOffset, LOCAL_HEADER_LENGTH)
                val extraOffset = checkedAdd(nameOffset, nameLength.toLong())
                val payloadOffset = checkedAdd(extraOffset, extraLength.toLong())
                val payloadEnd = checkedAdd(payloadOffset, compressedSize)
                if (payloadOffset > centralOffset || payloadEnd > centralOffset ||
                    payloadEnd <= entry.localOffset || flags != entry.flags ||
                    method != entry.method || crc32 != entry.crc32 ||
                    compressedSize != entry.compressedSize ||
                    uncompressedSize != entry.uncompressedSize
                ) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                val localName = reader.readWithin(
                    nameOffset,
                    nameLength.toLong(),
                    payloadOffset,
                    NativeCloseApkFailure.LOCAL_RECORD_INVALID,
                )
                if (!localName.contentEquals(entry.nameBytes)) {
                    reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                }
                if (extraLength > 0) {
                    val extra = reader.readWithin(
                        extraOffset,
                        extraLength.toLong(),
                        payloadOffset,
                        NativeCloseApkFailure.LOCAL_RECORD_INVALID,
                    )
                    if (extra.any { it != 0.toByte() }) {
                        reject(NativeCloseApkFailure.LOCAL_RECORD_INVALID)
                    }
                }
                val local = LocalEntry(
                    entry.localOffset,
                    payloadEnd,
                    EntryMetadata(
                        entry.name,
                        entry.nameBytes,
                        entry.method,
                        entry.crc32,
                        entry.compressedSize,
                        entry.uncompressedSize,
                        payloadOffset,
                        entry.kind,
                    ),
                )
                reader.checkpoint()
                local
            }
        }

        private fun validateSigningBlock(start: Long, end: Long) {
            val length = end - start
            if (length < MIN_SIGNING_BLOCK_LENGTH) {
                reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
            }
            val firstSize = u64(reader.readExact(start, 8), 0)
            val footer = reader.readExact(end - SIGNING_FOOTER_LENGTH, SIGNING_FOOTER_LENGTH.toInt())
            val footerSize = u64(footer, 0)
            if (!footer.copyOfRange(8, 24).contentEquals(SIGNING_MAGIC) ||
                firstSize != footerSize || firstSize < MIN_SIGNING_BLOCK_SIZE ||
                firstSize > Long.MAX_VALUE.toULong() ||
                firstSize != (length - 8L).toULong()
            ) {
                reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
            }
            var cursor = checkedAdd(start, 8L)
            val pairsEnd = end - SIGNING_FOOTER_LENGTH
            val ids = HashSet<Long>()
            var pairCount = 0
            while (cursor < pairsEnd) {
                reader.checkpoint()
                if (pairCount >= MAX_SIGNING_PAIR_COUNT) {
                    reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
                }
                if (pairsEnd - cursor < 8L) reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
                val pairLength = u64(reader.readExact(cursor, 8), 0)
                cursor = checkedAdd(cursor, 8L)
                if (pairLength < 4uL || pairLength > Long.MAX_VALUE.toULong() ||
                    pairLength.toLong() > pairsEnd - cursor
                ) {
                    reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
                }
                val id = u32(reader.readExact(cursor, 4), 0)
                if (id == 0L || !ids.add(id)) {
                    reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
                }
                cursor = checkedAdd(cursor, pairLength.toLong())
                pairCount += 1
                reader.checkpoint()
            }
            if (cursor != pairsEnd || pairCount == 0) {
                reject(NativeCloseApkFailure.SIGNING_BLOCK_INVALID)
            }
        }

        private fun validatePerPackageSelectedLimits(entries: List<LocalEntry>) {
            var dexCount = 0
            var dexBytes = 0L
            val jniCount = HashMap<NativeCloseAbi, Int>()
            val jniBytes = HashMap<NativeCloseAbi, Long>()
            entries.forEach { local ->
                reader.checkpoint()
                val entry = local.metadata
                when (val kind = entry.kind) {
                    EntryKind.Dex -> {
                        dexCount += 1
                        dexBytes = checkedLongAdd(
                            dexBytes,
                            entry.uncompressedSize,
                            NativeCloseApkFailure.DEX_IMAGE_INVALID,
                        )
                        if (dexCount > MAX_DEX_ENTRIES || dexBytes > MAX_IMAGE_BYTES) {
                            reject(NativeCloseApkFailure.DEX_IMAGE_INVALID)
                        }
                    }
                    is EntryKind.Jni -> {
                        val abi = kind.abi
                        val count = (jniCount[abi] ?: 0) + 1
                        val bytes = checkedLongAdd(
                            jniBytes[abi] ?: 0L,
                            entry.uncompressedSize,
                            NativeCloseApkFailure.JNI_SET_INVALID,
                        )
                        if (count > MAX_JNI_ENTRIES || bytes > MAX_IMAGE_BYTES) {
                            reject(NativeCloseApkFailure.JNI_SET_INVALID)
                        }
                        jniCount[abi] = count
                        jniBytes[abi] = bytes
                    }
                    EntryKind.Other -> Unit
                }
                reader.checkpoint()
            }
        }
    }

    private fun capturePackage(
        validated: ValidatedInput,
        snapshotBudget: SnapshotBudget,
    ): CapturedPackage {
        val input = validated.input
        checkpoint(input.observer)
        val length = try {
            input.source.length
        } catch (_: RuntimeException) {
            reject(NativeCloseApkFailure.READ_FAILED)
        } catch (_: AssertionError) {
            reject(NativeCloseApkFailure.READ_FAILED)
        }
        if (length < EOCD_LENGTH || length > MAX_IMMUTABLE_APK_BYTES || length > Int.MAX_VALUE) {
            reject(NativeCloseApkFailure.INVALID_LENGTH)
        }
        snapshotBudget.reserve(length, input.observer)
        val identity = identitySnapshot(input.source, input.observer)
        val snapshot = ByteArray(length.toInt())
        val sourceBuffer = ByteArray(minOf(STREAM_BUFFER_SIZE, snapshot.size))
        var cursor = 0
        while (cursor < snapshot.size) {
            val requested = minOf(sourceBuffer.size, snapshot.size - cursor)
            checkpoint(input.observer)
            val read = try {
                input.source.readAt(cursor.toLong(), sourceBuffer, 0, requested)
            } catch (_: RuntimeException) {
                reject(NativeCloseApkFailure.READ_FAILED)
            } catch (_: AssertionError) {
                reject(NativeCloseApkFailure.READ_FAILED)
            }
            checkpoint(input.observer)
            if (read <= 0 || read > requested) reject(NativeCloseApkFailure.READ_FAILED)
            sourceBuffer.copyInto(snapshot, cursor, 0, read)
            cursor += read
            checkpoint(input.observer)
        }
        val eofProbe = ByteArray(1)
        checkpoint(input.observer)
        val eof = try {
            input.source.readAt(length, eofProbe, 0, 1)
        } catch (_: RuntimeException) {
            reject(NativeCloseApkFailure.READ_FAILED)
        } catch (_: AssertionError) {
            reject(NativeCloseApkFailure.READ_FAILED)
        }
        checkpoint(input.observer)
        if (eof != -1) reject(NativeCloseApkFailure.READ_FAILED)
        requireStableMetadata(input.source, length, identity, input.observer)
        return CapturedPackage(
            validated,
            snapshot,
            MessageDigest.getInstance("SHA-256").digest(snapshot),
        )
    }

    private class Reader(
        private val snapshot: ByteArray,
        private val observer: NativeCloseInspectionObserver,
    ) {
        val fileLength: Long = snapshot.size.toLong()

        fun checkpoint() = com.motionarcade.vision.capability.domain.NativeCloseInstalledApkEngine
            .checkpoint(observer)

        fun readWithin(
            offset: Long,
            length: Long,
            exclusiveEnd: Long,
            failure: NativeCloseApkFailure,
        ): ByteArray {
            val end = try {
                Math.addExact(offset, length)
            } catch (_: ArithmeticException) {
                reject(failure)
            }
            if (offset < 0L || length < 0L || end > exclusiveEnd || length > Int.MAX_VALUE) {
                reject(failure)
            }
            return readExact(offset, length.toInt())
        }

        fun readExact(offset: Long, length: Int): ByteArray {
            val end = checkedAdd(offset, length.toLong())
            if (offset < 0L || length < 0 || end > fileLength) {
                reject(NativeCloseApkFailure.READ_FAILED)
            }
            checkpoint()
            val copy = snapshot.copyOfRange(offset.toInt(), end.toInt())
            checkpoint()
            return copy
        }

        companion object {
            fun validateAllPayloads(
                snapshot: ByteArray,
                entries: List<EntryMetadata>,
                observer: NativeCloseInspectionObserver,
            ): List<Sha256Digest?> {
                checkpoint(observer)
                val results = ArrayList<Sha256Digest?>(entries.size)
                checkpoint(observer)
                entries.forEach { entry ->
                    checkpoint(observer)
                    val retainDigest = entry.kind != EntryKind.Other
                    val accumulator = PayloadAccumulator(
                        entry,
                        retainDigest,
                        { checkpoint(observer) },
                    )
                    try {
                        var payloadOffset = entry.payloadOffset.toInt()
                        var remaining = entry.compressedSize.toInt()
                        while (remaining > 0) {
                            val count = minOf(STREAM_BUFFER_SIZE, remaining)
                            accumulator.feed(
                                snapshot,
                                payloadOffset,
                                count,
                            )
                            payloadOffset += count
                            remaining -= count
                        }
                        accumulator.finish()
                        results.add(accumulator.resultOrNull())
                    } finally {
                        accumulator.close()
                    }
                    checkpoint(observer)
                }
                return results
            }
        }

        private class PayloadAccumulator(
            val entry: EntryMetadata,
            retainDigest: Boolean,
            private val checkpoint: () -> Unit,
        ) {
            private val crc = CRC32()
            private val digest = if (retainDigest) MessageDigest.getInstance("SHA-256") else null
            private val inflater = if (entry.method == METHOD_DEFLATED) Inflater(true) else null
            private val output = if (inflater != null) ByteArray(STREAM_BUFFER_SIZE) else null
            private var compressedConsumed = 0L
            private var produced = 0L
            private var completed = false
            private var digestValue: Sha256Digest? = null

            init {
                if (entry.uncompressedSize !in 0L..MAX_ENTRY_BYTES ||
                    (entry.kind != EntryKind.Other && entry.uncompressedSize == 0L)
                ) {
                    reject(
                        when (entry.kind) {
                            EntryKind.Dex -> NativeCloseApkFailure.DEX_IMAGE_INVALID
                            is EntryKind.Jni -> NativeCloseApkFailure.JNI_SET_INVALID
                            EntryKind.Other -> NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED
                        },
                    )
                }
                if (entry.method == METHOD_STORED &&
                    entry.compressedSize != entry.uncompressedSize
                ) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
            }

            fun feed(bytes: ByteArray, offset: Int, count: Int) {
                if (completed || count <= 0 || offset < 0 || count > bytes.size - offset) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
                compressedConsumed = checkedLongAdd(
                    compressedConsumed,
                    count.toLong(),
                    NativeCloseApkFailure.PAYLOAD_INVALID,
                )
                if (compressedConsumed > entry.compressedSize) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
                checkpoint()
                if (inflater == null) {
                    consume(bytes, offset, count)
                } else {
                    if (!inflater.needsInput()) reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    inflater.setInput(bytes, offset, count)
                    pumpInflater()
                    if (inflater.finished() &&
                        (inflater.remaining != 0 || compressedConsumed != entry.compressedSize)
                    ) {
                        reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    }
                }
                checkpoint()
            }

            fun finish() {
                if (completed) return
                if (compressedConsumed != entry.compressedSize) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
                if (inflater != null) {
                    pumpInflater()
                    if (!inflater.finished() || inflater.remaining != 0) {
                        reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    }
                }
                if (produced != entry.uncompressedSize || crc.value != entry.crc32) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
                digestValue = digest?.digest()?.let(Sha256Digest::trusted)
                completed = true
            }

            fun resultOrNull(): Sha256Digest? = when (entry.kind) {
                EntryKind.Other -> null
                EntryKind.Dex, is EntryKind.Jni -> digestValue
                    ?: reject(NativeCloseApkFailure.PAYLOAD_INVALID)
            }

            fun close() {
                inflater?.end()
            }

            private fun pumpInflater() {
                val activeInflater = inflater ?: reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                val activeOutput = output ?: reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                while (true) {
                    if (activeInflater.needsDictionary()) {
                        reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    }
                    checkpoint()
                    val count = try {
                        activeInflater.inflate(activeOutput)
                    } catch (_: DataFormatException) {
                        reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    }
                    checkpoint()
                    if (count > 0) consume(activeOutput, 0, count)
                    when {
                        activeInflater.finished() -> return
                        activeInflater.needsDictionary() ->
                            reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                        activeInflater.needsInput() -> return
                        count == 0 -> reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                    }
                }
            }

            private fun consume(bytes: ByteArray, offset: Int, count: Int) {
                produced = checkedLongAdd(
                    produced,
                    count.toLong(),
                    NativeCloseApkFailure.PAYLOAD_INVALID,
                )
                if (produced > entry.uncompressedSize || produced > MAX_ENTRY_BYTES) {
                    reject(NativeCloseApkFailure.PAYLOAD_INVALID)
                }
                crc.update(bytes, offset, count)
                digest?.update(bytes, offset, count)
            }
        }
    }

    internal class PackageInput(
        val packageOutputName: String,
        val source: NativeCloseApkSource,
        val observer: NativeCloseInspectionObserver = NativeCloseInspectionObserver { true },
    )

    private data class ValidatedInput(val input: PackageInput, val packageNameBytes: ByteArray)

    private data class CapturedPackage(
        val input: ValidatedInput,
        val snapshot: ByteArray,
        val apkSha256: ByteArray,
    )

    private data class PreparedPackage(
        val captured: CapturedPackage,
        val structure: Structure,
    )

    private class SnapshotBudget {
        private var inspectedBytes = 0L

        fun reserve(count: Long, observer: NativeCloseInspectionObserver) {
            checkpoint(observer)
            inspectedBytes = checkedLongAdd(
                inspectedBytes,
                count,
                NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
            )
            if (count < 0L || inspectedBytes > MAX_PACKAGE_SET_APK_BYTES) {
                reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
            }
            checkpoint(observer)
        }
    }

    private class AllPayloadBudget {
        private var uncompressedBytes = 0L

        fun reserve(entries: List<EntryMetadata>, observer: NativeCloseInspectionObserver) {
            entries.forEach { entry ->
                checkpoint(observer)
                if (entry.uncompressedSize < 0L || entry.uncompressedSize > MAX_ENTRY_BYTES) {
                    reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
                }
                uncompressedBytes = checkedLongAdd(
                    uncompressedBytes,
                    entry.uncompressedSize,
                    NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
                )
                if (uncompressedBytes > MAX_PACKAGE_SET_ALL_UNCOMPRESSED_BYTES) {
                    reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
                }
                checkpoint(observer)
            }
        }
    }

    private class MetadataBudget {
        private var entryCount = 0L
        private var retainedNameBytes = 0L

        fun reserveEntries(count: Int, reader: Reader) {
            reader.checkpoint()
            entryCount = checkedLongAdd(
                entryCount,
                count.toLong(),
                NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
            )
            if (entryCount > MAX_PACKAGE_SET_ENTRY_COUNT) {
                reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
            }
            reader.checkpoint()
        }

        fun reserveNameBytes(count: Int, reader: Reader) {
            reader.checkpoint()
            retainedNameBytes = checkedLongAdd(
                retainedNameBytes,
                count.toLong(),
                NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED,
            )
            if (retainedNameBytes > MAX_PACKAGE_SET_RETAINED_NAME_BYTES) {
                reject(NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED)
            }
            reader.checkpoint()
        }
    }

    internal class InstalledApkEntryMaterial(
        val name: String,
        val nameBytes: ByteArray,
        val uncompressedLength: Long,
        val contentSha256: Sha256Digest?,
    )

    internal class PackageOutputMaterial(
        val packageOutputName: String,
        val packageNameBytes: ByteArray,
        entries: List<InstalledApkEntryMaterial>,
        signingBlockPresent: Boolean,
        runtimeArtifactEntry: RuntimeArtifactEntryV1,
    ) {
        val apkView = ApkViewMaterial(entries, signingBlockPresent, runtimeArtifactEntry)
    }

    internal class ApkViewMaterial(
        entries: List<InstalledApkEntryMaterial>,
        val signingBlockPresent: Boolean,
        val runtimeArtifactEntry: RuntimeArtifactEntryV1,
    ) {
        val entries: List<InstalledApkEntryMaterial> =
            Collections.unmodifiableList(ArrayList(entries))
    }

    internal class CanonicalImageMaterial(
        canonicalBytes: ByteArray,
        val sha256: Sha256Digest,
    ) {
        private val canonicalBytes = canonicalBytes.clone()
        fun copyCanonicalBytes(): ByteArray = canonicalBytes.clone()
    }

    internal class JniSetMaterial(
        val abi: NativeCloseAbi,
        canonicalBytes: ByteArray,
        val sha256: Sha256Digest,
    ) {
        private val canonicalBytes = canonicalBytes.clone()
        fun copyCanonicalBytes(): ByteArray = canonicalBytes.clone()
    }

    internal class InspectionMaterial(
        val codeImage: CanonicalImageMaterial,
        jniSets: List<JniSetMaterial>,
        packageOutputs: List<PackageOutputMaterial>,
    ) {
        val jniSets: List<JniSetMaterial> = Collections.unmodifiableList(ArrayList(jniSets))
        val packageOutputs: List<PackageOutputMaterial> =
            Collections.unmodifiableList(ArrayList(packageOutputs))
        val runtimeArtifactEntries: List<RuntimeArtifactEntryV1> = Collections.unmodifiableList(
            packageOutputs.map { it.apkView.runtimeArtifactEntry },
        )
    }

    internal class InspectionAttemptMaterial(
        val inspection: InspectionMaterial?,
        val failure: NativeCloseInstalledImagesFailure?,
        val apkFailure: NativeCloseApkFailure?,
    )

    private data class Structure(
        val entries: List<EntryMetadata>,
        val signingBlockPresent: Boolean,
    )

    private data class CentralEntry(
        val nameBytes: ByteArray,
        val name: String,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
        val kind: EntryKind,
    )

    private data class LocalEntry(
        val start: Long,
        val end: Long,
        val metadata: EntryMetadata,
    )

    private data class EntryMetadata(
        val name: String,
        val nameBytes: ByteArray,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val payloadOffset: Long,
        val kind: EntryKind,
    )

    private sealed interface EntryKind {
        data object Dex : EntryKind
        data class Jni(val abi: NativeCloseAbi) : EntryKind
        data object Other : EntryKind
    }

    private data class ImageEntry(
        val packageName: String,
        val packageNameBytes: ByteArray,
        val entryName: String,
        val entryNameBytes: ByteArray,
        val length: ULong,
        val sha256: Sha256Digest,
    )

    private class ApkRejected(val reason: NativeCloseApkFailure) :
        RuntimeException(null, null, false, false)

    private class ImagesRejected(val reason: NativeCloseInstalledImagesFailure) :
        RuntimeException(null, null, false, false)

    private fun buildCodeImage(inputs: List<ImageEntry>): CanonicalImageMaterial {
        if (inputs.isEmpty() || inputs.size > MAX_DEX_ENTRIES) {
            rejectImages(NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID)
        }
        val framedSize = preflightFramedImageEntries(
            inputs,
            DEX_ENTRY_DOMAIN,
            "dex_entry_name",
        )
        var outerSize = canonicalRecordBaseSize(CODE_IMAGE_DOMAIN)
        outerSize = addCanonicalFieldSize(
            outerSize,
            "schema_revision",
            CODE_IMAGE_DOMAIN.length.toLong(),
        )
        outerSize = addCanonicalFieldSize(outerSize, "dex_entries", framedSize)
        requireCanonicalSize(outerSize)
        val encodedEntries = inputs.sortedWith(IMAGE_ENTRY_COMPARATOR).map { entry ->
            encodeRecord(
                DEX_ENTRY_DOMAIN,
                listOf(
                    CanonicalField("package_output_name", entry.packageNameBytes),
                    CanonicalField("dex_entry_name", entry.entryNameBytes),
                    CanonicalField("uncompressed_length", CanonicalManifestCodec.uint64(entry.length)),
                    CanonicalField("content_sha256", entry.sha256.copyBytes()),
                ),
            )
        }
        val bytes = encodeRecord(
            CODE_IMAGE_DOMAIN,
            listOf(
                CanonicalField("schema_revision", CODE_IMAGE_DOMAIN.toByteArray(Charsets.UTF_8)),
                CanonicalField("dex_entries", frameList(encodedEntries)),
            ),
        )
        return CanonicalImageMaterial(bytes, CanonicalManifestCodec.sha256(bytes))
    }

    private fun buildJniSet(
        abi: NativeCloseAbi,
        inputs: List<ImageEntry>,
    ): JniSetMaterial {
        if (inputs.isEmpty() || inputs.size > MAX_JNI_ENTRIES) {
            rejectImages(NativeCloseInstalledImagesFailure.JNI_SET_INVALID)
        }
        val framedSize = preflightFramedImageEntries(
            inputs,
            JNI_ENTRY_DOMAIN,
            "jni_entry_name",
        )
        var outerSize = canonicalRecordBaseSize(JNI_SET_DOMAIN)
        outerSize = addCanonicalFieldSize(outerSize, "abi", abiBytes(abi).size.toLong())
        outerSize = addCanonicalFieldSize(outerSize, "entries", framedSize)
        requireCanonicalSize(outerSize)
        val encodedEntries = inputs.sortedWith(IMAGE_ENTRY_COMPARATOR).map { entry ->
            encodeRecord(
                JNI_ENTRY_DOMAIN,
                listOf(
                    CanonicalField("package_output_name", entry.packageNameBytes),
                    CanonicalField("jni_entry_name", entry.entryNameBytes),
                    CanonicalField("uncompressed_length", CanonicalManifestCodec.uint64(entry.length)),
                    CanonicalField("content_sha256", entry.sha256.copyBytes()),
                ),
            )
        }
        val bytes = encodeRecord(
            JNI_SET_DOMAIN,
            listOf(
                CanonicalField("abi", abiBytes(abi)),
                CanonicalField("entries", frameList(encodedEntries)),
            ),
        )
        return JniSetMaterial(abi, bytes, CanonicalManifestCodec.sha256(bytes))
    }

    private fun preflightFramedImageEntries(
        inputs: List<ImageEntry>,
        entryDomain: String,
        entryNameField: String,
    ): Long {
        var framedSize = UInt.SIZE_BYTES.toLong()
        inputs.forEach { entry ->
            var recordSize = canonicalRecordBaseSize(entryDomain)
            recordSize = addCanonicalFieldSize(
                recordSize,
                "package_output_name",
                entry.packageNameBytes.size.toLong(),
            )
            recordSize = addCanonicalFieldSize(
                recordSize,
                entryNameField,
                entry.entryNameBytes.size.toLong(),
            )
            recordSize = addCanonicalFieldSize(
                recordSize,
                "uncompressed_length",
                ULong.SIZE_BYTES.toLong(),
            )
            recordSize = addCanonicalFieldSize(
                recordSize,
                "content_sha256",
                Sha256Digest.BYTE_COUNT.toLong(),
            )
            requireCanonicalSize(recordSize)
            framedSize = canonicalSizeAdd(framedSize, UInt.SIZE_BYTES.toLong())
            framedSize = canonicalSizeAdd(framedSize, recordSize)
            requireCanonicalSize(framedSize)
        }
        return framedSize
    }

    private fun canonicalRecordBaseSize(domain: String): Long = canonicalSizeAdd(
        domain.length.toLong(),
        1L + UInt.SIZE_BYTES,
    )

    private fun addCanonicalFieldSize(size: Long, name: String, valueSize: Long): Long {
        if (valueSize < 0L) {
            rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
        }
        var result = canonicalSizeAdd(size, UInt.SIZE_BYTES.toLong())
        result = canonicalSizeAdd(result, name.length.toLong())
        result = canonicalSizeAdd(result, ULong.SIZE_BYTES.toLong())
        return canonicalSizeAdd(result, valueSize)
    }

    private fun canonicalSizeAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
    }

    private fun requireCanonicalSize(size: Long) {
        if (size > MAX_CANONICAL_BYTES.toLong() || size > Int.MAX_VALUE.toLong()) {
            rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
        }
    }

    private fun encodeRecord(domain: String, fields: List<CanonicalField>): ByteArray =
        when (val encoded = CanonicalManifestCodec.encode(domain, fields, MAX_CANONICAL_BYTES)) {
            is CapabilityDomainResult.Valid -> encoded.value
            is CapabilityDomainResult.Invalid ->
                rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
        }

    private fun frameList(entries: List<ByteArray>): ByteArray {
        var size = UInt.SIZE_BYTES.toLong()
        entries.forEach { entry ->
            size = try {
                Math.addExact(size, UInt.SIZE_BYTES.toLong() + entry.size.toLong())
            } catch (_: ArithmeticException) {
                rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
            }
        }
        if (size > MAX_CANONICAL_BYTES.toLong()) {
            rejectImages(NativeCloseInstalledImagesFailure.CANONICAL_ENCODING_FAILED)
        }
        val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
        output.putInt(entries.size)
        entries.forEach { entry ->
            output.putInt(entry.size)
            output.put(entry)
        }
        return output.array()
    }

    private fun validatePackageOutputName(value: String): ByteArray {
        if (value.isEmpty() || value.length > MAX_PACKAGE_OUTPUT_NAME_BYTES ||
            value == "." || value == ".." ||
            value.any { it == '/' || it == '\\' || it == '\u0000' }
        ) {
            reject(NativeCloseApkFailure.PACKAGE_OUTPUT_INVALID)
        }
        return when (val encoded = CanonicalManifestCodec.strictUtf8(value, "$/package")) {
            is CapabilityDomainResult.Valid -> if (
                encoded.value.size <= MAX_PACKAGE_OUTPUT_NAME_BYTES
            ) {
                encoded.value
            } else {
                reject(NativeCloseApkFailure.PACKAGE_OUTPUT_INVALID)
            }
            is CapabilityDomainResult.Invalid -> reject(NativeCloseApkFailure.PACKAGE_OUTPUT_INVALID)
        }
    }

    private fun classifyEntry(name: String): EntryKind = when {
        DEX_NAME.matches(name) -> EntryKind.Dex
        name.startsWith(JNI_PREFIX) -> EntryKind.Jni(parseJniAbi(name))
        else -> EntryKind.Other
    }

    private fun parseJniAbi(name: String): NativeCloseAbi {
        val match = JNI_NAME.matchEntire(name) ?: reject(NativeCloseApkFailure.JNI_SET_INVALID)
        return NativeCloseAbi.fromWireName(match.groupValues[1])
            ?: reject(NativeCloseApkFailure.JNI_SET_INVALID)
    }

    private fun canonicalAsciiName(bytes: ByteArray): String {
        if (bytes.isEmpty() || bytes.any { value ->
                val unsigned = value.toInt() and 0xff
                unsigned == 0 || unsigned > 0x7f || value == '\\'.code.toByte()
            }
        ) {
            reject(NativeCloseApkFailure.ENTRY_NAME_INVALID)
        }
        val name = String(bytes, Charsets.US_ASCII)
        if (name.startsWith('/') || name.endsWith('/') ||
            name.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            reject(NativeCloseApkFailure.ENTRY_NAME_INVALID)
        }
        return name
    }

    private fun identitySnapshot(
        source: NativeCloseApkSource,
        observer: NativeCloseInspectionObserver,
    ): ByteArray {
        checkpoint(observer)
        val untrusted = try {
            source.copyIdentitySnapshot()
        } catch (_: RuntimeException) {
            reject(NativeCloseApkFailure.SOURCE_IDENTITY_INVALID)
        } catch (_: AssertionError) {
            reject(NativeCloseApkFailure.SOURCE_IDENTITY_INVALID)
        }
        checkpoint(observer)
        if (untrusted.isEmpty() || untrusted.size > MAX_SOURCE_IDENTITY_BYTES) {
            reject(NativeCloseApkFailure.SOURCE_IDENTITY_INVALID)
        }
        return untrusted.copyOf()
    }

    private fun requireStableMetadata(
        source: NativeCloseApkSource,
        expectedLength: Long,
        expectedIdentity: ByteArray,
        observer: NativeCloseInspectionObserver,
    ) {
        checkpoint(observer)
        val length = try {
            source.length
        } catch (_: RuntimeException) {
            reject(NativeCloseApkFailure.SOURCE_CHANGED)
        } catch (_: AssertionError) {
            reject(NativeCloseApkFailure.SOURCE_CHANGED)
        }
        if (length != expectedLength ||
            !identitySnapshot(source, observer).contentEquals(expectedIdentity)
        ) {
            reject(NativeCloseApkFailure.SOURCE_CHANGED)
        }
    }

    private fun checkpoint(observer: NativeCloseInspectionObserver) {
        val continuing = try {
            observer.shouldContinue()
        } catch (_: RuntimeException) {
            false
        } catch (_: AssertionError) {
            false
        }
        if (!continuing) reject(NativeCloseApkFailure.INSPECTION_ABORTED)
    }

    private fun mapApkFailure(reason: NativeCloseApkFailure): NativeCloseInstalledImagesFailure =
        when (reason) {
            NativeCloseApkFailure.SOURCE_CHANGED -> NativeCloseInstalledImagesFailure.SOURCE_CHANGED
            NativeCloseApkFailure.INSPECTION_ABORTED ->
                NativeCloseInstalledImagesFailure.INSPECTION_ABORTED
            NativeCloseApkFailure.RESOURCE_LIMIT_EXCEEDED ->
                NativeCloseInstalledImagesFailure.RESOURCE_LIMIT_EXCEEDED
            NativeCloseApkFailure.DEX_IMAGE_INVALID ->
                NativeCloseInstalledImagesFailure.DEX_IMAGE_INVALID
            NativeCloseApkFailure.JNI_SET_INVALID ->
                NativeCloseInstalledImagesFailure.JNI_SET_INVALID
            else -> NativeCloseInstalledImagesFailure.APK_INSPECTION_INVALID
        }

    private fun reject(reason: NativeCloseApkFailure): Nothing = throw ApkRejected(reason)

    private fun rejectImages(reason: NativeCloseInstalledImagesFailure): Nothing =
        throw ImagesRejected(reason)

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        reject(NativeCloseApkFailure.INVALID_LENGTH)
    }

    private fun checkedLongAdd(
        left: Long,
        right: Long,
        failure: NativeCloseApkFailure,
    ): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        reject(failure)
    }

    private fun checkedIntAdd(left: Int, right: Int, failure: NativeCloseApkFailure): Int = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        reject(failure)
    }

    private fun dexOrdinal(name: String): Int {
        if (name == "classes.dex") return 1
        return name.substring(7, name.length - 4).toIntOrNull()
            ?: reject(NativeCloseApkFailure.DEX_IMAGE_INVALID)
    }

    private fun abiBytes(abi: NativeCloseAbi): ByteArray = abi.wireName.toByteArray(Charsets.UTF_8)

    private fun compareBytes(left: ByteArray, right: ByteArray): Int =
        CanonicalManifestCodec.compareUnsigned(left, right)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun u64(bytes: ByteArray, offset: Int): ULong {
        var result = 0uL
        repeat(8) { index ->
            result = result or ((bytes[offset + index].toULong() and 0xffuL) shl (index * 8))
        }
        return result
    }

    private val IMAGE_ENTRY_COMPARATOR = Comparator<ImageEntry> { left, right ->
        val packageComparison = compareBytes(left.packageNameBytes, right.packageNameBytes)
        if (packageComparison != 0) {
            packageComparison
        } else {
            compareBytes(left.entryNameBytes, right.entryNameBytes)
        }
    }

    private const val BASE_OUTPUT_NAME = "base"
    private const val JNI_PREFIX = "lib/"
    private const val CODE_IMAGE_DOMAIN = "native-close-code-image-v1"
    private const val DEX_ENTRY_DOMAIN = "native-close-dex-entry-v1"
    private const val JNI_SET_DOMAIN = "native-close-installed-jni-set-v1"
    private const val JNI_ENTRY_DOMAIN = "native-close-jni-entry-v1"
    private const val EOCD_LENGTH = 22L
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val CENTRAL_HEADER_LENGTH = 46L
    private const val LOCAL_HEADER_LENGTH = 30L
    private const val MAX_ZIP_ENTRY_COUNT = 65_534
    private const val MAX_PACKAGE_OUTPUTS = 129
    private const val MAX_PACKAGE_OUTPUT_NAME_BYTES = 128
    private const val MAX_PACKAGE_SET_ENTRY_COUNT = 65_534L
    private const val MAX_PACKAGE_SET_RETAINED_NAME_BYTES = 4_194_304L
    private const val MAX_PACKAGE_SET_ALL_UNCOMPRESSED_BYTES = 536_870_912L
    private const val MAX_PACKAGE_SET_APK_BYTES = 134_217_728L
    private const val MAX_DEX_ENTRIES = 64
    private const val MAX_JNI_ENTRIES = 256
    private const val MAX_SIGNING_PAIR_COUNT = 65_534
    private const val MAX_ALIGNMENT_PADDING_RECORDS = 256
    private const val MAX_ALIGNMENT_PADDING_BYTES = 8_388_608L
    private const val ALIGNMENT_PADDING_TIME = 0x0821
    private const val ALIGNMENT_PADDING_DATE = 0x0221
    private const val MAX_SOURCE_IDENTITY_BYTES = 4_096
    private const val MAX_CANONICAL_BYTES = 1_048_576
    private const val MAX_ENTRY_BYTES = 268_435_456L
    private const val MAX_IMAGE_BYTES = 536_870_912L
    /** Includes the current ~38 MiB APK while bounding one private snapshot to 64 MiB. */
    private const val MAX_IMMUTABLE_APK_BYTES = 67_108_864L
    private const val UINT32_MAX = 0xffff_ffffL
    private const val DOS_DIRECTORY_BIT = 0x10L
    private const val UNIX_CREATOR_OS = 3
    private const val UNIX_FILE_TYPE_MASK = 0xf000L
    private const val UNIX_DIRECTORY_TYPE = 0x4000L
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8
    private const val STREAM_BUFFER_SIZE = 65_536
    private const val SIGNING_FOOTER_LENGTH = 24L
    private const val MIN_SIGNING_BLOCK_LENGTH = 44L
    private const val MIN_SIGNING_BLOCK_SIZE = 36uL
    private val ALLOWED_FLAGS = setOf(0, 0x0800)
    private val ALLOWED_METHODS = setOf(METHOD_STORED, METHOD_DEFLATED)
    private val SIGNING_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private val DEX_NAME = Regex("^classes(?:[2-9]|[1-9][0-9]+)?[.]dex$")
    private val JNI_NAME =
        Regex("^lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/[A-Za-z0-9._+\\-]+[.]so$")
}
