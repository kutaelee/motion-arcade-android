package com.motionarcade.vision.capability.domain

/**
 * Parse-only diagnostic aliases. These values are not provenance, capability, or native-create
 * authority, and no authorization API accepts them.
 */
internal typealias NativeCloseCanonicalCodeImageInspection =
    NativeCloseInstalledApkEngine.CanonicalImageMaterial
internal typealias NativeCloseCanonicalJniSetInspection =
    NativeCloseInstalledApkEngine.JniSetMaterial
internal typealias NativeCloseInstalledImagesInspection =
    NativeCloseInstalledApkEngine.InspectionMaterial
internal typealias NativeCloseInstalledImagesInspectionResult =
    NativeCloseInstalledApkEngine.InspectionAttemptMaterial

internal enum class NativeCloseInstalledImagesFailure {
    PACKAGE_SET_INVALID,
    APK_INSPECTION_INVALID,
    SOURCE_CHANGED,
    INSPECTION_ABORTED,
    RESOURCE_LIMIT_EXCEEDED,
    DEX_IMAGE_INVALID,
    JNI_SET_INVALID,
    CANONICAL_ENCODING_FAILED,
}
