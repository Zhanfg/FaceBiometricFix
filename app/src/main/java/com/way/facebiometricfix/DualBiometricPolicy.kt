package com.way.facebiometricfix

internal enum class BiometricPolicyMode {
    ANY,
    FACE_THEN_FINGERPRINT,
    FINGERPRINT_ONLY,
}

/**
 * Policy boundary for 2.2.5-test.
 *
 * The engine is deliberately tiny and allocation-free on the hot path.
 * A later settings UI can replace the default with a compact package->mode map
 * without changing AuthSession orchestration.
 */
internal object DualBiometricPolicy {
    private val excludedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.coloros.codebook",
    )

    fun modeFor(packageName: String): BiometricPolicyMode {
        if (packageName.isBlank() || packageName in excludedPackages) {
            return BiometricPolicyMode.ANY
        }
        return BiometricPolicyMode.FACE_THEN_FINGERPRINT
    }
}
