package com.way.facebiometricfix

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.WeakHashMap
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
        val authController = chain.thisObject
        val result = chain.proceed()

        if (modality == FACE_MODALITY) {
            schedulePostFaceCleanup(authController)
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

    private fun schedulePostFaceCleanup(authController: Any?) {
        val handler = Handler(Looper.getMainLooper())
        POST_FACE_CLEANUP_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                runCatching {
                    // Pass 1: remove a real UDFPS overlay if ColorOS re-created it
                    // after AuthController#onBiometricAuthenticated returned.
                    hideUdfpsOverlayFromAuthController(authController)

                    // Pass 2: inspect the actual current BiometricPrompt tree. This
                    // does not depend on vendor class names and also provides the
                    // runtime class/resource evidence needed if ColorOS moved the
                    // icon into an OEM view.
                    cleanupCurrentPromptByGeometry(authController, delay)
                }.onFailure {
                    logError("FAIL post-face cleanup at ${delay}ms", it)
                }
            }, delay)
        }
    }

    private fun cleanupCurrentPromptByGeometry(authController: Any?, delayMs: Long) {
        val root = findCurrentDialogView(authController) ?: run {
            logWarn("PROBE[${delayMs}ms]: current BiometricPrompt view not found")
            return
        }

        val visibleViews = mutableListOf<View>()
        collectVisibleViews(root, visibleViews)

        val confirm = visibleViews.firstOrNull { isLikelyConfirmationView(it) } ?: run {
            logWarn(
                "PROBE[${delayMs}ms]: confirmation view not found; root=" +
                    root.javaClass.name
            )
            dumpPromptViews(visibleViews, delayMs, null)
            return
        }

        installPromptCollisionGuard(root)

        val confirmRect = Rect()
        if (!confirm.getGlobalVisibleRect(confirmRect) || confirmRect.isEmpty) {
            logWarn("PROBE[${delayMs}ms]: confirmation view has no visible rect")
            return
        }

        val candidates = visibleViews.filter { view ->
            view !== confirm &&
                view.visibility == View.VISIBLE &&
                view.alpha > 0f &&
                isLikelyBiometricIcon(view) &&
                overlapRatio(view, confirmRect) >= MIN_CONFIRM_OVERLAP_RATIO
        }

        dumpPromptViews(visibleViews, delayMs, confirm)

        if (candidates.isEmpty()) {
            logWarn(
                "PROBE[${delayMs}ms]: no overlapping biometric candidate; " +
                    "confirm=${describeView(confirm)}"
            )
            return
        }

        candidates.forEach { view ->
            logInfo(
                "FIX[${delayMs}ms]: hiding overlapping biometric view " +
                    describeView(view)
            )
            hardHideBiometricView(view)
        }
    }

    /**
     * ColorOS 16 runtime evidence shows the exact overlapping pair is
     * biometric_icon / biometric_icon_overlay, both BiometricPromptLottieViewWrapper.
     *
     * A one-shot visibility change is not strong enough if the prompt state machine
     * later marks the wrappers visible again. Keep this guard local to the current
     * BiometricPrompt root and suppress only those two views immediately before draw
     * while the real confirmation button is visible.
     */
    private fun installPromptCollisionGuard(root: View) {
        if (PROMPT_GUARDS.containsKey(root)) return

        val confirmId = resourceId(root, "button_confirm")
        val iconId = resourceId(root, "biometric_icon")
        val overlayId = resourceId(root, "biometric_icon_overlay")
        if (confirmId == 0 || (iconId == 0 && overlayId == 0)) return

        val observer = root.viewTreeObserver
        if (!observer.isAlive) return

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!root.isAttachedToWindow) {
                    removePromptCollisionGuard(root, this)
                    return true
                }

                val confirm = root.findViewById<View>(confirmId)
                if (
                    confirm == null ||
                    confirm.visibility != View.VISIBLE ||
                    !confirm.isEnabled
                ) {
                    return true
                }

                if (iconId != 0) {
                    root.findViewById<View>(iconId)?.let { hardHideBiometricView(it) }
                }
                if (overlayId != 0) {
                    root.findViewById<View>(overlayId)?.let { hardHideBiometricView(it) }
                }
                return true
            }
        }

        PROMPT_GUARDS[root] = listener
        observer.addOnPreDrawListener(listener)
        logInfo("OK: installed prompt-local pre-draw guard for biometric icon overlap")
    }

    private fun removePromptCollisionGuard(
        root: View,
        listener: ViewTreeObserver.OnPreDrawListener
    ) {
        runCatching {
            val observer = root.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        PROMPT_GUARDS.remove(root)
    }

    private fun hardHideBiometricView(view: View) {
        // IMPORTANT: keep this view in ConstraintLayout measurement/layout.
        // ColorOS anchors the confirmation area around these wrappers. GONE
        // removes them from layout and can stretch the prompt to almost full
        // screen. INVISIBLE suppresses rendering while preserving geometry.
        runCatching { view.animate().cancel() }
        runCatching { view.clearAnimation() }
        runCatching { findNoArgMethod(view.javaClass, "cancelAnimation")?.invoke(view) }
        runCatching { findNoArgMethod(view.javaClass, "pauseAnimation")?.invoke(view) }

        view.isClickable = false
        view.isFocusable = false
        view.visibility = View.INVISIBLE
    }

    private fun resourceId(root: View, name: String): Int {
        return runCatching {
            root.resources.getIdentifier(name, "id", SYSTEM_UI_PACKAGE)
        }.getOrDefault(0)
    }

    private fun findCurrentDialogView(authController: Any?): View? {
        if (authController == null) return null

        var type: Class<*>? = authController.javaClass
        while (type != null) {
            val fields = type.declaredFields

            fields.firstOrNull { it.name == "mCurrentDialog" }?.let { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(authController)
                }.getOrNull()
                if (value is View) return value
            }

            for (field in fields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(authController)
                }.getOrNull()
                if (value is View) {
                    val className = value.javaClass.name
                    if (
                        className.contains("AuthContainer", ignoreCase = true) ||
                        className.contains("Biometric", ignoreCase = true)
                    ) {
                        return value
                    }
                }
            }

            type = type.superclass
        }
        return null
    }

    private fun collectVisibleViews(root: View, out: MutableList<View>) {
        if (root.visibility != View.VISIBLE || root.alpha <= 0f) return
        out.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectVisibleViews(root.getChildAt(i), out)
            }
        }
    }

    private fun isLikelyConfirmationView(view: View): Boolean {
        val resource = resourceEntryName(view)
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val className = view.javaClass.name

        return (
            resource.contains("confirm", ignoreCase = true) ||
                resource.contains("positive", ignoreCase = true) ||
                resource.contains("continue", ignoreCase = true) ||
                text.equals("继续", ignoreCase = true) ||
                text.equals("确认", ignoreCase = true) ||
                text.equals("Continue", ignoreCase = true) ||
                text.equals("Confirm", ignoreCase = true) ||
                className.contains("Button", ignoreCase = true) &&
                (text.contains("继续") || text.contains("确认"))
            ) &&
            view.visibility == View.VISIBLE &&
            view.isEnabled
    }

    private fun isLikelyBiometricIcon(view: View): Boolean {
        val resource = resourceEntryName(view)
        val className = view.javaClass.name
        val haystack = "$resource $className".lowercase()

        if (
            haystack.contains("finger") ||
            haystack.contains("udfps") ||
            haystack.contains("biometric") ||
            haystack.contains("icon")
        ) {
            return true
        }

        // OEM fallback: the offending control in ColorOS is a compact,
        // approximately square affordance sitting over the confirmation button.
        val w = view.width
        val h = view.height
        return w in MIN_OEM_ICON_PX..MAX_OEM_ICON_PX &&
            h in MIN_OEM_ICON_PX..MAX_OEM_ICON_PX &&
            kotlin.math.abs(w - h) <= OEM_ICON_SQUARE_TOLERANCE_PX
    }

    private fun overlapRatio(view: View, target: Rect): Float {
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect) || rect.isEmpty) return 0f

        val intersection = Rect(rect)
        if (!intersection.intersect(target)) return 0f

        val area = rect.width().toLong() * rect.height().toLong()
        if (area <= 0L) return 0f

        val overlap = intersection.width().toLong() * intersection.height().toLong()
        return overlap.toFloat() / area.toFloat()
    }

    private fun dumpPromptViews(views: List<View>, delayMs: Long, confirm: View?) {
        logInfo(
            "PROBE[${delayMs}ms]: root tree visibleViews=${views.size}" +
                if (confirm != null) " confirm=${describeView(confirm)}" else ""
        )

        views
            .filter { view ->
                val name = resourceEntryName(view)
                val cls = view.javaClass.name
                val text = (view as? TextView)?.text?.toString().orEmpty()
                name.isNotBlank() ||
                    cls.contains("Biometric", true) ||
                    cls.contains("Fingerprint", true) ||
                    cls.contains("Udfps", true) ||
                    cls.contains("Button", true) ||
                    text.isNotBlank()
            }
            .take(MAX_PROBE_LINES)
            .forEach { logInfo("PROBE[${delayMs}ms]: ${describeView(it)}") }
    }

    private fun describeView(view: View): String {
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        val text = (view as? TextView)?.text?.toString()
            ?.replace("\n", " ")
            ?.take(40)
            .orEmpty()

        return "class=${view.javaClass.name} id=${resourceEntryName(view)} " +
            "rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}] " +
            "size=${view.width}x${view.height} vis=${view.visibility} " +
            "enabled=${view.isEnabled} clickable=${view.isClickable} " +
            "alpha=${view.alpha} text=${text}"
    }

    private fun resourceEntryName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(view.id) }
            .getOrDefault("")
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

        private val POST_FACE_CLEANUP_DELAYS_MS = longArrayOf(0L, 80L, 180L, 360L)
        private const val MIN_CONFIRM_OVERLAP_RATIO = 0.18f
        private const val MIN_OEM_ICON_PX = 48
        private const val MAX_OEM_ICON_PX = 280
        private const val OEM_ICON_SQUARE_TOLERANCE_PX = 72
        private const val MAX_PROBE_LINES = 80

        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()
        private val PROMPT_GUARDS =
            WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"

        @Volatile
        private var systemUiAuthCleanupInstalled = false

        @Volatile
        private var systemUiPromptCleanupInstalled = false
    }
}
