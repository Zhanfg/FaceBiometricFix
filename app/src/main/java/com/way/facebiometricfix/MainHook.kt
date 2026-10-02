package com.way.facebiometricfix

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class MainHook : XposedModule() {

    private fun logInfo(msg: String) {
        log(Log.INFO, TAG, msg)
    }

    private fun logWarn(msg: String) {
        log(Log.WARN, TAG, msg)
    }

    private fun logError(msg: String, tr: Throwable) {
        log(Log.ERROR, TAG, msg, tr)
    }

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
            sensorStrength == OEM_STRENGTH_MASK &&
            (requestedStrength == STRONG_AUTHENTICATORS ||
                requestedStrength == WEAK_AUTHENTICATORS)
        ) {
            return@Hooker true
        }

        // Keep the exact ColorOS implementation for all other paths,
        // especially SystemUI and provider/lockout operations.
        val maskedStrength = sensorStrength and 32767
        if (((requestedStrength.inv()) and maskedStrength) != 0) return@Hooker false

        var candidate = 1
        while (candidate <= requestedStrength) {
            if (candidate == maskedStrength) return@Hooker true
            candidate = (candidate shl 1) or 1
        }
        false
    }

    /**
     * ColorOS can leave the UDFPS overlay visible after FACE succeeds in a
     * multi-biometric BiometricPrompt. When the prompt then enters explicit
     * confirmation state, the optical fingerprint affordance can sit on top of
     * the "Continue" button.
     *
     * AuthController#onBiometricAuthenticated is specific to BiometricPrompt
     * (not keyguard), so this cleanup does not alter lock-screen UDFPS.
     */
    private val systemUiAuthSuccessHooker = XposedInterface.Hooker { chain ->
        val modality = runCatching { chain.getArg(0) as? Int }.getOrNull()
        val result = chain.proceed()

        if (modality == FACE_MODALITY) {
            runCatching {
                hideUdfpsOverlayFromAuthController(chain.thisObject)
            }.onFailure {
                logError("FAIL: hide UDFPS overlay after face auth", it)
            }
        }

        result
    }

    /**
     * The screenshot is not the standalone UDFPS window: it is the combined
     * face+fingerprint prompt icon used as an alternate confirmation control.
     * ColorOS can place that icon at the physical UDFPS location while also
     * rendering the regular confirmation button, so the two controls overlap.
     *
     * Keep Android's confirmation semantics intact. Only when the combined
     * prompt has already entered PENDING_CONFIRMATION and a real visible,
     * enabled confirmation button exists do we hide the redundant icon layer.
     */
    private val systemUiPromptStateHooker = XposedInterface.Hooker { chain ->
        val newState = runCatching { chain.getArg(0) as? Int }.getOrNull()
        val result = chain.proceed()

        if (newState == STATE_PENDING_CONFIRMATION) {
            runCatching {
                hideRedundantCombinedPromptIcon(chain.thisObject)
            }.onFailure {
                logError("FAIL: clean combined biometric confirmation icon", it)
            }
        }

        result
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        logInfo("hooking biometrics in system_server ...")
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
        var replacedPreAuth = false
        var replacedStrength = false
        var replacedSystemUi = false
        var replacedPromptState = false
        var sawSystemUiHook = false
        var targetClassLoader: ClassLoader? = null

        for (oldHandle in param.oldHookHandles) {
            if (targetClassLoader == null) {
                targetClassLoader = oldHandle.executable.declaringClass.classLoader
            }

            when (oldHandle.id) {
                HOOK_ID_PRE_AUTH_CONTEXT -> {
                    runCatching {
                        oldHandle.replaceHook(preAuthContextHooker)
                        replacedPreAuth = true
                        logInfo("OK: hot-replaced PreAuthInfo app context hook")
                    }.onFailure {
                        logError("FAIL hot-replace PreAuthInfo hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }

                HOOK_ID_FACE_STRENGTH_CHECK -> {
                    runCatching {
                        oldHandle.replaceHook(faceStrengthCheckHooker)
                        replacedStrength = true
                        logInfo("OK: hot-replaced app-only 4095 strength check")
                    }.onFailure {
                        logError("FAIL hot-replace strength hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }

                HOOK_ID_SYSTEMUI_AUTH_SUCCESS -> {
                    sawSystemUiHook = true
                    runCatching {
                        oldHandle.replaceHook(systemUiAuthSuccessHooker)
                        replacedSystemUi = true
                        logInfo("OK: hot-replaced SystemUI face-success UDFPS cleanup hook")
                    }.onFailure {
                        logError("FAIL hot-replace SystemUI cleanup hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }

                HOOK_ID_SYSTEMUI_PROMPT_STATE -> {
                    sawSystemUiHook = true
                    runCatching {
                        oldHandle.replaceHook(systemUiPromptStateHooker)
                        replacedPromptState = true
                        logInfo("OK: hot-replaced combined-prompt collision cleanup hook")
                    }.onFailure {
                        logError("FAIL hot-replace prompt-state hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }

                else -> {
                    runCatching { oldHandle.unhook() }
                        .onFailure { logError("FAIL unhook old handle ${oldHandle.id}", it) }
                }
            }
        }

        val classLoader = targetClassLoader ?: Thread.currentThread().contextClassLoader
        if (classLoader == null) {
            logWarn("Hot reload fallback skipped: target ClassLoader unavailable")
            return
        }

        if (sawSystemUiHook) {
            if (!replacedSystemUi || !replacedPromptState) {
                installSystemUiHooks(classLoader)
            }
            return
        }

        if (!replacedPreAuth || !replacedStrength) {
            if (!replacedPreAuth) hookAppPreAuthContext(classLoader)
            if (!replacedStrength) hookAppFaceStrengthCheck(classLoader)
        }
    }

    private fun installSystemServerHooks(classLoader: ClassLoader) {
        hookAppPreAuthContext(classLoader)
        hookAppFaceStrengthCheck(classLoader)
    }

    private fun installSystemUiHooks(classLoader: ClassLoader) {
        if (!systemUiAuthCleanupInstalled) {
            runCatching {
                val authControllerClass = Class.forName(
                    "com.android.systemui.biometrics.AuthController",
                    false,
                    classLoader
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

                systemUiAuthCleanupInstalled = true
                logInfo("OK: SystemUI face-success UDFPS cleanup hook")
            }.onFailure {
                logError("FAIL SystemUI UDFPS cleanup hook", it)
            }
        }

        if (!systemUiPromptCleanupInstalled) {
            runCatching {
                val biometricViewClass = Class.forName(
                    "com.android.systemui.biometrics.AuthBiometricView",
                    false,
                    classLoader
                )

                val method = biometricViewClass.declaredMethods.firstOrNull {
                    it.name == "updateState" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: error("AuthBiometricView#updateState(int) not found")

                method.isAccessible = true
                hook(method)
                    .setId(HOOK_ID_SYSTEMUI_PROMPT_STATE)
                    .intercept(systemUiPromptStateHooker)

                systemUiPromptCleanupInstalled = true
                logInfo("OK: combined biometric confirmation collision cleanup hook")
            }.onFailure {
                logError("FAIL combined prompt-state cleanup hook", it)
            }
        }
    }

    private fun hideRedundantCombinedPromptIcon(authViewObject: Any?) {
        val root = authViewObject as? View ?: return
        if (!isCombinedFaceFingerprintView(root.javaClass)) return

        val confirm = findViewByResourceName(root, "button_confirm")
            ?: findViewField(root, "mConfirmButton", "mPositiveButton")
            ?: run {
                logWarn("PENDING_CONFIRMATION: confirmation button not found; leaving icon untouched")
                return
            }

        // Never remove the icon when it is the only confirmation affordance.
        if (confirm.visibility != View.VISIBLE || !confirm.isEnabled) return

        val targets = linkedSetOf<View>()
        findViewByResourceName(root, "biometric_icon")?.let { targets.add(it) }
        findViewByResourceName(root, "biometric_icon_overlay")?.let { targets.add(it) }
        findViewByResourceName(root, "biometric_icon_frame")?.let { targets.add(it) }
        findViewField(root, "mIconView")?.let { targets.add(it) }
        findViewField(root, "mIconViewOverlay")?.let { targets.add(it) }
        findViewField(root, "mIconHolderView")?.let { targets.add(it) }

        if (targets.isEmpty()) {
            logWarn("PENDING_CONFIRMATION: combined biometric icon views not found")
            return
        }

        targets.forEach { view ->
            view.visibility = View.INVISIBLE
            view.isClickable = false
        }
        logInfo("OK: hid redundant combined biometric icon; normal confirmation button retained")
    }

    private fun isCombinedFaceFingerprintView(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            val name = current.name
            if (
                name.contains("FingerprintAndFace", ignoreCase = true) ||
                name.contains("FaceAndFingerprint", ignoreCase = true) ||
                name.contains("BiometricCoex", ignoreCase = true)
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun findViewByResourceName(root: View, name: String): View? {
        val id = runCatching {
            root.resources.getIdentifier(name, "id", SYSTEM_UI_PACKAGE)
        }.getOrDefault(0)
        if (id == 0) return null
        return root.findViewById(id)
    }

    private fun findViewField(owner: Any, vararg names: String): View? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            for (name in names) {
                val value = runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(owner)
                }.getOrNull()
                if (value is View) return value
            }
            type = type.superclass
        }
        return null
    }

    /**
     * Find AuthController's UdfpsController instance without hard-coding one
     * field name. AOSP uses mUdfpsController, while ColorOS may wrap or rename
     * it between releases.
     */
    private fun hideUdfpsOverlayFromAuthController(authController: Any?) {
        if (authController == null) return

        val udfpsController = findUdfpsController(authController) ?: run {
            logWarn("SystemUI face auth succeeded but UdfpsController was not found")
            return
        }

        val hideMethod = findNoArgMethod(udfpsController.javaClass, "hideUdfpsOverlay") ?: run {
            logWarn(
                "SystemUI UdfpsController found (${udfpsController.javaClass.name}) " +
                    "but hideUdfpsOverlay() is unavailable"
            )
            return
        }

        hideMethod.isAccessible = true
        hideMethod.invoke(udfpsController)
        logInfo("OK: hid UDFPS overlay after face authentication")
    }

    private fun findUdfpsController(owner: Any): Any? {
        var type: Class<*>? = owner.javaClass

        while (type != null) {
            val fields = type.declaredFields

            // Fast path: AOSP field name.
            fields.firstOrNull { it.name == "mUdfpsController" }?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()?.let { return it }
            }

            // ColorOS fallback: locate by declared/runtime type.
            for (field in fields) {
                val declaredLooksLikeUdfps =
                    field.type.name.contains("UdfpsController", ignoreCase = true)

                val value = runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()

                val runtimeLooksLikeUdfps =
                    value?.javaClass?.name?.contains("UdfpsController", ignoreCase = true) == true

                if (value != null && (declaredLooksLikeUdfps || runtimeLooksLikeUdfps)) {
                    return value
                }
            }

            type = type.superclass
        }

        return null
    }

    private fun findNoArgMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type

        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.let { return it }
            current = current.superclass
        }

        return null
    }

    /**
     * 在 PreAuthInfo 计算普通应用资格期间记录 opPackageName。
     *
     * isAtLeastStrength() 在 system_server 内部执行时无法可靠取得原始调用者 UID，
     * 所以必须从 PreAuthInfo 的参数传递调用上下文。
     */
    private fun hookAppPreAuthContext(classLoader: ClassLoader) {
        runCatching {
            val preAuthInfoClass = Class.forName(
                "com.android.server.biometrics.PreAuthInfo",
                false,
                classLoader
            )
            val method = preAuthInfoClass.getDeclaredMethod(
                "getStatusForBiometricAuthenticator",
                Class.forName("android.app.admin.DevicePolicyManager", false, classLoader),
                Class.forName(
                    "com.android.server.biometrics.BiometricService\$SettingObserver",
                    false,
                    classLoader
                ),
                Class.forName("com.android.server.biometrics.BiometricSensor", false, classLoader),
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                Class.forName(
                    "com.android.server.biometrics.BiometricCameraManager",
                    false,
                    classLoader
                )
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_PRE_AUTH_CONTEXT)
                .intercept(preAuthContextHooker)
            logInfo("OK: PreAuthInfo app context hook")
        }.onFailure {
            logError("FAIL PreAuthInfo context hook", it)
        }
    }

    /** 仅在普通 App 的预检查调用链中放行 4095 人脸强度。 */
    private fun hookAppFaceStrengthCheck(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName(
                "com.android.server.biometrics.Utils",
                false,
                classLoader
            )
            val method = utilsClass.getDeclaredMethod(
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
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
        private const val HOOK_ID_SYSTEMUI_PROMPT_STATE = "hook_systemui_prompt_state"

        /** 厂商私有强度掩码（人脸传感器上报值） */
        private const val OEM_STRENGTH_MASK = 4095
        private const val STRONG_AUTHENTICATORS = 15
        private const val WEAK_AUTHENTICATORS = 255
        private const val FACE_MODALITY = 8
        private const val STATE_PENDING_CONFIRMATION = 5

        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"

        @Volatile
        private var systemUiAuthCleanupInstalled = false

        @Volatile
        private var systemUiPromptCleanupInstalled = false
    }
}
