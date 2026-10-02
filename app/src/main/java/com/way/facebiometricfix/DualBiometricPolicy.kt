package com.way.facebiometricfix

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

enum class BiometricPolicyMode {
    ANY,
    FACE_ONLY,
    FINGERPRINT_ONLY,
    FACE_AND_FINGERPRINT,
}

internal object PolicyConfig {
    const val PREF_GROUP = "biometric_policy"
    const val KEY_PREFIX = "policy."
    const val KEY_DEFAULT_MODE = "default_mode"
    const val KEY_MANAGED_PACKAGES = "managed_packages"

    fun keyFor(packageName: String): String = KEY_PREFIX + packageName

    fun parse(raw: String?): BiometricPolicyMode =
        runCatching { BiometricPolicyMode.valueOf(raw ?: "") }
            .getOrDefault(BiometricPolicyMode.ANY)
}

/**
 * Hot-path policy cache backed by libxposed Remote Preferences.
 *
 * Explicit per-app overrides win first. The global default applies only to
 * packages discovered by the manager scan, so unrelated/system packages keep
 * Android's native behavior unless the user explicitly configures them.
 */
internal object DualBiometricPolicy {
    private val excludedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.coloros.codebook",
        "com.way.facebiometricfix",
    )

    private val modes = ConcurrentHashMap<String, BiometricPolicyMode>()

    @Volatile
    private var defaultMode = BiometricPolicyMode.ANY

    @Volatile
    private var managedPackages: Set<String> = emptySet()

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when {
            key == PolicyConfig.KEY_DEFAULT_MODE -> {
                defaultMode = PolicyConfig.parse(
                    prefs.getString(PolicyConfig.KEY_DEFAULT_MODE, null)
                )
            }

            key == PolicyConfig.KEY_MANAGED_PACKAGES -> {
                managedPackages = prefs.getStringSet(
                    PolicyConfig.KEY_MANAGED_PACKAGES,
                    emptySet(),
                )?.toSet().orEmpty()
            }

            key != null && key.startsWith(PolicyConfig.KEY_PREFIX) -> {
                val packageName = key.removePrefix(PolicyConfig.KEY_PREFIX)
                if (packageName.isBlank()) return@OnSharedPreferenceChangeListener

                if (!prefs.contains(key)) {
                    modes.remove(packageName)
                } else {
                    modes[packageName] = PolicyConfig.parse(prefs.getString(key, null))
                }
            }
        }
    }

    fun attach(prefs: SharedPreferences) {
        if (remotePrefs === prefs) return

        remotePrefs?.unregisterOnSharedPreferenceChangeListener(listener)
        remotePrefs = prefs

        defaultMode = PolicyConfig.parse(
            prefs.getString(PolicyConfig.KEY_DEFAULT_MODE, null)
        )
        managedPackages = prefs.getStringSet(
            PolicyConfig.KEY_MANAGED_PACKAGES,
            emptySet(),
        )?.toSet().orEmpty()

        modes.clear()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(PolicyConfig.KEY_PREFIX)) return@forEach
            val packageName = key.removePrefix(PolicyConfig.KEY_PREFIX)
            val mode = PolicyConfig.parse(value as? String)
            if (packageName.isNotBlank()) {
                modes[packageName] = mode
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun modeFor(packageName: String): BiometricPolicyMode {
        if (packageName.isBlank() || packageName in excludedPackages) {
            return BiometricPolicyMode.ANY
        }

        if (modes.containsKey(packageName)) {
            return modes[packageName] ?: BiometricPolicyMode.ANY
        }
        return if (packageName in managedPackages) defaultMode else BiometricPolicyMode.ANY
    }

    fun shouldPromoteFace(packageName: String): Boolean =
        when (modeFor(packageName)) {
            BiometricPolicyMode.FINGERPRINT_ONLY -> false
            BiometricPolicyMode.ANY,
            BiometricPolicyMode.FACE_ONLY,
            BiometricPolicyMode.FACE_AND_FINGERPRINT,
            -> true
        }
}
