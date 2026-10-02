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

    fun modeFor(packageName: String): BiometricPolicyMode =
        PolicyConfig.parse(local.getString(PolicyConfig.keyFor(packageName), null))

    fun setMode(packageName: String, mode: BiometricPolicyMode) {
        setModes(setOf(packageName), mode)
    }

    fun setModes(packageNames: Collection<String>, mode: BiometricPolicyMode) {
        if (packageNames.isEmpty()) return

        local.edit().apply {
            packageNames.forEach { packageName ->
                val key = PolicyConfig.keyFor(packageName)
                if (mode == BiometricPolicyMode.ANY) {
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
                    if (mode == BiometricPolicyMode.ANY) {
                        remove(key)
                    } else {
                        putString(key, mode.name)
                    }
                }
            }?.apply()
        }
    }

    fun configuredCount(): Int =
        local.all.keys.count { it.startsWith(PolicyConfig.KEY_PREFIX) }

    fun onRemoteAvailable() {
        val remote = remoteProvider() ?: return

        runCatching {
            val localPolicies = local.all.filterKeys {
                it.startsWith(PolicyConfig.KEY_PREFIX)
            }
            val remotePolicies = remote.all.filterKeys {
                it.startsWith(PolicyConfig.KEY_PREFIX)
            }

            if (localPolicies.isEmpty() && remotePolicies.isNotEmpty()) {
                local.edit().apply {
                    remotePolicies.forEach { (key, value) ->
                        val mode = PolicyConfig.parse(value as? String)
                        if (mode == BiometricPolicyMode.ANY) {
                            remove(key)
                        } else {
                            putString(key, mode.name)
                        }
                    }
                }.apply()
            } else if (localPolicies.isNotEmpty()) {
                remote.edit().apply {
                    remotePolicies.keys.forEach(::remove)
                    localPolicies.forEach { (key, value) ->
                        val mode = PolicyConfig.parse(value as? String)
                        if (mode != BiometricPolicyMode.ANY) {
                            putString(key, mode.name)
                        }
                    }
                }.apply()
            }
        }
    }
}
