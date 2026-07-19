# Baseline APK DEX/JNI ZIP-name feasibility check

## Purpose and boundary

This read-only check tests the NativeCloseFenceProofV1 packaged-name rule against the
existing Slice 0A APK. It is ZIP-format feasibility evidence only. It does not prove a
native callback-close fence, final Slice 1B packaging equality, or physical-device
behavior.

Input APK:

- path: `artifacts/slice-0a/motion-arcade-slice-0a-58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f.apk`;
- bytes: `68,351,511`;
- SHA-256: `58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f`.

## Method

A Python standard-library reader located EOCD, parsed all central-directory headers,
followed each local-header offset, decoded only ASCII names, and compared raw local and
central filename bytes. It selected exact `*.dex` and `lib/**` members and recorded both
general-purpose flags and compression methods. The core checks were:

```python
eocd = apk_bytes.rfind(b"PK\x05\x06")
entry_count = unpack_from("<H", apk_bytes, eocd + 10)[0]
central_offset = unpack_from("<I", apk_bytes, eocd + 16)[0]

# For each central header at central_offset:
flags = unpack_from("<H", apk_bytes, position + 8)[0]
method = unpack_from("<H", apk_bytes, position + 10)[0]
name_length, extra_length, comment_length = unpack_from("<HHH", apk_bytes, position + 28)
raw_name = apk_bytes[position + 46 : position + 46 + name_length]
local_offset = unpack_from("<I", apk_bytes, position + 42)[0]
local_flags = unpack_from("<H", apk_bytes, local_offset + 6)[0]
local_name_length = unpack_from("<H", apk_bytes, local_offset + 26)[0]
local_raw_name = apk_bytes[local_offset + 30 : local_offset + 30 + local_name_length]
assert raw_name.decode("ascii")
assert raw_name == local_raw_name
```

A second pass also compared local/central method, CRC-32, compressed size, and
uncompressed size; recorded central extra/comment and local extra lengths; and checked
every local extra byte. It observed exact equality for all five compared header values,
zero central extras/comments, DEX local-extra length zero, and JNI local-extra lengths
0..16,054 containing only zero-byte zipalign padding.

## Observed result

```text
apk_sha256 58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f
zip_entries 545
matched 24 dex 8 jni 16
central_flags [0] local_flags [0]
methods [0, 8] raw_names_equal True
first ('classes.dex', 0, 0, 8, True)
last ('lib/x86_64/libsurface_util_jni.so', 0, 0, 0, True)
```

All eight DEX and sixteen JNI entries used general-purpose flag `0`, not UTF-8 bit 11,
while their raw local/central names were identical ASCII. Requiring bit 11 would reject
the existing Android package without improving name identity. The v10 rule therefore
allows bit 11 to be zero or one but permits no other flag, requires exact local/central
flags/method/CRC/sizes and ASCII raw-name equality, accepts only empty/all-zero local
zipalign padding, and rejects data descriptors, ZIP64, Unicode-path aliases, encryption,
malformed names, and duplicate raw/decoded names. This freezes an observed-compatible
fail-closed subset; nonzero flags or nonzero extra bytes require new evidence/policy.
