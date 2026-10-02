package com.way.facebiometricfix

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class MainHook : XposedModule() {

    private fun logInfo(msg: String) {
        Log.i(TAG, msg)
        log(Log.INFO, TAG, msg)
    }

    private fun logWarn(msg: String) {
        Log.w(TAG, msg)
        log(Log.WARN, TAG, msg)
    }

    private fun logError(msg: String, tr: Throwable) {
        Log.e(TAG, msg, tr)
        log(Log.ERROR, TAG, msg, tr)
    }

    private val dualAuth = DualBiometricCoordinator(
        logInfo = ::logInfo,
        logWarn = ::logWarn,
        logError = ::logError,
    )

    private val promptCollisionGuard = PromptCollisionGuard(
        logInfo = ::logInfo,
        logWarn = ::logWarn,
        logError = ::logError,
    )

    private val preAuthContextHooker = XposedInterface.Hooker { chain ->
        val packageName = runCatching { chain.getArg(4) as? String }.getOrNull()
        if (packageName != null && packageName != SYSTEM_UI_PACKAGE) {
            APP_PRE_AUTH_PACKAGE.set(packageName)
        }

        try {
            chain.proceed()
        } finally {
            APP_PRE_AUTH_PACKAGE.remove()
        }
    }

    private val faceStrengthCheckHooker = XposedInterface.Hooker { chain ->
        val packageName = APP_PRE_AUTH_PACKAGE.get()
        val sensorStrength = runCatching { chain.getArg(0) as? Int }.getOrNull()
            ?: return@Hooker false
        val requestedStrength = runCatching { chain.getArg(1) as? Int }.getOrNull()
            ?: return@Hooker false

        if (
            packageName != null &&
            packageName != CODEBOOK_PACKAGE &&
            DualBiometricPolicy.shouldPromoteFace(packageName) &&
            sensorStrength == OEM_STRENGTH_MASK &&
            (requestedStrength == STRONG_AUTHENTICATORS ||
                requestedStrength == WEAK_AUTHENTICATORS)
        ) {
            return@Hooker true
        }

        val maskedStrength = sensorStrength and 32767
        if (((requestedStrength.inv()) and maskedStrength) != 0) return@Hooker false

        var candidate = 1
        while (candidate <= requestedStrength) {
            if (candidate == maskedStrength) return@Hooker true
            candidate = (candidate shl 1) or 1
        }
        false
    }

    private val authSessionSuccessHooker = XposedInterface.Hooker { chain ->
        val session = chain.thisObject
        val sensorId = runCatching { chain.getArg(0) as? Int }.getOrNull()
        val strong = runCatching { chain.getArg(1) as? Boolean }.getOrNull()
        val token = runCatching { chain.getArg(2) as? ByteArray }.getOrNull()

        if (sensorId == null || strong == null) {
            return@Hooker chain.proceed()
        }

        val action = dualAuth.onAuthenticationSucceeded(session, sensorId, strong, token)
        when (action.decision) {
            DualBiometricCoordinator.SuccessDecision.PROCEED_CURRENT -> chain.proceed()
            DualBiometricCoordinator.SuccessDecision.PROCEED_STORED_FINGERPRINT -> {
                val args = chain.args.toTypedArray()
                args[0] = action.sensorId
                args[1] = action.strong
                args[2] = action.token
                chain.proceed(args)
            }
            DualBiometricCoordinator.SuccessDecision.CONSUME -> null
        }
    }
    private val authSessionErrorHooker = XposedInterface.Hooker { chain ->
        val session = chain.thisObject
        val sensorId = runCatching { chain.getArg(0) as? Int }.getOrNull()
        val cookie = runCatching { chain.getArg(1) as? Int }.getOrNull()
        val error = runCatching { chain.getArg(2) as? Int }.getOrNull()

        if (sensorId == null || cookie == null || error == null) {
            return@Hooker chain.proceed()
        }

        when (dualAuth.beforeErrorReceived(session, sensorId, cookie, error)) {
            DualBiometricCoordinator.ErrorDecision.CONSUME_FALSE -> false
            DualBiometricCoordinator.ErrorDecision.PROCEED -> {
                val result = chain.proceed()
                dualAuth.afterErrorReceived(
                    session,
                    sensorId,
                    error,
                    result as? Boolean == true,
                )
                result
            }
        }
    }

    private val authSessionRejectedHooker = XposedInterface.Hooker { chain ->
        val sensorId = runCatching { chain.getArg(0) as? Int }.getOrNull()
        if (sensorId != null && dualAuth.onAuthenticationRejected(chain.thisObject, sensorId)) {
            null
        } else {
            chain.proceed()
        }
    }
    private val authSessionTimedOutHooker = XposedInterface.Hooker { chain ->
        val sensorId = runCatching { chain.getArg(0) as? Int }.getOrNull()
        if (sensorId != null && dualAuth.onAuthenticationTimedOut(chain.thisObject, sensorId)) {
            null
        } else {
            chain.proceed()
        }
    }
    private val authSessionDialogAnimatedHooker = XposedInterface.Hooker { chain ->
        val session = chain.thisObject
        dualAuth.prepare(session)

        val args = chain.args.toTypedArray()
        val override = dualAuth.fingerprintStartOverride(session)
        val result = if (
            args.size == 1 &&
            args[0] is Boolean &&
            override != null
        ) {
            args[0] = override
            chain.proceed(args)
        } else {
            chain.proceed()
        }

        dualAuth.onDialogAnimatedIn(session)
        result
    }
    private val authSessionFingerprintStartHooker = XposedInterface.Hooker { chain ->
        if (dualAuth.shouldBlockFingerprintStart(chain.thisObject)) {
            null
        } else {
            chain.proceed()
        }
    }

    private val authSessionCleanupHooker = XposedInterface.Hooker { chain ->
        try {
            chain.proceed()
        } finally {
            dualAuth.clear(chain.thisObject)
        }
    }

    private val systemUiNeutralHelpHooker = XposedInterface.Hooker { chain ->
        val message = runCatching { chain.getArg(1) as? String }.getOrNull()

        when {
            message != null &&
                message.startsWith(DualBiometricCoordinator.UI_STATE_PREFIX) -> {
                val payload = message.removePrefix(DualBiometricCoordinator.UI_STATE_PREFIX)
                val separator = payload.indexOf('|')
                val state = if (separator >= 0) payload.substring(0, separator) else payload
                val text = if (separator >= 0) payload.substring(separator + 1) else ""
                promptCollisionGuard.showDualAuthState(chain.thisObject, state, text)
                null
            }

            message != null &&
                message.startsWith(DualBiometricCoordinator.NEUTRAL_GUIDANCE_PREFIX) -> {
                promptCollisionGuard.showNeutralGuidance(
                    chain.thisObject,
                    message.removePrefix(DualBiometricCoordinator.NEUTRAL_GUIDANCE_PREFIX),
                )
                null
            }

            else -> chain.proceed()
        }
    }
    private val systemUiAuthSuccessHooker = XposedInterface.Hooker { chain ->
        val modality = runCatching { chain.getArg(0) as? Int }.getOrNull()
        val controller = chain.thisObject
        val result = chain.proceed()

        if (modality == FACE_MODALITY) {
            promptCollisionGuard.onFaceAuthenticated(controller)
        }

        result
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        logInfo("hooking biometrics in system_server ...")
        runCatching {
            DualBiometricPolicy.attach(getRemotePreferences(PolicyConfig.PREF_GROUP))
        }.onSuccess {
            logInfo("OK: live per-app biometric policy attached")
        }.onFailure {
            logError("FAIL remote policy; falling back to ANY", it)
        }
        installSystemServerHooks(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) return
        installSystemUiHooks(param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        APP_PRE_AUTH_PACKAGE.remove()
        logInfo("Hot reloading FaceBiometricFix hooks ...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val oldHandles = param.oldHookHandles.toList()
        val classLoader = oldHandles.firstOrNull()
            ?.executable
            ?.declaringClass
            ?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: return

        val isSystemUiProcess = oldHandles.any {
            it.executable.declaringClass.name.startsWith("com.android.systemui.")
        }

        oldHandles.forEach { handle ->
            runCatching { handle.unhook() }
                .onFailure { logError("FAIL unhook old handle ${handle.id}", it) }
        }

        if (isSystemUiProcess) {
            installSystemUiHooks(classLoader)
        } else {
            installSystemServerHooks(classLoader)
        }
    }

    private fun installSystemServerHooks(classLoader: ClassLoader) {
        hookAppPreAuthContext(classLoader)
        hookAppFaceStrengthCheck(classLoader)
        hookDualAuthSession(classLoader)
    }

    private fun installSystemUiHooks(classLoader: ClassLoader) {
        runCatching {
            val authControllerClass = Class.forName(
                "com.android.systemui.biometrics.AuthController",
                false,
                classLoader,
            )

            val method = authControllerClass.declaredMethods.firstOrNull {
                it.name == "onBiometricAuthenticated" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: error("AuthController#onBiometricAuthenticated(int) not found")

            method.isAccessible = true
            hook(method)
                .setId(HOOK_ID_SYSTEMUI_AUTH_SUCCESS)
                .intercept(systemUiAuthSuccessHooker)

            authControllerClass.declaredMethods.firstOrNull {
                it.name == "onBiometricHelp" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
            }?.let { helpMethod ->
                helpMethod.isAccessible = true
                hook(helpMethod)
                    .setId(HOOK_ID_SYSTEMUI_NEUTRAL_HELP)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(systemUiNeutralHelpHooker)
            }

            logInfo("OK: neutral dual-auth guidance + prompt collision guard")
        }.onFailure {
            logError("FAIL SystemUI auth-success hook", it)
        }
    }

    private fun hookDualAuthSession(classLoader: ClassLoader) {
        runCatching {
            val authSessionClass = Class.forName(
                "com.android.server.biometrics.AuthSession",
                false,
                classLoader,
            )

            val success = authSessionClass.declaredMethods.firstOrNull {
                it.name == "onAuthenticationSucceeded" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[2] == ByteArray::class.java
            } ?: error("AuthSession#onAuthenticationSucceeded(int,boolean,byte[]) not found")

            success.isAccessible = true
            hook(success)
                .setId(HOOK_ID_AUTH_SESSION_SUCCESS)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept(authSessionSuccessHooker)

            authSessionClass.declaredMethods
                .firstOrNull {
                    it.name == "onErrorReceived" &&
                        it.parameterTypes.size == 4 &&
                        it.parameterTypes.all { type ->
                            type == Int::class.javaPrimitiveType
                        }
                }
                ?.let { method ->
                    method.isAccessible = true
                    hook(method)
                        .setId(HOOK_ID_AUTH_SESSION_ERROR)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(authSessionErrorHooker)
                }

            authSessionClass.declaredMethods
                .firstOrNull {
                    it.name == "onAuthenticationRejected" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                ?.let { method ->
                    method.isAccessible = true
                    hook(method)
                        .setId(HOOK_ID_AUTH_SESSION_REJECTED)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(authSessionRejectedHooker)
                }

            authSessionClass.declaredMethods
                .firstOrNull {
                    it.name == "onAuthenticationTimedOut" &&
                        it.parameterTypes.size == 4 &&
                        it.parameterTypes.all { type ->
                            type == Int::class.javaPrimitiveType
                        }
                }
                ?.let { method ->
                    method.isAccessible = true
                    hook(method)
                        .setId(HOOK_ID_AUTH_SESSION_TIMEOUT)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(authSessionTimedOutHooker)
                }

            authSessionClass.declaredMethods
                .filter {
                    it.name == "onDialogAnimatedIn" &&
                        (
                            it.parameterTypes.isEmpty() ||
                                (
                                    it.parameterTypes.size == 1 &&
                                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                                    )
                            )
                }
                .forEachIndexed { index, method ->
                    method.isAccessible = true
                    hook(method)
                        .setId("${HOOK_ID_AUTH_SESSION_DIALOG}_${index}")
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(authSessionDialogAnimatedHooker)
                }

            authSessionClass.declaredMethods
                .filter { it.name == "onStartFingerprint" && it.parameterTypes.isEmpty() }
                .forEachIndexed { index, method ->
                    method.isAccessible = true
                    hook(method)
                        .setId("${HOOK_ID_AUTH_SESSION_FP_START}_${index}")
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept(authSessionFingerprintStartHooker)
                }

            authSessionClass.declaredMethods
                .filter {
                    it.name == "onDialogDismissed" ||
                        it.name == "onCancelAuthSession" ||
                        it.name == "onClientDied" ||
                        it.name == "onDeviceCredentialPressed"
                }
                .forEachIndexed { index, method ->
                    method.isAccessible = true
                    hook(method)
                        .setId("${HOOK_ID_AUTH_SESSION_CLEANUP}_${method.name}_${index}")
                        .intercept(authSessionCleanupHooker)
                }

            logInfo("OK: order-independent FACE+FINGERPRINT 2-of-2 state machine")
        }.onFailure {
            logError("FAIL AuthSession dual-biometric hooks", it)
        }
    }

    private fun hookAppPreAuthContext(classLoader: ClassLoader) {
        runCatching {
            val preAuthInfoClass = Class.forName(
                "com.android.server.biometrics.PreAuthInfo",
                false,
                classLoader,
            )
            val method = preAuthInfoClass.getDeclaredMethod(
                "getStatusForBiometricAuthenticator",
                Class.forName("android.app.admin.DevicePolicyManager", false, classLoader),
                Class.forName(
                    "com.android.server.biometrics.BiometricService\$SettingObserver",
                    false,
                    classLoader,
                ),
                Class.forName(
                    "com.android.server.biometrics.BiometricSensor",
                    false,
                    classLoader,
                ),
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                Class.forName(
                    "com.android.server.biometrics.BiometricCameraManager",
                    false,
                    classLoader,
                ),
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_PRE_AUTH_CONTEXT)
                .intercept(preAuthContextHooker)
            logInfo("OK: PreAuthInfo app context hook")
        }.onFailure {
            logError("FAIL PreAuthInfo context hook", it)
        }
    }

    private fun hookAppFaceStrengthCheck(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName(
                "com.android.server.biometrics.Utils",
                false,
                classLoader,
            )
            val method = utilsClass.getDeclaredMethod(
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_FACE_STRENGTH_CHECK)
                .intercept(faceStrengthCheckHooker)
            logInfo("OK: app-only 4095 strength check")
        }.onFailure {
            logError("FAIL app strength hook", it)
        }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"

        private const val HOOK_ID_PRE_AUTH_CONTEXT = "hook_pre_auth_context"
        private const val HOOK_ID_FACE_STRENGTH_CHECK = "hook_face_strength_check"
        private const val HOOK_ID_SYSTEMUI_AUTH_SUCCESS = "hook_systemui_auth_success"
        private const val HOOK_ID_SYSTEMUI_NEUTRAL_HELP = "hook_systemui_neutral_help"
        private const val HOOK_ID_AUTH_SESSION_SUCCESS = "hook_authsession_success"
        private const val HOOK_ID_AUTH_SESSION_ERROR = "hook_authsession_error"
        private const val HOOK_ID_AUTH_SESSION_REJECTED = "hook_authsession_rejected"
        private const val HOOK_ID_AUTH_SESSION_TIMEOUT = "hook_authsession_timeout"
        private const val HOOK_ID_AUTH_SESSION_DIALOG = "hook_authsession_dialog"
        private const val HOOK_ID_AUTH_SESSION_FP_START = "hook_authsession_fp_start"
        private const val HOOK_ID_AUTH_SESSION_CLEANUP = "hook_authsession_cleanup"

        private const val OEM_STRENGTH_MASK = 4095
        private const val STRONG_AUTHENTICATORS = 15
        private const val WEAK_AUTHENTICATORS = 255
        private const val FACE_MODALITY = 8

        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"
    }
}
