package com.way.facebiometricfix

import android.view.View
import android.view.ViewTreeObserver
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

        val observer = root.viewTreeObserver
        if (!observer.isAlive) return

        lateinit var preDraw: ViewTreeObserver.OnPreDrawListener
        lateinit var attach: View.OnAttachStateChangeListener

        fun remove() {
            synchronized(guards) {
                guards.remove(root)
            }
            runCatching {
                val current = root.viewTreeObserver
                if (current.isAlive) current.removeOnPreDrawListener(preDraw)
            }
            runCatching { root.removeOnAttachStateChangeListener(attach) }
        }

        preDraw = ViewTreeObserver.OnPreDrawListener {
            if (!root.isAttachedToWindow) {
                remove()
                return@OnPreDrawListener true
            }

            val confirm = root.findViewById<View>(confirmId)
            if (
                confirm != null &&
                confirm.visibility == View.VISIBLE &&
                confirm.isEnabled
            ) {
                if (iconId != 0) {
                    root.findViewById<View>(iconId)?.let(::suppressVisualOnly)
                }
                if (overlayId != 0) {
                    root.findViewById<View>(overlayId)?.let(::suppressVisualOnly)
                }
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
        logInfo("OK: installed prompt-local biometric icon guard")
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
