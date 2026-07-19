# Local deliverables

Content-addressed APKs and evidence archives are retained under this directory for
workspace handoff, but the large binary files are intentionally excluded from Git.
Each retained artifact must be named with its SHA-256 digest and must be covered by
a committed provenance record and evidence manifest before it can be cited.

The mutable Gradle output under `app/build/` is never an accepted handoff artifact.
Rebuilding or repackaging invalidates any evidence bound to that mutable path until
the new APK is retested and copied here under its new digest.
