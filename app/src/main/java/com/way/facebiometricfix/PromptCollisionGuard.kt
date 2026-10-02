package com.way.facebiometricfix

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Event-driven ColorOS BiometricPrompt collision fix.
 *
 * No timers and no frame-wide tree scans: resource IDs are resolved once when
 * FACE succeeds, then a prompt-local pre-draw guard only touches the two
 * confirmed Lottie wrapper views while the real confirmation button is visible.
 */
internal class PromptCollisionGuard(
    private val logInfo: (String) -> Unit,
    private val logWarn: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) {
    private data class Guard(
        val preDraw: ViewTreeObserver.OnPreDrawListener,
        val attach: View.OnAttachStateChangeListener,
    )

    private val guards = WeakHashMap<View, Guard>()
    private val verifiedBadges = WeakHashMap<View, TextView>()

    fun onFaceAuthenticated(authController: Any?) {
        if (authController == null) return

        runCatching { hideUdfpsOverlay(authController) }
            .onFailure { logError("FAIL: hide UDFPS overlay after face auth", it) }

        val root = findCurrentDialogView(authController) ?: run {
            logWarn("BiometricPrompt root unavailable after face auth")
            return
        }

        install(root)
    }

    fun showDualAuthState(authController: Any?, state: String, message: String) {
        if (authController == null) return
        val root = findCurrentDialogView(authController) ?: return
        updateIndicator(root, message)

        when (state) {
            DualBiometricCoordinator.UI_STATE_FINGERPRINT_VERIFIED -> {
                // Fingerprint is already the completed first factor: stop the
                // UDFPS affordance and replace its prompt icon with a check mark.
                runCatching { hideUdfpsOverlay(authController) }
                    .onFailure { logError("FAIL: hide UDFPS after fingerprint factor", it) }
                showVerifiedBadge(root)
            }

            DualBiometricCoordinator.UI_STATE_WAIT_BOTH,
            DualBiometricCoordinator.UI_STATE_FACE_VERIFIED,
            -> clearVerifiedBadge(root)

            DualBiometricCoordinator.UI_STATE_COMPLETE -> {
                // Keep a previously shown check until the dialog dismisses.
            }
        }
    }
    fun showNeutralGuidance(authController: Any?, message: String) {
        if (authController == null || message.isBlank()) return
        val root = findCurrentDialogView(authController) ?: return
        updateIndicator(root, message)
    }

    private fun updateIndicator(root: View, message: String) {
        if (message.isBlank()) return
        val indicatorId = resourceId(root, "indicator")
        if (indicatorId == 0) return
        val indicator = root.findViewById<TextView>(indicatorId) ?: return

        indicator.post {
            if (!indicator.isAttachedToWindow) return@post
            indicator.text = message
            indicator.contentDescription = message
            indicator.visibility = View.VISIBLE
        }
    }

    private fun showVerifiedBadge(root: View) {
        val host = root as? ViewGroup ?: return
        val iconId = resourceId(root, "biometric_icon")
        val overlayId = resourceId(root, "biometric_icon_overlay")
        val icon = if (iconId != 0) root.findViewById<View>(iconId) else null
        val iconOverlay = if (overlayId != 0) root.findViewById<View>(overlayId) else null
        val target = icon ?: iconOverlay ?: return

        icon?.let(::suppressVisualOnly)
        iconOverlay?.let(::suppressVisualOnly)

        val badge = synchronized(verifiedBadges) {
            verifiedBadges[root] ?: TextView(root.context).also {
                verifiedBadges[root] = it
            }
        }

        val targetLocation = IntArray(2)
        val rootLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        root.getLocationInWindow(rootLocation)

        val width = target.width.coerceAtLeast(1)
        val height = target.height.coerceAtLeast(1)
        val left = targetLocation[0] - rootLocation[0]
        val top = targetLocation[1] - rootLocation[1]

        badge.text = "✓"
        badge.gravity = Gravity.CENTER
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.58f)
        badge.contentDescription = "已经过身份验证"
        badge.isClickable = false
        badge.isFocusable = false
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        val accent = TypedValue()
        if (root.context.theme.resolveAttribute(android.R.attr.colorAccent, accent, true)) {
            badge.setTextColor(accent.data)
        } else {
            val indicatorId = resourceId(root, "indicator")
            val indicator = if (indicatorId != 0) root.findViewById<TextView>(indicatorId) else null
            indicator?.let { badge.setTextColor(it.currentTextColor) }
        }

        if (!badge.isAttachedToWindow) {
            host.overlay.add(badge)
        }
        badge.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        badge.layout(left, top, left + width, top + height)
        badge.visibility = View.VISIBLE
        logInfo("OK: fingerprint first-factor check mark shown")
    }

    private fun clearVerifiedBadge(root: View) {
        val host = root as? ViewGroup ?: return
        val badge = synchronized(verifiedBadges) {
            verifiedBadges.remove(root)
        } ?: return
        runCatching { host.overlay.remove(badge) }
    }
    private fun install(root: View) {
        synchronized(guards) {
            if (guards.containsKey(root)) return
        }

        val confirmId = resourceId(root, "button_confirm")
        val iconId = resourceId(root, "biometric_icon")
        val overlayId = resourceId(root, "biometric_icon_overlay")

        if (confirmId == 0 || (iconId == 0 && overlayId == 0)) {
            logWarn("BiometricPrompt collision resources unavailable")
            return
        }

        // Resolve the three concrete views once. The pre-draw hot path performs
        // only direct field checks; no resource lookup or tree traversal per frame.
        val confirm = root.findViewById<View>(confirmId) ?: return
        val icon = if (iconId != 0) root.findViewById<View>(iconId) else null
        val overlay = if (overlayId != 0) root.findViewById<View>(overlayId) else null
        if (icon == null && overlay == null) return

        val observer = root.viewTreeObserver
        if (!observer.isAlive) return

        val rootRef = WeakReference(root)
        val confirmRef = WeakReference(confirm)
        val iconRef = WeakReference(icon)
        val overlayRef = WeakReference(overlay)

        lateinit var preDraw: ViewTreeObserver.OnPreDrawListener
        lateinit var attach: View.OnAttachStateChangeListener

        fun remove() {
            val liveRoot = rootRef.get() ?: return
            synchronized(guards) {
                guards.remove(liveRoot)
            }
            runCatching {
                val current = liveRoot.viewTreeObserver
                if (current.isAlive) current.removeOnPreDrawListener(preDraw)
            }
            runCatching { liveRoot.removeOnAttachStateChangeListener(attach) }
        }

        preDraw = ViewTreeObserver.OnPreDrawListener {
            val liveRoot = rootRef.get()
            if (liveRoot == null || !liveRoot.isAttachedToWindow) {
                remove()
                return@OnPreDrawListener true
            }

            val liveConfirm = confirmRef.get()
            if (
                liveConfirm != null &&
                liveConfirm.visibility == View.VISIBLE &&
                liveConfirm.isEnabled
            ) {
                iconRef.get()?.let(::suppressVisualOnly)
                overlayRef.get()?.let(::suppressVisualOnly)
            }
            true
        }

        attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = remove()
        }

        synchronized(guards) {
            if (guards.containsKey(root)) return
            guards[root] = Guard(preDraw, attach)
        }

        observer.addOnPreDrawListener(preDraw)
        root.addOnAttachStateChangeListener(attach)
        logInfo("OK: installed zero-scan prompt-local biometric icon guard")
    }

    private fun suppressVisualOnly(view: View) {
        if (view.visibility == View.INVISIBLE && !view.isClickable && !view.isFocusable) {
            return
        }

        runCatching { view.animate().cancel() }
        runCatching { view.clearAnimation() }
        runCatching { findNoArgMethod(view.javaClass, "cancelAnimation")?.invoke(view) }
        runCatching { findNoArgMethod(view.javaClass, "pauseAnimation")?.invoke(view) }

        // INVISIBLE is deliberate: GONE breaks ColorOS ConstraintLayout geometry.
        view.isClickable = false
        view.isFocusable = false
        view.visibility = View.INVISIBLE
    }

    private fun findCurrentDialogView(authController: Any): View? {
        var type: Class<*>? = authController.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == "mCurrentDialog" }?.let { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(authController)
                }.getOrNull()
                if (value is View) return value
            }

            for (field in type.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(authController)
                }.getOrNull()
                if (value is View) {
                    val name = value.javaClass.name
                    if (
                        name.contains("AuthContainer", ignoreCase = true) ||
                        name.contains("Biometric", ignoreCase = true)
                    ) {
                        return value
                    }
                }
            }

            type = type.superclass
        }
        return null
    }

    private fun hideUdfpsOverlay(authController: Any) {
        val udfpsController = findUdfpsController(authController) ?: return
        val hide = findNoArgMethod(udfpsController.javaClass, "hideUdfpsOverlay") ?: return
        hide.isAccessible = true
        hide.invoke(udfpsController)
    }

    private fun findUdfpsController(owner: Any): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                val declaredMatch =
                    field.name == "mUdfpsController" ||
                        field.type.name.contains("UdfpsController", ignoreCase = true)

                val value = runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()

                if (
                    value != null &&
                    (declaredMatch ||
                        value.javaClass.name.contains("UdfpsController", ignoreCase = true))
                ) {
                    return value
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun resourceId(root: View, name: String): Int {
        return runCatching {
            root.resources.getIdentifier(name, "id", SYSTEM_UI_PACKAGE)
        }.getOrDefault(0)
    }

    private fun findNoArgMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
