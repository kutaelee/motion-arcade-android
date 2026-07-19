# AndroidX DataStore 1.2.1 corruption/reset artifact probe

## Purpose and boundary

This read-only dependency probe resolves review-06's reset lifecycle uncertainty. It
used official JVM artifacts and an ignored standalone harness; it did not activate or
modify the Android product dependency graph. Android integration and crash-point tests
remain required.

## Primary artifact identity

Source base: `https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-jvm/1.2.1/`

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `datastore-core-jvm-1.2.1.jar` | 170,156 | `DD2D080FC16EB0BEA1E02E5551D43496FB9E5177855C5B72C6AAF6E16AB1C5C1` |
| `datastore-core-jvm-1.2.1-sources.jar` | 50,950 | `766EB126E0DE85A27DF66879CE3577205CC31FC2A15730E20FD48C22F4F1A048` |
| `datastore-core-jvm-1.2.1.module` | 12,566 | `9F95DE6E21A7AFEBAAE381FBF592AD717D0F8A92DD7E15484AADF3E9251A4A86` |
| `datastore-core-jvm-1.2.1.pom` | 4,257 | `FC77C1C1EADBB46D7403CE7014A7BB25B439E49B87AF5396DB8345E4E8D01D48` |

Relevant extracted source hashes:

- `FileStorage.kt`: `63819C82AFB90B72D2775B70445E65F3D40C2FB958F13315C346E29C1C9FF7AC`;
- `DataStoreImpl.kt`: `344420E4E7C22C2D43BEB1938EA51E68DD9221B8CA29061A94A3F880D8971FA2`;
- `ReplaceFileCorruptionHandler.jvm.kt`:
  `FC4060A19597CD1BC801A28E28AB9C3B898080C1C7FD80EEAE74A20C0F8D8A5D`.

## Published-bytecode cross-check

The published JVM jar above was also inspected directly with JDK 17 `javap -p -c`;
this is bytecode evidence, not an Android filesystem crash guarantee. The reproducible
commands were:

```powershell
$artifactRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\androidx.datastore\datastore-core-jvm\1.2.1'
$jar = Get-ChildItem -LiteralPath $artifactRoot -Recurse -Filter 'datastore-core-jvm-1.2.1.jar' |
  Where-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq 'DD2D080FC16EB0BEA1E02E5551D43496FB9E5177855C5B72C6AAF6E16AB1C5C1' } |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $jar) { throw 'Pinned DataStore jar not found' }
Get-FileHash -LiteralPath $jar -Algorithm SHA256
javap -classpath $jar -p -c androidx.datastore.core.FileStorageConnection
javap -classpath $jar -p -c 'androidx.datastore.core.FileWriteScope$writeData$2'
javap -classpath $jar -p -c androidx.datastore.core.FileMoves_jvmKt
javap -classpath $jar -p -c androidx.datastore.core.FileStorage
```

Observed instructions:

- `FileStorageConnection.writeScope` offsets 101-106 invoke its private
  `createParentDirectories(file)` before every write scope; that helper's offsets 18-26
  call `File.mkdirs()`, discard the boolean result, and then require
  `File.isDirectory()`;
- `FileStorageConnection.writeScope` offsets 201-225 append literal `.tmp` to the
  backing file's absolute path and construct the write scope on that sibling;
- `FileWriteScope$writeData$2.invokeSuspend` offsets 162-169 obtain the output
  stream descriptor and call `FileDescriptor.sync()` before close;
- `FileStorageConnection.writeScope` offsets 491-506 move an existing temporary to
  the base through `FileMoves_jvmKt.atomicMoveTo`, while the caught-`IOException` path
  at offsets 554-570 calls `File.delete()` and discards the boolean result;
- `FileMoves_jvmKt.atomicMoveTo` offsets 21-33 pass exactly one copy option,
  `REPLACE_EXISTING`, to `Files.move`; the bytecode does **not** request
  `StandardCopyOption.ATOMIC_MOVE`; and
- `FileStorage.createConnection` rejects a second active canonical path within the same
  loaded JVM process/classloader state, and its close callback removes that path from
  the process-local active set.

The method name `atomicMoveTo` is therefore not used as proof that every API/device
crash cut leaves only a whole old or whole new DataStore file. Recovery may approve an
exact frozen whole-file old or new identity; a partial, unrelated, or otherwise third
identity remains blocking evidence. Android API/device crash injection is still
required.

The fixed `mkdirs()+isDirectory()` sequence means product policy cannot truthfully ban
the call from the pinned dependency. CapabilityModeStoreV4 instead performs checked
public-Os bootstrap first, freezes the parent descriptor's `st_dev`/`st_ino`, permits
only this exact pinned internal call as non-authoritative idempotent behavior, and
requires the same directory identity after owner join. Android integration must verify
that the activated variant retains this exact behavior and that a parent identity
change fails closed.

## Execution

Harness stack: repository Gradle 9.4.1, Kotlin JVM 2.4.0, JDK 17.0.17,
DataStore core JVM 1.2.1, coroutines JVM 1.9.0, and protobuf-javalite 4.35.1.

Attempt 1 failed before behavior execution because the harness did not smart-cast a
nullable exception through a custom assertion. Attempt 2 changed that method, ran a
clean build, and returned:

```text
PASS one_active_instance_per_file duplicate=IllegalStateException sequential_reopen=0
PASS same_read_returns_replacement first=42 second=42 handler_calls=1 disk=082a
PASS unresolved_corruption_retries failures=2 handler_calls=2 disk=80
PASS atomic_one_shot_permit handler_calls=2 permit_claims=1 write_attempts=1 first_suppressed=1 second_suppressed=0 disk=80
PASS journal_permit_strict_reopen reset_read=99 handler_calls=1 owners_overlapped=false verify_read=99
RESULT passed=5 failed=0 datastore=1.2.1
BUILD SUCCESSFUL
```

Command:

```powershell
.\gradlew.bat -p docs\evidence\runs\_working\datastore-1.2.1-probe\harness clean run --no-daemon --stacktrace
```

## Evidence-backed decision

Under the tested JVM harness:

- two live connections in the same process/classloader to one canonical file are rejected;
- `cancelAndJoin` was observed to close ownership and permit a later non-overlapping instance;
- a successful corruption-handler replacement was descriptor-synced, observed on disk,
  and returned from the triggering read; crash durability remains unproved, and another
  read on that instance is cached rather than strict disk evidence;
- Unresolved corruption and replacement-write failure can invoke the handler again.
- A construction-captured atomic CAS permit limited authorization to one claim/write.

The rejected RecoveryJournalV4 proposal therefore used RESET_REQUESTED -> RESET_RUNNING,
one reset owner, bounded cancel-and-join, then a non-overlapping handler-free verifier
owner. A recovered RESET_RUNNING never automatically rearms the handler. This is direct
dependency-mechanism evidence, not Android/physical release approval.

RecoveryJournalV4 was subsequently rejected before implementation. Capability-v10's
RecoveryJournalV5 retains the harness-demonstrated reset ordering and also applies cancel-and-join
before normal-save direct disk verification. The source and published-bytecode evidence
for DataStore 1.2.1's fixed `<base>.tmp` scratch path, descriptor sync, move options,
and cleanup behavior is policy input, but still requires Android integration/crash
evidence before adapter approval.
