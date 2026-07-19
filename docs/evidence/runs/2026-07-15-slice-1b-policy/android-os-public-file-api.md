# Android public file-API surface check

## Purpose and boundary

This read-only check tests whether the proposed Slice 1B recovery writer can be built
using public Android API 26-37 symbols. It is API-surface evidence only; it does not
prove OEM filesystem behavior, directory-fsync support, rename durability, process-
death recovery, or sudden-power behavior.

## Official API evidence

The Android Developers public references report:

- [`Os.open`, `read`, `mkdir`, `fsync`, `fstat`, `lstat`, `remove`, and `rename`](https://developer.android.com/reference/android/system/Os)
  were added in API 21;
- [`OsConstants.O_NOFOLLOW` and `S_ISDIR`](https://developer.android.com/reference/android/system/OsConstants)
  were added in API 21; and
- `OsConstants.O_CLOEXEC` was added in API 27.

The public `OsConstants` reference does not declare `O_DIRECTORY`. The checked local
API 33, 36, and 37.0 stubs likewise contain `O_CLOEXEC`, `O_NOFOLLOW`, `S_ISDIR`, and
`S_ISREG`, but no `O_DIRECTORY`. A policy that names public
`OsConstants.O_DIRECTORY` would therefore be non-compilable against the checked SDKs
and cannot claim API 26-37 public-API feasibility.

The implementable public directory-sync sequence is instead:

1. lstat the fixed private parent path and require directory type;
2. open it read-only with `O_NOFOLLOW` (and API27+ `O_CLOEXEC`) and mode zero;
3. fstat the returned descriptor and require `S_ISDIR(st_mode)`;
4. call `Os.fsync`, then checked `Os.close`; and
5. fail closed on every open/type/fsync/close error.

This removes the unavailable symbol but does not upgrade directory fsync to verified
device behavior. API/device instrumentation remains release-blocking.

The same official reference exposes `Os.mkdir(path, mode)` and `Os.read` from API 21
and links them to `mkdir(2)`/`read(2)`. The v10 contract therefore uses checked
component-by-component `mkdir(path,0700)` for first-run private directories. A file read
requires positive progress only while bytes remain; after the frozen fstat length, a
separate positive-length read returning exactly zero is the required normal EOF result.
Zero before that length or nonzero after it fails. This distinguishes EOF from a
premature no-progress condition instead of rejecting every successful read.

API 26 also supplies public `java.nio.file.Files.newDirectoryStream(Path)`. V10 uses
that API only to enumerate at most two names in the already validated selected journal
directory; fixed-path lstat/open/read/mutation remains on public `android.system.Os`.
The local API 36 source contains the public method and Android's Unix provider/stream
implementation. Exact API 26/27 linkage and iterator/close failure fixtures remain
required. Local stubs also expose `OsConstants.ENOENT` and `EEXIST`; first-run mkdir
accepts only those exact named branches and treats every other errno as blocking.

## Local stub identity and command

```powershell
foreach ($platform in @('android-33', 'android-36', 'android-37.0')) {
  $jar = Join-Path $env:ANDROID_SDK_ROOT "platforms\$platform\android.jar"
  Get-FileHash -LiteralPath $jar -Algorithm SHA256
  javap -classpath $jar -p android.system.Os
  javap -classpath $jar -p android.system.OsConstants
}
```

| Platform | `android.jar` SHA-256 | Required `Os` methods | Constant/type result |
| --- | --- | --- | --- |
| API 33 | `FD628AA21321859A9035C6AAE4B084B7D3FDB3F230BE5CD5EC76541E32F7C963` | present | CLOEXEC/NOFOLLOW/S_ISDIR/S_ISREG/ENOENT/EEXIST present; O_DIRECTORY absent |
| API 36 | `D9EB9DA824D9E247A352F570F01E1169E725B2954BCA9E283A71786C59B59F9A` | present | CLOEXEC/NOFOLLOW/S_ISDIR/S_ISREG/ENOENT/EEXIST present; O_DIRECTORY absent |
| API 37.0 | `BF1B4387CC7CA94FC6EF684F040D9D16FBF16248E181819F020736EA2053F177` | present | CLOEXEC/NOFOLLOW/S_ISDIR/S_ISREG/ENOENT/EEXIST present; O_DIRECTORY absent |

The required methods checked were `open`, `close`, `mkdir`, `fsync`, `fstat`, `lstat`,
`read`, `write`, `remove`, and `rename`. API 26/27 platform jars are not installed locally, so
their availability is supported here by the official per-member API-level references,
not by a fabricated local `javap` result. Exact API 26 and 27 emulator/device linkage
fixtures remain required by the Slice 1B execution contract.
