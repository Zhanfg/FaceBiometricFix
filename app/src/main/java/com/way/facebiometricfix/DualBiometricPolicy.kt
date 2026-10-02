package com.way.facebiometricfix

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

internal enum class BiometricPolicyMode {
    ANY,
    FACE_ONLY,
    FINGERPRINT_ONLY,
    FACE_AND_FINGERPRINT,
}

internal object PolicyConfig {
    const val PREF_GROUP = "biometric_policy"
    const val KEY_PREFIX = "policy."

    fun keyFor(packageName: String): String = KEY_PREFIX + packageName

    fun parse(raw: String?): BiometricPolicyMode =
        runCatching { BiometricPolicyMode.valueOf(raw ?: "") }
            .getOrDefault(BiometricPolicyMode.ANY)
}

/**
 * Hot-path policy cache backed by libxposed Remote Preferences.
 *
 * The hooked system_server side is read-only. The settings app writes the same
 * remote preference group through XposedService. A listener keeps this cache
 * current without rebooting or rescanning on every authentication.
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
    private var remotePrefs: SharedPreferences? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || !key.startsWith(PolicyConfig.KEY_PREFIX)) return@OnSharedPreferenceChangeListener
        val packageName = key.removePrefix(PolicyConfig.KEY_PREFIX)
        if (packageName.isBlank()) return@OnSharedPreferenceChangeListener

        val mode = PolicyConfig.parse(prefs.getString(key, null))
        if (mode == BiometricPolicyMode.ANY) {
            modes.remove(packageName)
        } else {
            modes[packageName] = mode
        }
    }

    fun attach(prefs: SharedPreferences) {
        if (remotePrefs === prefs) return

        remotePrefs?.unregisterOnSharedPreferenceChangeListener(listener)
        remotePrefs = prefs

        modes.clear()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(PolicyConfig.KEY_PREFIX)) return@forEach
            val packageName = key.removePrefix(PolicyConfig.KEY_PREFIX)
            val mode = PolicyConfig.parse(value as? String)
            if (packageName.isNotBlank() && mode != BiometricPolicyMode.ANY) {
                modes[packageName] = mode
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun modeFor(packageName: String): BiometricPolicyMode {
        if (packageName.isBlank() || packageName in excludedPackages) {
            return BiometricPolicyMode.ANY
        }
        return modes[packageName] ?: BiometricPolicyMode.ANY
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
