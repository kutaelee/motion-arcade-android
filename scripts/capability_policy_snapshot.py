"""Immutable, handle-anchored repository snapshots for capability policy linting.

The validator-facing API deliberately accepts repository-relative *names* but
never returns paths for governed inputs.  A caller must parse, hash, and lint
the returned bytes.  This keeps path traversal and filesystem time-of-check /
time-of-use concerns in this small, independently tested boundary.

The implementation fails closed when the operating system cannot provide the
required non-following, handle-relative primitives.  Windows directory anchors
are opened without ``FILE_SHARE_DELETE``; POSIX paths are walked with ``openat``
semantics (``dir_fd`` plus ``O_NOFOLLOW``).  Every OS handle is closed before a
snapshot is returned.
"""

from __future__ import annotations

from dataclasses import dataclass
import errno
import os
from pathlib import Path
import re
import stat
import subprocess
from types import MappingProxyType
from typing import Callable, Mapping, Sequence
import unicodedata


DEFAULT_MAX_FILE_BYTES = 4 * 1024 * 1024


class SnapshotError(RuntimeError):
    """A complete repository snapshot could not be captured safely."""


@dataclass(frozen=True, slots=True)
class RepositorySnapshot:
    """A closed, immutable view of every governed repository input."""

    canonical_root: Path
    files: Mapping[str, bytes]
    review_names: tuple[str, ...]
    governed_inputs: tuple[str, ...]
    source: str = "worktree"
    commit: str | None = None
    tree: str | None = None


# Tests may replace this module-private hook.  It is intentionally absent from
# the public API, and it runs only after every required file and directory
# anchor is held.  Production validator callers have no argument with which to
# inject work at this boundary.
_TEST_HOOK: Callable[[], None] | None = None


_DRIVE_PREFIX = re.compile(r"^[A-Za-z]:")
_WINDOWS_RESERVED = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{number}" for number in range(1, 10)),
    *(f"LPT{number}" for number in range(1, 10)),
}


def _portable_name_key(value: str) -> str:
    """Return the cross-platform collision key for one path or entry name."""

    return unicodedata.normalize("NFKC", value).casefold()


def _normalize_relative(value: str, *, label: str) -> str:
    if not isinstance(value, str):
        raise SnapshotError(f"{label} must be a string")
    if not value or "\x00" in value:
        raise SnapshotError(f"{label} is empty or contains NUL")
    if value.startswith(("/", "\\")) or _DRIVE_PREFIX.match(value):
        raise SnapshotError(f"{label} must be repository-relative: {value!r}")
    if "\\" in value:
        raise SnapshotError(f"{label} must use canonical '/' separators: {value!r}")

    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise SnapshotError(f"{label} contains an empty, '.' or '..' component: {value!r}")
    for part in parts:
        if ":" in part:
            raise SnapshotError(f"{label} contains a drive or stream component: {value!r}")
        if part.endswith((" ", ".")):
            raise SnapshotError(f"{label} contains a non-canonical component: {value!r}")
        stem = part.split(".", 1)[0].upper()
        if stem in _WINDOWS_RESERVED:
            raise SnapshotError(f"{label} contains a reserved device component: {value!r}")
    return "/".join(parts)


def _prepare_inputs(
    required_files: Sequence[str], review_dir: str, forbidden_paths: Sequence[str]
) -> tuple[tuple[str, ...], str, tuple[str, ...]]:
    if isinstance(required_files, (str, bytes)) or isinstance(forbidden_paths, (str, bytes)):
        raise SnapshotError("snapshot inventories must be sequences of path strings")
    try:
        required_source = tuple(required_files)
        forbidden_source = tuple(forbidden_paths)
    except Exception as exc:
        raise SnapshotError("snapshot path inventories must be finite sequences") from exc

    if not required_source:
        raise SnapshotError("required_files must not be empty")
    required = tuple(
        _normalize_relative(value, label="required file") for value in required_source
    )
    forbidden = tuple(
        _normalize_relative(value, label="forbidden path") for value in forbidden_source
    )
    reviews = _normalize_relative(review_dir, label="review directory")

    # Windows repositories are case-insensitive by default.  Reject aliases on
    # every platform so a snapshot inventory has one portable interpretation.
    required_folded = [_portable_name_key(value) for value in required]
    forbidden_folded = [_portable_name_key(value) for value in forbidden]
    if len(set(required_folded)) != len(required_folded):
        raise SnapshotError("required_files contains a duplicate or case alias")
    if len(set(forbidden_folded)) != len(forbidden_folded):
        raise SnapshotError("forbidden_paths contains a duplicate or case alias")
    overlap = set(required_folded) & set(forbidden_folded)
    if overlap:
        raise SnapshotError("a governed input cannot also be forbidden")

    # Sorting is part of the serialized contract.  It also copies caller-owned
    # mutable sequences into an independent exact inventory.
    return tuple(sorted(required)), reviews, tuple(sorted(forbidden))


def _validate_root_lexically(root: Path) -> Path:
    try:
        candidate = Path(root)
    except Exception as exc:
        raise SnapshotError("repository root is not path-like") from exc
    if not candidate.is_absolute():
        raise SnapshotError("repository root must be absolute")

    raw = os.fspath(candidate)
    if os.name == "nt":
        normalized_slashes = raw.replace("/", "\\")
        if normalized_slashes.startswith(("\\\\?\\", "\\\\.\\", "\\??\\")):
            raise SnapshotError("Windows device namespace roots are not supported")
        if normalized_slashes.startswith("\\\\"):
            raise SnapshotError("UNC repository roots are not supported")
        drive, tail = os.path.splitdrive(normalized_slashes)
        if not drive or not tail.startswith("\\"):
            raise SnapshotError("repository root must be a drive-absolute Windows path")
        if any(part == ".." for part in tail.split("\\")):
            raise SnapshotError("repository root must not contain '..'")
        return Path(os.path.normpath(normalized_slashes))

    if any(part == ".." for part in candidate.parts):
        raise SnapshotError("repository root must not contain '..'")
    return Path(os.path.normpath(raw))


class _SnapshotBackend:
    def capture(
        self,
        root: Path,
        required: tuple[str, ...],
        review_dir: str,
        forbidden: tuple[str, ...],
        max_file_bytes: int,
    ) -> tuple[Path, dict[str, bytes], tuple[str, ...]]:
        raise NotImplementedError


if os.name == "nt":
    import ctypes
    from ctypes import wintypes
    import ntpath

    _INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
    _GENERIC_READ = 0x80000000
    _FILE_LIST_DIRECTORY = 0x0001
    _FILE_READ_ATTRIBUTES = 0x0080
    _FILE_SHARE_READ = 0x00000001
    _FILE_SHARE_WRITE = 0x00000002
    _OPEN_EXISTING = 3
    _FILE_ATTRIBUTE_DIRECTORY = 0x00000010
    _FILE_ATTRIBUTE_DEVICE = 0x00000040
    _FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400
    _FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    _FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    _FILE_FLAG_SEQUENTIAL_SCAN = 0x08000000
    _FILE_TYPE_DISK = 0x0001
    _FILE_ID_BOTH_DIRECTORY_INFO = 10
    _ERROR_FILE_NOT_FOUND = 2
    _ERROR_PATH_NOT_FOUND = 3
    _ERROR_NO_MORE_FILES = 18

    class _BY_HANDLE_FILE_INFORMATION(ctypes.Structure):
        _fields_ = [
            ("dwFileAttributes", wintypes.DWORD),
            ("ftCreationTime", wintypes.FILETIME),
            ("ftLastAccessTime", wintypes.FILETIME),
            ("ftLastWriteTime", wintypes.FILETIME),
            ("dwVolumeSerialNumber", wintypes.DWORD),
            ("nFileSizeHigh", wintypes.DWORD),
            ("nFileSizeLow", wintypes.DWORD),
            ("nNumberOfLinks", wintypes.DWORD),
            ("nFileIndexHigh", wintypes.DWORD),
            ("nFileIndexLow", wintypes.DWORD),
        ]

    class _FILE_ID_BOTH_DIR_INFO_HEADER(ctypes.Structure):
        _fields_ = [
            ("NextEntryOffset", wintypes.DWORD),
            ("FileIndex", wintypes.DWORD),
            ("CreationTime", ctypes.c_longlong),
            ("LastAccessTime", ctypes.c_longlong),
            ("LastWriteTime", ctypes.c_longlong),
            ("ChangeTime", ctypes.c_longlong),
            ("EndOfFile", ctypes.c_longlong),
            ("AllocationSize", ctypes.c_longlong),
            ("FileAttributes", wintypes.DWORD),
            ("FileNameLength", wintypes.DWORD),
            ("EaSize", wintypes.DWORD),
            ("ShortNameLength", ctypes.c_byte),
            ("ShortName", wintypes.WCHAR * 12),
            ("FileId", ctypes.c_longlong),
        ]

    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _CreateFileW = _kernel32.CreateFileW
    _CreateFileW.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    _CreateFileW.restype = wintypes.HANDLE
    _CloseHandle = _kernel32.CloseHandle
    _CloseHandle.argtypes = (wintypes.HANDLE,)
    _CloseHandle.restype = wintypes.BOOL
    _GetFileInformationByHandle = _kernel32.GetFileInformationByHandle
    _GetFileInformationByHandle.argtypes = (
        wintypes.HANDLE,
        ctypes.POINTER(_BY_HANDLE_FILE_INFORMATION),
    )
    _GetFileInformationByHandle.restype = wintypes.BOOL
    _GetFinalPathNameByHandleW = _kernel32.GetFinalPathNameByHandleW
    _GetFinalPathNameByHandleW.argtypes = (
        wintypes.HANDLE,
        wintypes.LPWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
    )
    _GetFinalPathNameByHandleW.restype = wintypes.DWORD
    _GetFileType = _kernel32.GetFileType
    _GetFileType.argtypes = (wintypes.HANDLE,)
    _GetFileType.restype = wintypes.DWORD
    _ReadFile = _kernel32.ReadFile
    _ReadFile.argtypes = (
        wintypes.HANDLE,
        wintypes.LPVOID,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        wintypes.LPVOID,
    )
    _ReadFile.restype = wintypes.BOOL
    _GetFileInformationByHandleEx = _kernel32.GetFileInformationByHandleEx
    _GetFileInformationByHandleEx.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        wintypes.LPVOID,
        wintypes.DWORD,
    )
    _GetFileInformationByHandleEx.restype = wintypes.BOOL

    def _windows_error(operation: str, path: str) -> OSError:
        code = ctypes.get_last_error()
        if code in {_ERROR_FILE_NOT_FOUND, _ERROR_PATH_NOT_FOUND}:
            return FileNotFoundError(code, f"{operation}: {path}", path)
        return OSError(code, f"{operation}: {path}", path)

    def _strip_extended_prefix(path: str) -> str:
        if path.startswith("\\\\?\\UNC\\"):
            return "\\\\" + path[8:]
        if path.startswith("\\\\?\\"):
            return path[4:]
        return path

    class _WindowsBackend(_SnapshotBackend):
        def __init__(self) -> None:
            self._handles: list[int] = []
            self._directories: dict[str, int] = {}

        def _close_all(self) -> None:
            close_errors: list[int] = []
            while self._handles:
                handle = self._handles.pop()
                if not _CloseHandle(handle):
                    close_errors.append(ctypes.get_last_error())
            self._directories.clear()
            if close_errors:
                raise SnapshotError(f"failed to close Windows snapshot handle(s): {close_errors}")

        def _create_handle(
            self, path: str, *, directory: bool, missing_ok: bool = False
        ) -> int:
            flags = _FILE_FLAG_OPEN_REPARSE_POINT
            access = _FILE_READ_ATTRIBUTES
            share = _FILE_SHARE_READ | _FILE_SHARE_WRITE
            if directory:
                flags |= _FILE_FLAG_BACKUP_SEMANTICS
                access |= _FILE_LIST_DIRECTORY
            else:
                flags |= _FILE_FLAG_SEQUENTIAL_SCAN
                access |= _GENERIC_READ
                # A held governed file cannot be replaced or modified through a
                # newly opened writer before its bytes have been captured.
                share = _FILE_SHARE_READ
            handle = _CreateFileW(path, access, share, None, _OPEN_EXISTING, flags, None)
            if handle == _INVALID_HANDLE_VALUE:
                error = _windows_error("CreateFileW failed", path)
                if missing_ok and isinstance(error, FileNotFoundError):
                    raise error
                raise error
            value = int(handle)
            self._handles.append(value)
            return value

        @staticmethod
        def _information(handle: int, path: str) -> _BY_HANDLE_FILE_INFORMATION:
            info = _BY_HANDLE_FILE_INFORMATION()
            if not _GetFileInformationByHandle(handle, ctypes.byref(info)):
                raise _windows_error("GetFileInformationByHandle failed", path)
            return info

        @staticmethod
        def _identity(info: _BY_HANDLE_FILE_INFORMATION) -> tuple[int, int, int, int, int]:
            return (
                int(info.dwVolumeSerialNumber),
                (int(info.nFileIndexHigh) << 32) | int(info.nFileIndexLow),
                (int(info.nFileSizeHigh) << 32) | int(info.nFileSizeLow),
                int(info.ftLastWriteTime.dwHighDateTime),
                int(info.ftLastWriteTime.dwLowDateTime),
            )

        @staticmethod
        def _final_path(handle: int, path: str) -> str:
            size = 512
            while size <= 32768:
                buffer = ctypes.create_unicode_buffer(size)
                length = _GetFinalPathNameByHandleW(handle, buffer, size, 0)
                if length == 0:
                    raise _windows_error("GetFinalPathNameByHandleW failed", path)
                if length < size:
                    return ntpath.normpath(_strip_extended_prefix(buffer.value))
                size = int(length) + 1
            raise SnapshotError(f"final handle path is unreasonably long: {path}")

        def _inspect_directory(self, handle: int, path: str) -> None:
            info = self._information(handle, path)
            if info.dwFileAttributes & _FILE_ATTRIBUTE_REPARSE_POINT:
                raise SnapshotError(f"directory is a symlink/junction/reparse point: {path}")
            if not info.dwFileAttributes & _FILE_ATTRIBUTE_DIRECTORY:
                raise SnapshotError(f"directory anchor is not a directory: {path}")

        def _open_root_chain(self, root: Path) -> tuple[str, int]:
            raw = ntpath.normpath(os.fspath(root))
            drive, tail = ntpath.splitdrive(raw)
            current = drive + "\\"
            handle = self._create_handle(current, directory=True)
            self._inspect_directory(handle, current)
            self._directories[ntpath.normcase(current)] = handle
            for component in [part for part in tail.split("\\") if part]:
                current = ntpath.join(current, component)
                handle = self._create_handle(current, directory=True)
                self._inspect_directory(handle, current)
                self._directories[ntpath.normcase(current)] = handle
            canonical = self._final_path(handle, raw)
            return canonical, handle

        def _open_relative_directory(self, canonical_root: str, relative: str) -> int:
            current = canonical_root
            current_handle = self._directories[ntpath.normcase(current)]
            if not relative:
                return current_handle
            for component in relative.split("/"):
                current = ntpath.join(current, component)
                key = ntpath.normcase(current)
                cached = self._directories.get(key)
                if cached is None:
                    cached = self._create_handle(current, directory=True)
                    self._inspect_directory(cached, current)
                    final = self._final_path(cached, current)
                    self._verify_under_root(canonical_root, final, current)
                    self._directories[key] = cached
                current_handle = cached
            return current_handle

        def _reopen_relative_directory(self, canonical_root: str, relative: str) -> int:
            """Open a fresh enumeration cursor for an already anchored directory."""

            absolute = ntpath.join(canonical_root, *relative.split("/"))
            handle = self._create_handle(absolute, directory=True)
            self._inspect_directory(handle, absolute)
            final = self._final_path(handle, absolute)
            self._verify_under_root(canonical_root, final, relative)
            return handle

        @staticmethod
        def _verify_under_root(canonical_root: str, final: str, display: str) -> None:
            try:
                common = ntpath.commonpath([canonical_root, final])
            except ValueError as exc:
                raise SnapshotError(f"opened handle escaped repository root: {display}") from exc
            if ntpath.normcase(common) != ntpath.normcase(canonical_root):
                raise SnapshotError(f"opened handle escaped repository root: {display}")

        def _open_required_file(
            self, canonical_root: str, relative: str, max_file_bytes: int
        ) -> tuple[int, tuple[int, int, int, int, int]]:
            parent, name = relative.rsplit("/", 1) if "/" in relative else ("", relative)
            self._open_relative_directory(canonical_root, parent)
            absolute = ntpath.join(canonical_root, *relative.split("/"))
            handle = self._create_handle(absolute, directory=False)
            info = self._information(handle, absolute)
            attrs = int(info.dwFileAttributes)
            if attrs & _FILE_ATTRIBUTE_REPARSE_POINT:
                raise SnapshotError(f"required file is a symlink/reparse point: {relative}")
            if attrs & (_FILE_ATTRIBUTE_DIRECTORY | _FILE_ATTRIBUTE_DEVICE):
                raise SnapshotError(f"required input is not a regular file: {relative}")
            if _GetFileType(handle) != _FILE_TYPE_DISK:
                raise SnapshotError(f"required input is not a disk file: {relative}")
            size = (int(info.nFileSizeHigh) << 32) | int(info.nFileSizeLow)
            if size > max_file_bytes:
                raise SnapshotError(
                    f"required file exceeds max_file_bytes ({size} > {max_file_bytes}): {relative}"
                )
            final = self._final_path(handle, absolute)
            self._verify_under_root(canonical_root, final, relative)
            return handle, self._identity(info)

        @staticmethod
        def _read_exact(handle: int, expected_size: int, relative: str) -> bytes:
            try:
                data = bytearray(expected_size)
            except MemoryError as exc:
                raise SnapshotError(f"cannot allocate governed file buffer: {relative}") from exc
            offset = 0
            while offset < expected_size:
                chunk = min(expected_size - offset, 1024 * 1024)
                buffer = (ctypes.c_char * chunk).from_buffer(data, offset)
                read = wintypes.DWORD()
                if not _ReadFile(handle, buffer, chunk, ctypes.byref(read), None):
                    raise _windows_error("ReadFile failed", relative)
                if read.value == 0:
                    raise SnapshotError(f"short read while capturing governed file: {relative}")
                offset += int(read.value)

            extra = ctypes.create_string_buffer(1)
            read = wintypes.DWORD()
            if not _ReadFile(handle, extra, 1, ctypes.byref(read), None):
                raise _windows_error("ReadFile EOF check failed", relative)
            if read.value:
                raise SnapshotError(f"governed file grew while being captured: {relative}")
            return bytes(data)

        @staticmethod
        def _enumerate_directory(handle: int, relative: str) -> tuple[str, ...]:
            names: list[str] = []
            buffer_size = 64 * 1024
            buffer = ctypes.create_string_buffer(buffer_size)
            header_size = ctypes.sizeof(_FILE_ID_BOTH_DIR_INFO_HEADER)
            while True:
                if not _GetFileInformationByHandleEx(
                    handle,
                    _FILE_ID_BOTH_DIRECTORY_INFO,
                    buffer,
                    buffer_size,
                ):
                    code = ctypes.get_last_error()
                    if code == _ERROR_NO_MORE_FILES:
                        break
                    raise _windows_error("directory enumeration failed", relative)
                offset = 0
                while True:
                    if offset + header_size > buffer_size:
                        raise SnapshotError(f"malformed directory enumeration: {relative}")
                    header = _FILE_ID_BOTH_DIR_INFO_HEADER.from_buffer(buffer, offset)
                    length = int(header.FileNameLength)
                    if length % 2 or offset + header_size + length > buffer_size:
                        raise SnapshotError(f"malformed directory entry: {relative}")
                    raw = ctypes.string_at(
                        ctypes.addressof(buffer) + offset + header_size, length
                    )
                    name = raw.decode("utf-16-le", errors="strict")
                    if name not in {".", ".."}:
                        names.append(name)
                    next_offset = int(header.NextEntryOffset)
                    if next_offset == 0:
                        break
                    if next_offset < header_size or offset + next_offset >= buffer_size:
                        raise SnapshotError(f"malformed directory chain: {relative}")
                    offset += next_offset
            if len(names) != len(set(_portable_name_key(name) for name in names)):
                raise SnapshotError(f"directory enumeration contains aliases: {relative}")
            return tuple(sorted(names))

        def _forbidden_absent(self, canonical_root: str, relative: str) -> None:
            parts = relative.split("/")
            current_relative = ""
            for component in parts[:-1]:
                current_relative = (
                    f"{current_relative}/{component}" if current_relative else component
                )
                try:
                    self._open_relative_directory(canonical_root, current_relative)
                except FileNotFoundError:
                    return
            parent = "/".join(parts[:-1])
            parent_handle = self._reopen_relative_directory(canonical_root, parent)
            target_key = _portable_name_key(parts[-1])
            for name in self._enumerate_directory(parent_handle, parent or "."):
                if _portable_name_key(name) == target_key:
                    raise SnapshotError(
                        f"forbidden path or portable filename alias exists: {relative}"
                    )

        def capture(
            self,
            root: Path,
            required: tuple[str, ...],
            review_dir: str,
            forbidden: tuple[str, ...],
            max_file_bytes: int,
        ) -> tuple[Path, dict[str, bytes], tuple[str, ...]]:
            try:
                canonical_root, _ = self._open_root_chain(root)
                review_handle = self._open_relative_directory(canonical_root, review_dir)
                file_handles: dict[str, tuple[int, tuple[int, int, int, int, int]]] = {}
                for relative in required:
                    file_handles[relative] = self._open_required_file(
                        canonical_root, relative, max_file_bytes
                    )
                for relative in forbidden:
                    self._forbidden_absent(canonical_root, relative)

                if _TEST_HOOK is not None:
                    _TEST_HOOK()

                review_names = self._enumerate_directory(review_handle, review_dir)
                captured: dict[str, bytes] = {}
                for relative, (handle, identity) in file_handles.items():
                    size = identity[2]
                    data = self._read_exact(handle, size, relative)
                    if len(data) != size:
                        raise SnapshotError(f"short read while capturing governed file: {relative}")
                    captured[relative] = data
                    after = self._information(handle, relative)
                    if self._identity(after) != identity:
                        raise SnapshotError(f"governed file changed during capture: {relative}")

                # Recheck absence after bytes are captured.  A directory query
                # cursor cannot be restarted through GetFileInformationByHandleEx,
                # so open a second handle to the still-anchored directory and prove
                # the exact review-name set did not change during capture.
                for relative in forbidden:
                    self._forbidden_absent(canonical_root, relative)
                verification_handle = self._reopen_relative_directory(
                    canonical_root, review_dir
                )
                if self._enumerate_directory(verification_handle, review_dir) != review_names:
                    raise SnapshotError("review directory changed during capture")
                return Path(canonical_root), captured, review_names
            finally:
                self._close_all()


else:
    import fcntl
    import signal

    class _PosixBackend(_SnapshotBackend):
        def __init__(self) -> None:
            self._fds: list[int] = []
            self._directories: dict[tuple[str, ...], int] = {}
            self._lease_break_observed = False
            self._previous_sigio_handler: object | None = None

        def _install_lease_signal_handler(self) -> None:
            required = ("F_GETLEASE", "F_RDLCK", "F_SETLEASE", "F_UNLCK")
            if not all(hasattr(fcntl, name) for name in required):
                raise SnapshotError("Linux file leases are unavailable")
            if not hasattr(signal, "SIGIO"):
                raise SnapshotError("SIGIO lease-break signaling is unavailable")
            try:
                self._previous_sigio_handler = signal.getsignal(signal.SIGIO)

                def observe_break(_signum: int, _frame: object) -> None:
                    self._lease_break_observed = True

                signal.signal(signal.SIGIO, observe_break)
            except (OSError, RuntimeError, ValueError) as exc:
                raise SnapshotError("cannot install the file-lease break handler") from exc

        def _restore_lease_signal_handler(self) -> None:
            if self._previous_sigio_handler is None:
                return
            previous = self._previous_sigio_handler
            self._previous_sigio_handler = None
            try:
                signal.signal(signal.SIGIO, previous)
            except (OSError, RuntimeError, ValueError) as exc:
                raise SnapshotError("cannot restore the file-lease break handler") from exc

        def _acquire_read_lease(self, fd: int, relative: str) -> None:
            try:
                fcntl.fcntl(fd, fcntl.F_SETLEASE, fcntl.F_RDLCK)
            except OSError as exc:
                raise SnapshotError(
                    f"cannot acquire exclusive snapshot read lease: {relative}"
                ) from exc

        def _assert_read_lease(self, fd: int, relative: str) -> None:
            try:
                state = fcntl.fcntl(fd, fcntl.F_GETLEASE)
            except OSError as exc:
                raise SnapshotError(f"cannot verify snapshot read lease: {relative}") from exc
            if self._lease_break_observed or state != fcntl.F_RDLCK:
                raise SnapshotError(f"snapshot read lease was broken: {relative}")

        def _close_all(self) -> None:
            close_errors: list[OSError] = []
            while self._fds:
                fd = self._fds.pop()
                try:
                    os.close(fd)
                except OSError as exc:
                    close_errors.append(exc)
            self._directories.clear()
            if close_errors:
                raise SnapshotError("failed to close POSIX snapshot file descriptor(s)")

        def _open(self, name: str, flags: int, *, dir_fd: int | None = None) -> int:
            try:
                fd = os.open(name, flags, dir_fd=dir_fd)
            except OSError:
                raise
            self._fds.append(fd)
            return fd

        @staticmethod
        def _fd_path(fd: int) -> Path:
            proc_path = f"/proc/self/fd/{fd}"
            if not os.path.exists("/proc/self/fd"):
                raise SnapshotError("opened-handle path inspection is unsupported")
            try:
                return Path(os.readlink(proc_path))
            except OSError as exc:
                raise SnapshotError("cannot inspect opened-handle path") from exc

        def _open_root_chain(self, root: Path) -> tuple[Path, int]:
            required_flags = os.O_RDONLY | os.O_DIRECTORY
            if not hasattr(os, "O_NOFOLLOW"):
                raise SnapshotError("O_NOFOLLOW is unavailable")
            required_flags |= os.O_NOFOLLOW
            fd = self._open("/", required_flags)
            self._directories[()] = fd
            parts = tuple(part for part in root.parts if part != "/")
            current: tuple[str, ...] = ()
            for component in parts:
                fd = self._open(component, required_flags, dir_fd=fd)
                metadata = os.fstat(fd)
                if not stat.S_ISDIR(metadata.st_mode):
                    raise SnapshotError(f"repository ancestor is not a directory: {component}")
                current += (component,)
                self._directories[current] = fd
            canonical = self._fd_path(fd)
            # Ancestor descriptors remain held in ``_fds``.  Relative governed
            # lookups must, however, start from the repository descriptor rather
            # than accidentally reusing the filesystem-root cache entry.
            self._directories = {(): fd}
            return canonical, fd

        def _open_relative_directory(self, relative: str) -> int:
            flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
            current: tuple[str, ...] = ()
            fd = self._directories[current]
            if not relative:
                return fd
            for component in relative.split("/"):
                current += (component,)
                cached = self._directories.get(current)
                if cached is None:
                    cached = self._open(component, flags, dir_fd=fd)
                    if not stat.S_ISDIR(os.fstat(cached).st_mode):
                        raise SnapshotError(f"governed ancestor is not a directory: {relative}")
                    self._directories[current] = cached
                fd = cached
            return fd

        def _verify_under_root(self, canonical_root: Path, fd: int, relative: str) -> None:
            final = self._fd_path(fd)
            try:
                final.relative_to(canonical_root)
            except ValueError as exc:
                raise SnapshotError(f"opened handle escaped repository root: {relative}") from exc

        def _open_required_file(
            self, canonical_root: Path, relative: str, max_file_bytes: int
        ) -> tuple[int, tuple[int, int, int, int, int]]:
            parent, name = relative.rsplit("/", 1) if "/" in relative else ("", relative)
            parent_fd = self._open_relative_directory(parent)
            if not hasattr(os, "O_NONBLOCK"):
                raise SnapshotError("O_NONBLOCK is unavailable")
            before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            if not stat.S_ISREG(before.st_mode):
                raise SnapshotError(f"required input is not a regular file: {relative}")
            fd = self._open(
                name,
                os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                dir_fd=parent_fd,
            )
            metadata = os.fstat(fd)
            if not stat.S_ISREG(metadata.st_mode):
                raise SnapshotError(f"required input is not a regular file: {relative}")
            if (metadata.st_dev, metadata.st_ino) != (before.st_dev, before.st_ino):
                raise SnapshotError(f"required input changed while being opened: {relative}")
            self._acquire_read_lease(fd, relative)
            if metadata.st_size > max_file_bytes:
                raise SnapshotError(
                    f"required file exceeds max_file_bytes ({metadata.st_size} > {max_file_bytes}): {relative}"
                )
            self._verify_under_root(canonical_root, fd, relative)
            return fd, (
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_size,
                metadata.st_mtime_ns,
                metadata.st_ctime_ns,
            )

        @staticmethod
        def _read_exact(fd: int, expected_size: int, relative: str) -> bytes:
            try:
                data = bytearray(expected_size)
            except MemoryError as exc:
                raise SnapshotError(f"cannot allocate governed file buffer: {relative}") from exc
            view = memoryview(data)
            offset = 0
            while offset < expected_size:
                chunk = os.read(fd, min(expected_size - offset, 1024 * 1024))
                if not chunk:
                    raise SnapshotError(f"short read while capturing governed file: {relative}")
                view[offset : offset + len(chunk)] = chunk
                offset += len(chunk)
            if os.read(fd, 1):
                raise SnapshotError(f"governed file grew while being captured: {relative}")
            return bytes(data)

        def _forbidden_absent(self, relative: str) -> None:
            parts = relative.split("/")
            try:
                parent_fd = self._open_relative_directory("/".join(parts[:-1]))
            except FileNotFoundError:
                return
            except OSError as exc:
                raise SnapshotError(f"cannot prove forbidden path absent: {relative}") from exc
            try:
                names = tuple(os.listdir(parent_fd))
            except OSError as exc:
                raise SnapshotError(f"cannot enumerate forbidden-path parent: {relative}") from exc
            if len(names) != len(set(_portable_name_key(name) for name in names)):
                raise SnapshotError(
                    f"forbidden-path parent contains portable filename aliases: {relative}"
                )
            target_key = _portable_name_key(parts[-1])
            if any(_portable_name_key(name) == target_key for name in names):
                raise SnapshotError(
                    f"forbidden path or portable filename alias exists: {relative}"
                )

        def capture(
            self,
            root: Path,
            required: tuple[str, ...],
            review_dir: str,
            forbidden: tuple[str, ...],
            max_file_bytes: int,
        ) -> tuple[Path, dict[str, bytes], tuple[str, ...]]:
            try:
                self._install_lease_signal_handler()
                canonical_root, root_handle = self._open_root_chain(root)
                review_handle = self._open_relative_directory(review_dir)
                file_handles: dict[str, tuple[int, tuple[int, int, int, int, int]]] = {}
                for relative in required:
                    file_handles[relative] = self._open_required_file(
                        canonical_root, relative, max_file_bytes
                    )
                for relative in forbidden:
                    self._forbidden_absent(relative)

                if _TEST_HOOK is not None:
                    _TEST_HOOK()

                review_names = tuple(sorted(os.listdir(review_handle)))
                if len(review_names) != len(
                    {_portable_name_key(name) for name in review_names}
                ):
                    raise SnapshotError(
                        "review directory contains portable filename aliases"
                    )
                captured: dict[str, bytes] = {}
                for relative, (fd, identity) in file_handles.items():
                    data = self._read_exact(fd, identity[2], relative)
                    if len(data) != identity[2]:
                        raise SnapshotError(f"short read while capturing governed file: {relative}")
                    try:
                        os.lseek(fd, 0, os.SEEK_SET)
                    except OSError as exc:
                        raise SnapshotError(
                            f"cannot rewind governed regular file: {relative}"
                        ) from exc
                    verification = self._read_exact(fd, identity[2], relative)
                    if verification != data:
                        raise SnapshotError(
                            f"governed file bytes changed during capture: {relative}"
                        )
                    self._assert_read_lease(fd, relative)
                    captured[relative] = data
                    metadata = os.fstat(fd)
                    after = (
                        metadata.st_dev,
                        metadata.st_ino,
                        metadata.st_size,
                        metadata.st_mtime_ns,
                        metadata.st_ctime_ns,
                    )
                    if after != identity:
                        raise SnapshotError(f"governed file changed during capture: {relative}")
                for relative in forbidden:
                    self._forbidden_absent(relative)
                if tuple(sorted(os.listdir(review_handle))) != review_names:
                    raise SnapshotError("review directory changed during capture")
                if self._fd_path(root_handle) != canonical_root:
                    raise SnapshotError("repository root moved during capture")
                return canonical_root, captured, review_names
            finally:
                try:
                    self._close_all()
                finally:
                    self._restore_lease_signal_handler()


def _select_backend() -> _SnapshotBackend:
    if os.name == "nt":
        return _WindowsBackend()
    if os.name == "posix":
        if not all(
            (
                os.open in os.supports_dir_fd,
                os.stat in os.supports_dir_fd,
                os.listdir in os.supports_fd,
            )
        ):
            raise SnapshotError("required POSIX dir_fd/fd primitives are unavailable")
        return _PosixBackend()
    raise SnapshotError(f"unsupported snapshot platform: {os.name}")


_FULL_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_GIT_COMMAND_TIMEOUT_SECONDS = 15
_GIT_TREE_ENTRY = re.compile(
    rb"^(?P<mode>[0-7]{6}) (?P<type>[a-z]+) (?P<oid>[0-9a-f]{40})$"
)
_GIT_ENTRY_KINDS = {
    ("040000", "tree"),
    ("100644", "blob"),
    ("100755", "blob"),
    ("120000", "blob"),
    ("160000", "commit"),
}
_GIT_ENVIRONMENT_REDIRECTS = {
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_CONFIG",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_SYSTEM",
    "GIT_DIR",
    "GIT_INDEX_FILE",
    "GIT_NAMESPACE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_REPLACE_REF_BASE",
    "GIT_SHALLOW_FILE",
    "GIT_WORK_TREE",
}
_GIT_ENVIRONMENT_REDIRECT_PREFIXES = (
    "GIT_CONFIG_KEY_",
    "GIT_CONFIG_VALUE_",
)


def _run_git(root: Path, arguments: Sequence[str]) -> bytes:
    """Run one read-only Git object query with replacement objects disabled."""

    environment = os.environ.copy()
    for name in tuple(environment):
        canonical_name = name.upper()
        if canonical_name in _GIT_ENVIRONMENT_REDIRECTS or canonical_name.startswith(
            _GIT_ENVIRONMENT_REDIRECT_PREFIXES
        ):
            environment.pop(name, None)
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    command = [
        "git",
        "--no-replace-objects",
        "-C",
        os.fspath(root),
        *arguments,
    ]
    try:
        completed = subprocess.run(
            command,
            shell=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=_GIT_COMMAND_TIMEOUT_SECONDS,
            check=False,
            env=environment,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise SnapshotError(f"Git object query could not run: {exc}") from exc
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        if len(detail) > 500:
            detail = detail[:500] + "..."
        suffix = f": {detail}" if detail else ""
        raise SnapshotError(
            f"Git object query failed ({completed.returncode}){suffix}"
        )
    return bytes(completed.stdout)


def _git_sha1(output: bytes, *, label: str) -> str:
    try:
        value = output.decode("ascii", errors="strict").strip()
    except UnicodeDecodeError as exc:
        raise SnapshotError(f"{label} is not ASCII") from exc
    if not _FULL_SHA1.fullmatch(value):
        raise SnapshotError(f"{label} is not one lowercase 40-hex SHA-1")
    return value


def _parse_git_tree(output: bytes) -> dict[str, tuple[str, str, str]]:
    """Parse ``git ls-tree -z`` output without filename quoting or decoding loss."""

    if not output.endswith(b"\x00"):
        raise SnapshotError("Git tree enumeration is not NUL-terminated")
    entries: dict[str, tuple[str, str, str]] = {}
    portable_paths: dict[str, str] = {}
    records = output[:-1].split(b"\x00") if output else []
    for record in records:
        if not record or b"\t" not in record:
            raise SnapshotError("Git tree enumeration contains a malformed record")
        metadata, raw_path = record.split(b"\t", 1)
        match = _GIT_TREE_ENTRY.fullmatch(metadata)
        if match is None:
            raise SnapshotError("Git tree enumeration contains malformed metadata")
        try:
            path = raw_path.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise SnapshotError("Git tree contains a non-UTF-8 path") from exc
        normalized_path = _normalize_relative(path, label="Git tree path")
        if normalized_path != path:
            raise SnapshotError(f"Git tree path is non-canonical: {path!r}")
        if path in entries:
            raise SnapshotError(f"Git tree contains a duplicate path: {path}")

        mode = match.group("mode").decode("ascii")
        object_type = match.group("type").decode("ascii")
        oid = match.group("oid").decode("ascii")
        if (mode, object_type) not in _GIT_ENTRY_KINDS:
            raise SnapshotError(
                f"Git tree contains an unsupported entry kind: {path} "
                f"({mode} {object_type})"
            )
        portable = _portable_name_key(path)
        alias = portable_paths.get(portable)
        if alias is not None:
            raise SnapshotError(
                f"Git tree contains portable whole-path aliases: {alias!r}, {path!r}"
            )
        portable_paths[portable] = path
        entries[path] = (mode, object_type, oid)
    return entries


def _governed_namespace_prefixes(
    required: tuple[str, ...], review_dir: str, forbidden: tuple[str, ...]
) -> tuple[tuple[str, ...], ...]:
    prefixes: set[tuple[str, ...]] = set()
    for path in (*required, *forbidden):
        components = path.split("/")
        for length in range(1, len(components)):
            prefixes.add(tuple(components[:length]))
    review_components = review_dir.split("/")
    for length in range(1, len(review_components) + 1):
        prefixes.add(tuple(review_components[:length]))
    return tuple(sorted(prefixes, key=lambda value: (len(value), value)))


def _reject_git_namespace_aliases(
    entries: Mapping[str, tuple[str, str, str]],
    required: tuple[str, ...],
    review_dir: str,
    forbidden: tuple[str, ...],
) -> None:
    prefixes = _governed_namespace_prefixes(required, review_dir, forbidden)
    canonical_components: dict[str, set[str]] = {}
    for prefix in prefixes:
        for component in prefix:
            canonical_components.setdefault(_portable_name_key(component), set()).add(
                component
            )
    portable_forbidden = {_portable_name_key(path): path for path in forbidden}
    for path, (_, object_type, _) in entries.items():
        portable_path = _portable_name_key(path)
        if portable_path in portable_forbidden:
            raise SnapshotError(
                f"forbidden path exists in Git commit: {path} "
                f"(governed spelling {portable_forbidden[portable_path]})"
            )

        components = tuple(path.split("/"))
        ancestor_components = (
            components if object_type == "tree" else components[:-1]
        )
        for actual_component in ancestor_components:
            governed_spellings = canonical_components.get(
                _portable_name_key(actual_component)
            )
            if governed_spellings is not None and actual_component not in governed_spellings:
                raise SnapshotError(
                    "Git tree contains a portable governed namespace component alias: "
                    f"{actual_component!r} aliases {sorted(governed_spellings)!r}"
                )
        for canonical in prefixes:
            if len(components) < len(canonical):
                continue
            actual = components[: len(canonical)]
            if tuple(map(_portable_name_key, actual)) != tuple(
                map(_portable_name_key, canonical)
            ):
                continue
            if actual != canonical:
                raise SnapshotError(
                    "Git tree contains a portable governed namespace alias: "
                    f"{'/'.join(actual)!r} aliases {'/'.join(canonical)!r}"
                )


def capture_git_commit_snapshot(
    root: Path,
    commit: str,
    required_files: Sequence[str],
    review_dir: str,
    forbidden_paths: Sequence[str],
    *,
    max_file_bytes: int = DEFAULT_MAX_FILE_BYTES,
) -> RepositorySnapshot:
    """Capture governed inputs from one immutable SHA-1 commit and its tree.

    Unlike :func:`capture_repository_snapshot`, this formal-gate path never
    reads governed names or bytes from the mutable worktree.  Every returned
    byte is addressed through the supplied commit's object graph.
    """

    try:
        if isinstance(max_file_bytes, bool) or not isinstance(max_file_bytes, int):
            raise SnapshotError("max_file_bytes must be an integer")
        if max_file_bytes < 0:
            raise SnapshotError("max_file_bytes must be non-negative")
        canonical_root = _validate_root_lexically(root)
        if not isinstance(commit, str) or _FULL_SHA1.fullmatch(commit) is None:
            raise SnapshotError("commit must be an exact lowercase 40-hex SHA-1")
        required, reviews, forbidden = _prepare_inputs(
            required_files, review_dir, forbidden_paths
        )

        try:
            object_format = _run_git(
                canonical_root, ["rev-parse", "--show-object-format=storage"]
            ).decode("ascii", errors="strict").strip()
        except UnicodeDecodeError as exc:
            raise SnapshotError("Git object format is not ASCII") from exc
        if object_format != "sha1":
            raise SnapshotError(
                f"Git repository object format must be sha1, observed {object_format!r}"
            )

        object_type = _run_git(canonical_root, ["cat-file", "-t", commit])
        if object_type != b"commit\n":
            observed = object_type.decode("ascii", errors="replace").strip()
            raise SnapshotError(
                f"supplied Git object is not a commit: {commit} ({observed!r})"
            )
        tree = _git_sha1(
            _run_git(canonical_root, ["rev-parse", "--verify", f"{commit}^{{tree}}"]),
            label="commit tree",
        )
        entries = _parse_git_tree(
            _run_git(
                canonical_root,
                ["ls-tree", "-r", "-t", "-z", "--full-tree", commit],
            )
        )
        _reject_git_namespace_aliases(entries, required, reviews, forbidden)

        review_entry = entries.get(reviews)
        if review_entry is None or review_entry[:2] != ("040000", "tree"):
            raise SnapshotError(
                f"review directory is missing or not a Git tree: {reviews}"
            )
        review_components = tuple(reviews.split("/"))
        review_names = tuple(
            sorted(
                components[-1]
                for path in entries
                if len(components := tuple(path.split("/")))
                == len(review_components) + 1
                and components[: len(review_components)] == review_components
            )
        )

        captured: dict[str, bytes] = {}
        for relative in required:
            entry = entries.get(relative)
            if entry is None:
                raise SnapshotError(f"required file is missing from Git commit: {relative}")
            mode, object_type, oid = entry
            if mode != "100644" or object_type != "blob":
                raise SnapshotError(
                    f"required Git input must be a 100644 blob: {relative} "
                    f"({mode} {object_type})"
                )
            size_bytes = _run_git(canonical_root, ["cat-file", "-s", oid])
            try:
                size_text = size_bytes.decode("ascii", errors="strict").strip()
                if not size_text or not size_text.isdecimal():
                    raise ValueError("not decimal")
                size = int(size_text)
            except (UnicodeDecodeError, ValueError) as exc:
                raise SnapshotError(
                    f"Git blob size is malformed for governed input: {relative}"
                ) from exc
            if size > max_file_bytes:
                raise SnapshotError(
                    f"required Git blob exceeds max_file_bytes "
                    f"({size} > {max_file_bytes}): {relative}"
                )
            data = _run_git(canonical_root, ["cat-file", "blob", oid])
            if len(data) != size:
                raise SnapshotError(
                    f"Git blob size changed or was read incompletely: {relative}"
                )
            captured[relative] = bytes(data)

        if tuple(sorted(captured)) != required:
            raise SnapshotError("Git snapshot returned an incomplete governed input inventory")
        return RepositorySnapshot(
            canonical_root=canonical_root,
            files=MappingProxyType(dict(captured)),
            review_names=review_names,
            governed_inputs=required,
            source="git-commit",
            commit=commit,
            tree=tree,
        )
    except SnapshotError:
        raise
    except Exception as exc:
        raise SnapshotError(f"Git commit snapshot failed: {exc}") from exc


def capture_repository_snapshot(
    root: Path,
    required_files: Sequence[str],
    review_dir: str,
    forbidden_paths: Sequence[str],
    *,
    max_file_bytes: int = DEFAULT_MAX_FILE_BYTES,
) -> RepositorySnapshot:
    """Capture all governed bytes and directory names from held OS handles.

    ``SnapshotError`` is the only ordinary failure type exposed by this API.
    No snapshot object is returned until every input, absence check, immutable
    byte copy, metadata recheck, and review-directory recheck has succeeded.
    """

    try:
        if isinstance(max_file_bytes, bool) or not isinstance(max_file_bytes, int):
            raise SnapshotError("max_file_bytes must be an integer")
        if max_file_bytes < 0:
            raise SnapshotError("max_file_bytes must be non-negative")
        canonical_candidate = _validate_root_lexically(root)
        required, reviews, forbidden = _prepare_inputs(
            required_files, review_dir, forbidden_paths
        )
        backend = _select_backend()
        canonical_root, captured, review_names = backend.capture(
            canonical_candidate, required, reviews, forbidden, max_file_bytes
        )
        if tuple(sorted(captured)) != required:
            raise SnapshotError("backend returned an incomplete governed input inventory")
        immutable_files = MappingProxyType(dict(captured))
        return RepositorySnapshot(
            canonical_root=canonical_root,
            files=immutable_files,
            review_names=tuple(review_names),
            governed_inputs=tuple(required),
        )
    except SnapshotError:
        raise
    except Exception as exc:
        raise SnapshotError(f"repository snapshot failed: {exc}") from exc


__all__ = [
    "DEFAULT_MAX_FILE_BYTES",
    "RepositorySnapshot",
    "SnapshotError",
    "capture_git_commit_snapshot",
    "capture_repository_snapshot",
]
