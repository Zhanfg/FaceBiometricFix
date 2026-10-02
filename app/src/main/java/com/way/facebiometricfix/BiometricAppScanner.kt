package com.way.facebiometricfix

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class BiometricCandidateApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val permissions: Set<String>,
    val isSystemApp: Boolean,
)

object BiometricAppScanner {
    private const val USE_BIOMETRIC = "android.permission.USE_BIOMETRIC"
    private const val USE_FINGERPRINT = "android.permission.USE_FINGERPRINT"

    private val excludedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.coloros.codebook",
        "com.way.facebiometricfix",
    )

    fun scan(context: Context): List<BiometricCandidateApp> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
        )

        return packages.asSequence()
            .filter { it.packageName !in excludedPackages && it.packageName != context.packageName }
            .mapNotNull { info ->
                val requested = info.requestedPermissions?.toSet().orEmpty()
                val evidence = buildSet {
                    if (USE_BIOMETRIC in requested) add(USE_BIOMETRIC)
                    if (USE_FINGERPRINT in requested) add(USE_FINGERPRINT)
                }
                if (evidence.isEmpty()) return@mapNotNull null

                val appInfo = info.applicationInfo ?: return@mapNotNull null
                val label = runCatching {
                    appInfo.loadLabel(pm).toString()
                }.getOrDefault(info.packageName)
                val icon = runCatching {
                    appInfo.loadIcon(pm)
                }.getOrElse {
                    pm.defaultActivityIcon
                }
                val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

                BiometricCandidateApp(
                    packageName = info.packageName,
                    label = label,
                    icon = icon,
                    permissions = evidence,
                    isSystemApp = isSystem,
                )
            }
            .sortedWith(
                compareBy<BiometricCandidateApp> { it.isSystemApp }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    fun evidenceLabel(app: BiometricCandidateApp): String =
        when {
            "android.permission.USE_BIOMETRIC" in app.permissions &&
                "android.permission.USE_FINGERPRINT" in app.permissions ->
                "检测到 USE_BIOMETRIC · USE_FINGERPRINT"
            "android.permission.USE_BIOMETRIC" in app.permissions ->
                "检测到 USE_BIOMETRIC"
            else ->
                "检测到 USE_FINGERPRINT"
        }
}
