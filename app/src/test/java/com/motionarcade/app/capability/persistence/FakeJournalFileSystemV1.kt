package com.motionarcade.app.capability.persistence

import java.util.concurrent.atomic.AtomicLong

internal enum class FakeJournalCallKindV1 {
    LSTAT,
    MKDIR,
    OPEN,
    FSTAT,
    READ,
    WRITE,
    FSYNC,
    CLOSE,
    REMOVE,
    RENAME,
    OPEN_DIRECTORY,
    DIRECTORY_ITERATOR,
    DIRECTORY_HAS_NEXT,
    DIRECTORY_NEXT,
    DIRECTORY_CLOSE,
}

internal data class FakeJournalCallV1(
    val kind: FakeJournalCallKindV1,
    val path: String?,
    val occurrence: Int,
)

internal data class FakeJournalOpenV1(
    val path: JournalPathV1,
    val purpose: FakeJournalOpenPurposeV1,
    val mode: Int,
)

internal enum class FakeJournalOpenPurposeV1 {
    READ_ONLY,
    EXCLUSIVE_CREATE,
}

internal sealed interface FakeJournalRuleActionV1 {
    data class FailBefore(val failure: JournalFileSystemFailureV1) : FakeJournalRuleActionV1

    data class FailAfter(val failure: JournalFileSystemFailureV1) : FakeJournalRuleActionV1

    data object CrashAfter : FakeJournalRuleActionV1
}

internal data class FakeJournalRuleV1(
    val kind: FakeJournalCallKindV1,
    val occurrence: Int,
    val path: String? = null,
    val action: FakeJournalRuleActionV1,
)

internal class SimulatedProcessDeathV1 : Error("simulated process death")

/** Process-visible syscall model only; it intentionally makes no power-loss/durability claim. */
internal class FakeJournalDiskV1 {
    internal sealed class Node(
        val device: Long,
        val inode: Long,
    ) {
        class Directory(device: Long, inode: Long) : Node(device, inode)

        class RegularFile(
            device: Long,
            inode: Long,
            bytes: ByteArray,
        ) : Node(device, inode) {
            var bytes: ByteArray = bytes.copyOf()
        }

        class SymbolicLink(device: Long, inode: Long) : Node(device, inode)

        class Other(device: Long, inode: Long) : Node(device, inode)
    }

    internal val nodes = LinkedHashMap<String, Node>()
    private val nextInode = AtomicLong(10L)

    fun putDirectory(path: JournalPathV1) {
        nodes[path.value] = Node.Directory(1L, nextInode.getAndIncrement())
    }

    fun putFile(
        path: JournalPathV1,
        bytes: ByteArray,
    ) {
        nodes[path.value] = Node.RegularFile(1L, nextInode.getAndIncrement(), bytes)
    }

    fun putSymbolicLink(path: JournalPathV1) {
        nodes[path.value] = Node.SymbolicLink(1L, nextInode.getAndIncrement())
    }

    fun putOther(path: JournalPathV1) {
        nodes[path.value] = Node.Other(1L, nextInode.getAndIncrement())
    }

    fun bytes(path: JournalPathV1): ByteArray? =
        (nodes[path.value] as? Node.RegularFile)?.bytes?.copyOf()

    fun append(
        path: JournalPathV1,
        bytes: ByteArray,
    ) {
        val node = nodes[path.value] as? Node.RegularFile ?: error("not a regular file")
        node.bytes += bytes
    }

    internal fun newFile(bytes: ByteArray = ByteArray(0)): Node.RegularFile =
        Node.RegularFile(1L, nextInode.getAndIncrement(), bytes)

    internal fun newDirectory(): Node.Directory =
        Node.Directory(1L, nextInode.getAndIncrement())
}

internal class FakeJournalFileSystemV1(
    private val disk: FakeJournalDiskV1,
    override val providerKey: JournalProviderKeyV1 = JournalProviderKeyV1("fake-posix"),
) : JournalFileSystemV1 {
    private data class Handle(
        val id: Long,
        val path: JournalPathV1,
        val node: FakeJournalDiskV1.Node,
        val writable: Boolean,
        var offset: Int = 0,
        var closed: Boolean = false,
    ) : JournalFileHandleV1

    val calls = mutableListOf<FakeJournalCallV1>()
    val opens = mutableListOf<FakeJournalOpenV1>()
    val rules = mutableListOf<FakeJournalRuleV1>()
    var maximumReadChunk: Int = Int.MAX_VALUE
    var maximumWriteChunk: Int = Int.MAX_VALUE
    var forceZeroReadAtCall: Int? = null
    var forceZeroWriteAtCall: Int? = null
    var returnExtraByteAtEof: Boolean = false
    var mutateLengthAfterPayloadRead: Boolean = false
    var directoryEntriesOverride: List<JournalDirectoryEntryV1>? = null
    var beforeCallObserver: ((FakeJournalCallV1) -> Unit)? = null
    var afterCallObserver: ((FakeJournalCallV1) -> Unit)? = null
    private val occurrences = mutableMapOf<FakeJournalCallKindV1, Int>()
    private val handles = mutableMapOf<Long, Handle>()
    private var nextHandle = 1L
    private var payloadReadMutationDone = false

    fun restart(): FakeJournalFileSystemV1 = FakeJournalFileSystemV1(disk, providerKey)

    fun addRule(rule: FakeJournalRuleV1) {
        rules += rule
    }

    override fun lstat(path: JournalPathV1): JournalStatV1 {
        val call = before(FakeJournalCallKindV1.LSTAT, path)
        val node = disk.nodes[path.value]
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.NOT_FOUND)
        val result = stat(node)
        after(call)
        return result
    }

    override fun mkdir(path: JournalPathV1, mode: Int) {
        require(mode == JOURNAL_DIRECTORY_MODE)
        val call = before(FakeJournalCallKindV1.MKDIR, path)
        if (disk.nodes.containsKey(path.value)) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.ALREADY_EXISTS)
        }
        disk.nodes[path.value] = disk.newDirectory()
        after(call)
    }

    override fun openReadOnly(path: JournalPathV1): JournalFileHandleV1 =
        open(path, FakeJournalOpenPurposeV1.READ_ONLY, 0)

    override fun openExclusiveCreate(
        path: JournalPathV1,
        mode: Int,
    ): JournalFileHandleV1 =
        open(path, FakeJournalOpenPurposeV1.EXCLUSIVE_CREATE, mode)

    private fun open(
        path: JournalPathV1,
        purpose: FakeJournalOpenPurposeV1,
        mode: Int,
    ): JournalFileHandleV1 {
        val call = before(FakeJournalCallKindV1.OPEN, path)
        opens += FakeJournalOpenV1(path, purpose, mode)
        val create = purpose == FakeJournalOpenPurposeV1.EXCLUSIVE_CREATE
        var node = disk.nodes[path.value]
        if (create) {
            require(mode == JOURNAL_FILE_MODE)
            if (node != null) {
                throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.ALREADY_EXISTS)
            }
            node = disk.newFile()
            disk.nodes[path.value] = node
        } else {
            require(mode == 0)
            if (node == null) throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.NOT_FOUND)
        }
        if (node is FakeJournalDiskV1.Node.SymbolicLink) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.SECURITY)
        }
        val handle = Handle(
            id = nextHandle++,
            path = path,
            node = checkNotNull(node),
            writable = create,
        )
        handles[handle.id] = handle
        after(call)
        return handle
    }

    override fun fstat(handle: JournalFileHandleV1): JournalStatV1 {
        val real = checked(handle)
        val call = before(FakeJournalCallKindV1.FSTAT, real.path)
        val result = stat(real.node)
        after(call)
        return result
    }

    override fun read(
        handle: JournalFileHandleV1,
        destination: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int {
        val real = checked(handle)
        require(!real.writable)
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= destination.size)
        val call = before(FakeJournalCallKindV1.READ, real.path)
        if (forceZeroReadAtCall == call.occurrence) return 0
        val node = real.node as? FakeJournalDiskV1.Node.RegularFile
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        val available = node.bytes.size - real.offset
        val result = if (available <= 0) {
            if (returnExtraByteAtEof && byteCount > 0) {
                destination[offset] = 0x5a
                1
            } else {
                0
            }
        } else {
            val count = minOf(available, byteCount, maximumReadChunk)
            node.bytes.copyInto(destination, offset, real.offset, real.offset + count)
            real.offset += count
            if (mutateLengthAfterPayloadRead && real.offset == node.bytes.size && !payloadReadMutationDone) {
                payloadReadMutationDone = true
                node.bytes += byteArrayOf(0x33)
            }
            count
        }
        after(call)
        return result
    }

    override fun write(
        handle: JournalFileHandleV1,
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int {
        val real = checked(handle)
        require(real.writable)
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= source.size)
        val call = before(FakeJournalCallKindV1.WRITE, real.path)
        if (forceZeroWriteAtCall == call.occurrence) return 0
        val node = real.node as? FakeJournalDiskV1.Node.RegularFile
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        val count = minOf(byteCount, maximumWriteChunk)
        val required = real.offset + count
        if (node.bytes.size < required) node.bytes = node.bytes.copyOf(required)
        source.copyInto(node.bytes, real.offset, offset, offset + count)
        real.offset += count
        after(call)
        return count
    }

    override fun fsync(handle: JournalFileHandleV1) {
        val real = checked(handle)
        val call = before(FakeJournalCallKindV1.FSYNC, real.path)
        after(call)
    }

    override fun close(handle: JournalFileHandleV1) {
        val real = handle as? Handle
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        val call = before(FakeJournalCallKindV1.CLOSE, real.path)
        if (real.closed) throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        real.closed = true
        handles.remove(real.id)
        after(call)
    }

    override fun remove(path: JournalPathV1) {
        val call = before(FakeJournalCallKindV1.REMOVE, path)
        if (disk.nodes.remove(path.value) == null) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.NOT_FOUND)
        }
        after(call)
    }

    override fun rename(source: JournalPathV1, target: JournalPathV1) {
        val call = before(FakeJournalCallKindV1.RENAME, source)
        val sourceNode = disk.nodes.remove(source.value)
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.NOT_FOUND)
        disk.nodes[target.value] = sourceNode
        after(call)
    }

    override fun openDirectory(path: JournalPathV1): JournalDirectoryStreamV1 {
        val call = before(FakeJournalCallKindV1.OPEN_DIRECTORY, path)
        val node = disk.nodes[path.value]
        if (node !is FakeJournalDiskV1.Node.Directory) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        }
        val entries = directoryEntriesOverride ?: directChildren(path)
        after(call)
        return object : JournalDirectoryStreamV1 {
            private var iteratorIssued = false
            private var closed = false

            override fun iterator(): JournalDirectoryIteratorV1 {
                check(!iteratorIssued)
                iteratorIssued = true
                val iteratorCall = before(FakeJournalCallKindV1.DIRECTORY_ITERATOR, path)
                after(iteratorCall)
                return object : JournalDirectoryIteratorV1 {
                    private var index = 0

                    override fun hasNext(): Boolean {
                        val hasNextCall = before(FakeJournalCallKindV1.DIRECTORY_HAS_NEXT, path)
                        val result = index < entries.size
                        after(hasNextCall)
                        return result
                    }

                    override fun next(): JournalDirectoryEntryV1 {
                        val nextCall = before(FakeJournalCallKindV1.DIRECTORY_NEXT, path)
                        if (index >= entries.size) {
                            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
                        }
                        val result = entries[index++]
                        after(nextCall)
                        return result
                    }
                }
            }

            override fun close() {
                val closeCall = before(FakeJournalCallKindV1.DIRECTORY_CLOSE, path)
                check(!closed)
                closed = true
                after(closeCall)
            }
        }
    }

    private fun directChildren(parent: JournalPathV1): List<JournalDirectoryEntryV1> {
        val prefix = if (parent.value == "/") "/" else "${parent.value}/"
        return disk.nodes.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && !it.contains('/') }
            .sorted()
            .map { name -> JournalDirectoryEntryV1(providerKey, parent, name) }
            .toList()
    }

    private fun checked(handle: JournalFileHandleV1): Handle {
        val real = handle as? Handle
            ?: throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        if (real.closed || handles[real.id] !== real) {
            throw JournalFileSystemExceptionV1(JournalFileSystemFailureV1.IO)
        }
        return real
    }

    private fun stat(node: FakeJournalDiskV1.Node): JournalStatV1 =
        JournalStatV1(
            device = node.device,
            inode = node.inode,
            type = when (node) {
                is FakeJournalDiskV1.Node.Directory -> JournalNodeTypeV1.DIRECTORY
                is FakeJournalDiskV1.Node.RegularFile -> JournalNodeTypeV1.REGULAR_FILE
                is FakeJournalDiskV1.Node.SymbolicLink -> JournalNodeTypeV1.SYMBOLIC_LINK
                is FakeJournalDiskV1.Node.Other -> JournalNodeTypeV1.OTHER
            },
            size = (node as? FakeJournalDiskV1.Node.RegularFile)?.bytes?.size?.toLong() ?: 0L,
        )

    private fun before(
        kind: FakeJournalCallKindV1,
        path: JournalPathV1,
    ): FakeJournalCallV1 {
        val occurrence = (occurrences[kind] ?: 0) + 1
        occurrences[kind] = occurrence
        val call = FakeJournalCallV1(kind, path.value, occurrence)
        calls += call
        beforeCallObserver?.invoke(call)
        val rule = rules.firstOrNull {
            it.kind == kind && (it.occurrence <= 0 || it.occurrence == occurrence) &&
                (it.path == null || it.path == path.value)
        }
        val action = rule?.action
        if (action is FakeJournalRuleActionV1.FailBefore) {
            throw JournalFileSystemExceptionV1(action.failure)
        }
        return call
    }

    private fun after(call: FakeJournalCallV1) {
        afterCallObserver?.invoke(call)
        val action = rules.firstOrNull {
            it.kind == call.kind && (it.occurrence <= 0 || it.occurrence == call.occurrence) &&
                (it.path == null || it.path == call.path)
        }?.action
        when (action) {
            is FakeJournalRuleActionV1.FailAfter ->
                throw JournalFileSystemExceptionV1(action.failure)
            FakeJournalRuleActionV1.CrashAfter -> throw SimulatedProcessDeathV1()
            else -> Unit
        }
    }
}

internal class MutableJournalClockV1(var value: Long) : JournalMonotonicClockV1 {
    override fun nowNs(): Long = value
}
