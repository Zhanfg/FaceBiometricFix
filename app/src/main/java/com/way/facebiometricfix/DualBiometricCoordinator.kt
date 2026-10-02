package com.way.facebiometricfix

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.WeakHashMap

/**
 * Enforces FACE -> FINGERPRINT in a single AuthSession.
 *
 * Design goals:
 * - event driven: no polling, timers, or repeated view-tree scans;
 * - fail closed: FACE never becomes the final app success in dual mode;
 * - no face HAT retention: the final framework success uses the fingerprint token;
 * - O(1) session lookup via a small WeakHashMap;
 * - reflection handles are resolved once per AuthSession class.
 */
internal class DualBiometricCoordinator(
    private val logInfo: (String) -> Unit,
    private val logWarn: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) {
    enum class SuccessDecision {
        PROCEED,
        CONSUME,
    }

    private enum class Phase {
        WAIT_FACE,
        WAIT_FINGERPRINT,
        COMPLETE,
    }

    private data class SessionState(
        val packageName: String,
        var phase: Phase = Phase.WAIT_FACE,
        var uiReady: Boolean = false,
        var fingerprintStartRequested: Boolean = false,
    )

    private data class Handles(
        val sessionClass: Class<*>,
        val opPackageName: Field?,
        val preAuthInfo: Field?,
        val getEligibleModalities: Method?,
        val sensorIdToModality: Method?,
        val onStartFingerprint: Method?,
        val startFingerprintSensorsNow: Method?,
        val startAllPreparedFingerprintSensors: Method?,
        val statusBarService: Field?,
    )

    private val sessions = WeakHashMap<Any, SessionState>()

    @Volatile
    private var cachedHandles: Handles? = null

    @Volatile
    private var cachedSensorIdClass: Class<*>? = null

    @Volatile
    private var cachedSensorIdField: Field? = null

    @Volatile
    private var cachedSensorModalityClass: Class<*>? = null

    @Volatile
    private var cachedSensorModalityField: Field? = null

    @Volatile
    private var cachedEligibleSensorsClass: Class<*>? = null

    @Volatile
    private var cachedEligibleSensorsField: Field? = null

    @Volatile
    private var cachedStatusBarClass: Class<*>? = null

    @Volatile
    private var cachedStatusBarHelp: Method? = null

    fun prepare(session: Any?): Boolean {
        if (session == null) return false
        return getOrCreateState(session) != null
    }

    fun shouldDelayFingerprint(session: Any?): Boolean {
        if (session == null) return false
        val state = getOrCreateState(session) ?: return false
        synchronized(state) {
            // In dual mode AuthSession never decides the initial fingerprint start.
            // We release it only after FACE succeeds and the prompt animation is done.
            return state.phase != Phase.COMPLETE && !state.fingerprintStartRequested
        }
    }

    fun shouldBlockFingerprintStart(session: Any?): Boolean {
        if (session == null) return false
        val state = getOrCreateState(session) ?: return false
        synchronized(state) {
            return state.phase != Phase.COMPLETE &&
                (state.phase != Phase.WAIT_FINGERPRINT || !state.uiReady)
        }
    }

    fun onDialogAnimatedIn(session: Any?) {
        if (session == null) return
        val state = getOrCreateState(session) ?: return

        val shouldAdvance = synchronized(state) {
            state.uiReady = true
            state.phase == Phase.WAIT_FINGERPRINT
        }

        if (shouldAdvance) {
            advanceFingerprintStage(session, state)
        }
    }

    fun onAuthenticationSucceeded(session: Any?, sensorId: Int): SuccessDecision {
        if (session == null) return SuccessDecision.PROCEED

        val state = getOrCreateState(session) ?: return SuccessDecision.PROCEED
        val modality = sensorModality(session, sensorId)

        return when (modality) {
            TYPE_FACE -> onFaceSucceeded(session, state, sensorId)
            TYPE_FINGERPRINT -> onFingerprintSucceeded(state)
            else -> SuccessDecision.PROCEED
        }
    }

    fun clear(session: Any?) {
        if (session == null) return
        synchronized(sessions) {
            sessions.remove(session)
        }
    }

    private fun onFaceSucceeded(
        session: Any,
        state: SessionState,
        sensorId: Int,
    ): SuccessDecision {
        synchronized(state) {
            when (state.phase) {
                Phase.WAIT_FACE -> {
                    state.phase = Phase.WAIT_FINGERPRINT
                    logInfo("DualAuth: FACE verified for ${state.packageName}; waiting for fingerprint")
                }

                Phase.WAIT_FINGERPRINT -> return SuccessDecision.CONSUME
                Phase.COMPLETE -> return SuccessDecision.CONSUME
            }
        }

        // Do not cancel FACE here. AuthSession treats the resulting asynchronous
        // BIOMETRIC_ERROR_CANCELED as a session-level cancellation while it still
        // has no authenticated sensor. Keeping FACE alive for the short second
        // stage is safer; the framework cancels it automatically as soon as the
        // fingerprint succeeds. Duplicate FACE callbacks are consumed above.
        //
        // If FACE succeeds before the prompt finishes animating, defer the
        // fingerprint start until onDialogAnimatedIn() has completed. This
        // preserves AOSP's rule that UDFPS must not appear before the prompt UI.
        advanceFingerprintStage(session, state)

        // Deliberately do NOT call AuthSession's original success path here.
        // This avoids mAuthenticatedSensorId/mTokenEscrow being populated by FACE.
        return SuccessDecision.CONSUME
    }

    private fun onFingerprintSucceeded(state: SessionState): SuccessDecision {
        synchronized(state) {
            return when (state.phase) {
                Phase.WAIT_FACE -> {
                    logWarn(
                        "DualAuth: early fingerprint success ignored for ${state.packageName}"
                    )
                    SuccessDecision.CONSUME
                }

                Phase.WAIT_FINGERPRINT -> {
                    state.phase = Phase.COMPLETE
                    logInfo(
                        "DualAuth: FINGERPRINT verified for ${state.packageName}; " +
                            "framework may complete authentication"
                    )
                    SuccessDecision.PROCEED
                }

                Phase.COMPLETE -> SuccessDecision.PROCEED
            }
        }
    }

    private fun getOrCreateState(session: Any): SessionState? {
        synchronized(sessions) {
            sessions[session]?.let { return it }
        }

        val handles = handlesFor(session)
        val packageName = readString(handles.opPackageName, session) ?: return null
        if (DualBiometricPolicy.modeFor(packageName) != BiometricPolicyMode.FACE_THEN_FINGERPRINT) {
            return null
        }

        val modalities = eligibleModalities(session, handles)
        val hasFace = modalities and TYPE_FACE != 0
        val hasFingerprint = modalities and TYPE_FINGERPRINT != 0
        if (!hasFace || !hasFingerprint) return null

        val state = SessionState(packageName)
        synchronized(sessions) {
            return sessions[session] ?: state.also { sessions[session] = it }
        }
    }

    private fun eligibleModalities(session: Any, handles: Handles): Int {
        handles.getEligibleModalities?.let { method ->
            val value = runCatching { method.invoke(session) as? Int }.getOrNull()
            if (value != null) return value
        }

        val preAuth = runCatching { handles.preAuthInfo?.get(session) }.getOrNull() ?: return 0
        val sensorsField = eligibleSensorsField(preAuth.javaClass) ?: return 0
        val sensors = runCatching { sensorsField.get(preAuth) as? Iterable<*> }
            .getOrNull() ?: return 0

        var result = 0
        for (sensor in sensors) {
            if (sensor == null) continue
            result = result or sensorModality(sensor)
            if ((result and TYPE_FACE != 0) && (result and TYPE_FINGERPRINT != 0)) {
                break
            }
        }
        return result
    }

    private fun sensorModality(session: Any, sensorId: Int): Int {
        val handles = handlesFor(session)

        handles.sensorIdToModality?.let { method ->
            val value = runCatching {
                method.invoke(session, sensorId) as? Int
            }.getOrNull()
            if (value != null) return value
        }

        val preAuth = runCatching { handles.preAuthInfo?.get(session) }.getOrNull() ?: return 0
        val sensorsField = eligibleSensorsField(preAuth.javaClass) ?: return 0
        val sensors = runCatching { sensorsField.get(preAuth) as? Iterable<*> }
            .getOrNull() ?: return 0

        for (sensor in sensors) {
            if (sensor == null) continue
            val sensorClass = sensor.javaClass
            val idField = sensorIdField(sensorClass) ?: continue
            val id = runCatching { idField.getInt(sensor) }.getOrNull() ?: continue
            if (id == sensorId) return sensorModality(sensor)
        }
        return 0
    }

    private fun sensorModality(sensor: Any): Int {
        val field = sensorModalityField(sensor.javaClass) ?: return 0
        return runCatching { field.getInt(sensor) }.getOrDefault(0)
    }

    private fun advanceFingerprintStage(session: Any, state: SessionState) {
        val ready = synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT && state.uiReady
        }
        if (!ready) return

        // Do not send the stage transition through IStatusBarService#onBiometricHelp.
        // ColorOS maps "help" to a warning/error visual state (the exclamation icon).
        // Let the native fingerprint stage own the normal affordance instead.
        startFingerprintStage(session, state)
    }

    private fun startFingerprintStage(session: Any, state: SessionState) {
        synchronized(state) {
            if (state.fingerprintStartRequested) return
        }

        val handles = handlesFor(session)

        val started = runCatching {
            when {
                handles.onStartFingerprint != null -> {
                    handles.onStartFingerprint.invoke(session)
                    true
                }

                handles.startFingerprintSensorsNow != null -> {
                    handles.startFingerprintSensorsNow.invoke(session)
                    true
                }

                handles.startAllPreparedFingerprintSensors != null -> {
                    handles.startAllPreparedFingerprintSensors.invoke(session)
                    true
                }

                else -> false
            }
        }.onFailure {
            logError("DualAuth: failed to start fingerprint stage", it)
        }.getOrDefault(false)

        if (started) {
            synchronized(state) {
                state.fingerprintStartRequested = true
            }
            return
        }

        logWarn("DualAuth: no compatible fingerprint-start method; session remains fail-closed")
        notifyStage(
            session,
            TYPE_FINGERPRINT,
            localized(
                zh = "指纹阶段无法启动，请取消后重试",
                en = "Fingerprint stage unavailable. Cancel and retry.",
            ),
        )
    }

    private fun notifyStage(session: Any, modality: Int, message: String) {
        val handles = handlesFor(session)
        val statusBar = runCatching { handles.statusBarService?.get(session) }
            .getOrNull() ?: return

        val method = statusBarHelpMethod(statusBar.javaClass) ?: return
        runCatching {
            method.invoke(statusBar, modality, message)
        }.onFailure {
            logError("DualAuth: failed to update BiometricPrompt help text", it)
        }
    }

    private fun handlesFor(session: Any): Handles {
        val type = session.javaClass
        cachedHandles?.let { if (it.sessionClass === type) return it }

        synchronized(this) {
            cachedHandles?.let { if (it.sessionClass === type) return it }

            val handles = Handles(
                sessionClass = type,
                opPackageName = findField(type, "mOpPackageName"),
                preAuthInfo = findField(type, "mPreAuthInfo"),
                getEligibleModalities = findMethod(type, "getEligibleModalities") {
                    it.parameterTypes.isEmpty()
                },
                sensorIdToModality = findMethod(type, "sensorIdToModality") {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                },
                onStartFingerprint = findMethod(type, "onStartFingerprint") {
                    it.parameterTypes.isEmpty()
                },
                startFingerprintSensorsNow = findMethod(type, "startFingerprintSensorsNow") {
                    it.parameterTypes.isEmpty()
                },
                startAllPreparedFingerprintSensors =
                    findMethod(type, "startAllPreparedFingerprintSensors") {
                        it.parameterTypes.isEmpty()
                    },
                statusBarService = findField(type, "mStatusBarService"),
            )
            cachedHandles = handles
            return handles
        }
    }

    private fun eligibleSensorsField(type: Class<*>): Field? {
        cachedEligibleSensorsField?.let {
            if (cachedEligibleSensorsClass === type) return it
        }

        synchronized(this) {
            cachedEligibleSensorsField?.let {
                if (cachedEligibleSensorsClass === type) return it
            }

            val field = findField(type, "eligibleSensors")
                ?: findField(type, "mEligibleSensors")
            cachedEligibleSensorsClass = type
            cachedEligibleSensorsField = field
            return field
        }
    }

    private fun sensorIdField(type: Class<*>): Field? {
        cachedSensorIdField?.let {
            if (cachedSensorIdClass === type) return it
        }

        synchronized(this) {
            cachedSensorIdField?.let {
                if (cachedSensorIdClass === type) return it
            }

            val field = findField(type, "id")
                ?: findField(type, "sensorId")
                ?: findField(type, "mSensorId")
            cachedSensorIdClass = type
            cachedSensorIdField = field
            return field
        }
    }

    private fun sensorModalityField(type: Class<*>): Field? {
        cachedSensorModalityField?.let {
            if (cachedSensorModalityClass === type) return it
        }

        synchronized(this) {
            cachedSensorModalityField?.let {
                if (cachedSensorModalityClass === type) return it
            }

            val field = findField(type, "modality")
                ?: findField(type, "mModality")
            cachedSensorModalityClass = type
            cachedSensorModalityField = field
            return field
        }
    }

    private fun statusBarHelpMethod(type: Class<*>): Method? {
        cachedStatusBarHelp?.let {
            if (cachedStatusBarClass === type) return it
        }

        synchronized(this) {
            cachedStatusBarHelp?.let {
                if (cachedStatusBarClass === type) return it
            }

            val method = findMethod(type, "onBiometricHelp") {
                val p = it.parameterTypes
                p.size == 2 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == String::class.java
            }

            cachedStatusBarClass = type
            cachedStatusBarHelp = method
            return method
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val cls = current
            runCatching {
                cls.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()?.let { return it }
            current = cls.superclass
        }
        return null
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        predicate: (Method) -> Boolean,
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val cls = current
            cls.declaredMethods.firstOrNull {
                it.name == name && predicate(it)
            }?.let {
                it.isAccessible = true
                return it
            }
            current = cls.superclass
        }
        return null
    }

    private fun readString(field: Field?, owner: Any): String? {
        return runCatching { field?.get(owner) as? String }.getOrNull()
    }

    private fun localized(zh: String, en: String): String {
        return if (Locale.getDefault().language.equals("zh", ignoreCase = true)) zh else en
    }

    private companion object {
        const val TYPE_FINGERPRINT = 2
        const val TYPE_FACE = 8
    }
}
