# Slice 1B capability policy review 08

## Verdict and reviewed snapshot

**REJECT / REWORK.** Two read-only passes plus direct Android 36 source inspection
rejected the stable pre-commit `capability-v8` snapshot with no P0, four P1, and no
separate P2 finding. It was never committed or approved. The reviewed document bytes
were stable for the general audit:

| Document | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `1C6E01F29EE25A79AD763094FF1683993D352B72678773E976D70724C478F58F` |
| `docs/contracts/capability-store-v4.md` | `79CEC46CAF651181C3DD7953374A1F88402629BBF98A33222010DBF5C3C72785` |
| `docs/contracts/recovery-journal-v4.md` | `FF58B758234F4D364C93FFD928D3A0140125974BDF442A410CC518099C6C12AA` |
| `docs/execution/slice-1b-contract.md` | `2399D471AB55AA5854BB274D401ED10457E351ACCFB5E1D837A27ABCCEEADFB0` |

Physical G2/G8, Android DataStore integration, and shipped-ABI/API native behavior
remain pending release evidence; none was credited as passed.

## Findings and required v9 resolution

| Priority | v8 defect | Counterexample | Required `capability-v9` resolution |
| --- | --- | --- | --- |
| P1 | Final clear deleted evidence required by its new live record | CPU RETRY_CONSUMED, GPU measured/selected, record contains CPU_SKIPPED_QUARANTINED, final clear deletes the CPU tombstone, strict reread immediately rejects the record | RETRY_CONSUMED is absorbing for the exact artifact/mode/base/delegate; final clear/recheck never deletes QUARANTINED or RETRY_CONSUMED; dual strict reread cross-validates every skipped event |
| P1 | Thermal close occurred only when the sentinel began | a callback calls wrapper `execute` after sentinel enqueue but before sentinel entry, is admitted and queued behind the sentinel, and makes a zero-wait sentinel unable to complete without losing the status or deadlocking | wrapper-owned bounded FIFO; `execute` and close-plus-sentinel append linearize under one gate; no external call under the gate; post-close execute has an exact durable poison reason |
| P1 | Journal completion trusted `AtomicFile.finishWrite()` return | `.new` holds intended bytes, `renameTo` fails, Android logs and the void method returns, v8 authorizes native work while base still holds old bytes | checked flush/`FileDescriptor.sync`, authorize before finish, then direct exact base reread with `.new`/`.bak` absent; return alone never completes; cleanup is trusted only after the same inspection |
| P1 | FrameMetrics removal did not drain Handler/native work | native observer posts a Runnable, main removal returns, v8 freezes/persists, queued Runnable later invokes the listener; native removal itself is asynchronously posted and can also generate a post behind any app Handler sentinel | capability-v9 public Window path is non-authoritative and persists exact UNAVAILABLE/REMOVAL_FENCE_UNPROVEN zero evidence; COMPLETE/PARTIAL are reader-invalid until a later schema has an actual native happens-before fence |

Review 07's consumed-state conversion, quarantine-only epoch allocation, recovery owner
identity, endpoint multiplicity, and reset-epoch fixes were materially present in v8.
They do not resolve these four independent failures.

## Android 36 evidence

The inspected local SDK source files and SHA-256 values were:

| Source | SHA-256 | Relevant lines |
| --- | --- | --- |
| `android/util/AtomicFile.java` | `90E1CC85E92E53E6C257E49156677DE354A79D2B6CAF65D826CB77D0CFFAB70E` | 172-188 void/log-only finish; 195-209 log-only cleanup; 338-352 log-only rename |
| `android/graphics/HardwareRendererObserver.java` | `CBB258B4F48C77641D947512DEFF922349243BD330E146F8E055F51C5844650F` | 88-100 asynchronous Handler post and listener drain |
| `android/graphics/HardwareRenderer.java` | `35626890A527069991AD6A0797CDFCD1FB32C16773E10150B0C245EAFEA107E9` | 673-681 native removal call without Java fence |
| `android/view/View.java` | `CEB24DF845D5304BA82422D828F0BD3AF8F9DA93C33CE898935850DF875C7AC2` | 7983-7997 unregisters observer without Handler drain |
| `android/view/Window.java` | `C35D1B3C29416E8AC15FFC03E95967DE85EFD5377D66D3E087EA83534DFEBA22` | 980-1002 supplies Handler and delegates removal |
| `android/os/Handler.java` | `1945369C50F0F61F15382DE6B4CD54F5D5901D7064A854756DF6757C773DFE6B` | 438-440 post path; 731-740 overridable enqueue; 101-112 dispatch |
| `android/os/PowerManager.java` | `ECEED282B2A2C36266B5B180FD1D832A8F6F8DD57F53043F5E8C0F39B5063D1C` | 2890-2898 Binder calls supplied executor; 2918-2933 unregister has no app-executor drain |

Official AOSP native source strengthens the FrameMetrics finding: `RenderProxy` posts
both add and remove work asynchronously to RenderThread rather than running a removal
fence ([AOSP `RenderProxy.cpp`, lines 241-249](https://android.googlesource.com/platform/frameworks/base/+/99e1424/libs/hwui/renderthread/RenderProxy.cpp#241)). A quiet physical run is useful regression evidence but cannot prove a universal future-post absence.

## Required v9 rereview fixtures

- save -> final clear -> journal/store reread with a retained CPU or GPU skipped-route
  tombstone; pending-committed recovery and recheck retain it; eight-entry exhaustion
  fails closed without overwrite;
- one authorization per exact quarantine, successful manual retry resolution, and
  absorbing RETRY_CONSUMED failure paths;
- thermal execute-before-close and close-before-execute orders, queued/running CRITICAL,
  exact 64/65 queue boundary, post-close submission before sentinel entry, runner
  rejection, listener throw, and proof that no external call holds the wrapper gate;
- AtomicFile sync throw, silent rename failure with old/absent base, truncated/third
  base, residual sibling, cleanup failure, exact clean commit token, deadline between
  authorization/reread, and crash cuts;
- FrameMetrics queued-before-remove and native-post-behind-sentinel counterexamples,
  exact REMOVAL_FENCE_UNPROVEN zero tuple, and rejection of COMPLETE/PARTIAL in v9.

`capability-v9` remains proposed until fresh reviewers inspect its clean exact commit
and report no actionable P0/P1/P2 defect. This document is rejection evidence, not an
implementation, physical-device, G8, or release approval.
