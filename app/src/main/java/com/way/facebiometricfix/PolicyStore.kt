package com.way.facebiometricfix

import android.content.Context
import android.content.SharedPreferences

class PolicyStore(
    context: Context,
    private val remoteProvider: () -> SharedPreferences?,
) {
    private val local = context.getSharedPreferences(
        "biometric_policy_local",
        Context.MODE_PRIVATE,
    )

    fun explicitModeFor(packageName: String): BiometricPolicyMode? {
        val raw = local.getString(PolicyConfig.keyFor(packageName), null) ?: return null
        return PolicyConfig.parse(raw).takeUnless { it == BiometricPolicyMode.ANY }
    }

    fun defaultMode(): BiometricPolicyMode =
        PolicyConfig.parse(local.getString(PolicyConfig.KEY_DEFAULT_MODE, null))

    fun effectiveModeFor(packageName: String): BiometricPolicyMode =
        explicitModeFor(packageName) ?: defaultMode()

    fun setDefaultMode(mode: BiometricPolicyMode) {
        local.edit()
            .putString(PolicyConfig.KEY_DEFAULT_MODE, mode.name)
            .apply()

        runCatching {
            remoteProvider()?.edit()
                ?.putString(PolicyConfig.KEY_DEFAULT_MODE, mode.name)
                ?.apply()
        }
    }

    fun setMode(packageName: String, mode: BiometricPolicyMode?) {
        setModes(setOf(packageName), mode)
    }

    fun setModes(packageNames: Collection<String>, mode: BiometricPolicyMode?) {
        if (packageNames.isEmpty()) return

        local.edit().apply {
            packageNames.forEach { packageName ->
                val key = PolicyConfig.keyFor(packageName)
                if (mode == null || mode == BiometricPolicyMode.ANY) {
                    remove(key)
                } else {
                    putString(key, mode.name)
                }
            }
        }.apply()

        runCatching {
            remoteProvider()?.edit()?.apply {
                packageNames.forEach { packageName ->
                    val key = PolicyConfig.keyFor(packageName)
                    if (mode == null || mode == BiometricPolicyMode.ANY) {
                        remove(key)
                    } else {
                        putString(key, mode.name)
                    }
                }
            }?.apply()
        }
    }

    fun updateManagedPackages(packageNames: Collection<String>) {
        val managed = packageNames.toSet()

        local.edit()
            .putStringSet(PolicyConfig.KEY_MANAGED_PACKAGES, managed)
            .apply()

        runCatching {
            remoteProvider()?.edit()
                ?.putStringSet(PolicyConfig.KEY_MANAGED_PACKAGES, managed)
                ?.apply()
        }
    }

    fun configuredCount(): Int =
        local.all.keys.count { it.startsWith(PolicyConfig.KEY_PREFIX) }

    fun onRemoteAvailable() {
        val remote = remoteProvider() ?: return

        runCatching {
            val relevantLocal = local.all.filterKeys {
                it.startsWith(PolicyConfig.KEY_PREFIX) ||
                    it == PolicyConfig.KEY_DEFAULT_MODE ||
                    it == PolicyConfig.KEY_MANAGED_PACKAGES
            }
            val relevantRemote = remote.all.filterKeys {
                it.startsWith(PolicyConfig.KEY_PREFIX) ||
                    it == PolicyConfig.KEY_DEFAULT_MODE ||
                    it == PolicyConfig.KEY_MANAGED_PACKAGES
            }

            if (relevantLocal.isEmpty() && relevantRemote.isNotEmpty()) {
                local.edit().apply {
                    relevantRemote.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                putStringSet(key, value.filterIsInstance<String>().toSet())
                            }
                        }
                    }
                }.apply()
            } else if (relevantLocal.isNotEmpty()) {
                remote.edit().apply {
                    relevantRemote.keys.forEach(::remove)
                    relevantLocal.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                putStringSet(key, value.filterIsInstance<String>().toSet())
                            }
                        }
                    }
                }.apply()
            }
        }
    }
}
