package com.way.facebiometricfix

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Order-independent 2-of-2 biometric orchestration for one AuthSession.
 *
 * FACE and FINGERPRINT may succeed in either order. The first factor is retained
 * only inside this short-lived session; the second factor completes the Android
 * AuthSession. When fingerprint is first, its real HAT is replayed as the final
 * framework success after FACE succeeds, preserving the strong-token path.
 *
 * FACE capture windows are transparently re-armed instead of becoming unusable
 * after the OEM timeout. Hot paths are event-driven; only one bounded
 * second-factor deadline Runnable exists per session.
 */
internal class DualBiometricCoordinator(
    private val logInfo: (String) -> Unit,
    private val logWarn: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) {
    enum class SuccessDecision {
        PROCEED_CURRENT,
        PROCEED_STORED_FINGERPRINT,
        CONSUME,
    }

    data class SuccessAction(
        val decision: SuccessDecision,
        val sensorId: Int = -1,
        val strong: Boolean = false,
        val token: ByteArray? = null,
    )

    enum class ErrorDecision {
        PROCEED,
        CONSUME_FALSE,
    }

    private enum class Phase {
        WAIT_BOTH,
        WAIT_FACE,
        WAIT_FINGERPRINT,
        COMPLETE,
        ABORTING,
        TERMINATED,
    }

    private data class SessionState(
        val packageName: String,
        var phase: Phase = Phase.WAIT_BOTH,
        var uiReady: Boolean = false,
        var verifiedMask: Int = 0,
        var fingerprintSensorId: Int = -1,
        var fingerprintStrong: Boolean = false,
        var fingerprintToken: ByteArray? = null,
        var faceRestartPending: Boolean = false,
        var abortPosted: Boolean = false,
        var deadlineAtElapsedMs: Long = 0L,
        var deadlineRunnable: Runnable? = null,
        var lastGuidance: String? = null,
    )

    private data class Handles(
        val sessionClass: Class<*>,
        val opPackageName: Field?,
        val preAuthInfo: Field?,
        val getEligibleModalities: Method?,
        val sensorIdToModality: Method?,
        val pauseSensorIfSupported: Method?,
        val onTryAgainPressed: Method?,
        val onCancelAuthSession: Method?,
        val cancelAllSensors: Method?,
        val statusBarService: Field?,
    )
    private val sessions = WeakHashMap<Any, SessionState>()
    private val handler = Handler(Looper.getMainLooper())

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
    private var cachedSensorStopClass: Class<*>? = null

    @Volatile
    private var cachedSensorStopMethod: Method? = null

    @Volatile
    private var cachedSensorCookieClass: Class<*>? = null

    @Volatile
    private var cachedSensorCookieMethod: Method? = null

    @Volatile
    private var cachedStatusBarClass: Class<*>? = null

    @Volatile
    private var cachedStatusBarHelp: Method? = null

    fun prepare(session: Any?): Boolean {
        if (session == null) return false
        return getOrCreateState(session) != null
    }

    fun shouldForceFingerprintStart(session: Any?): Boolean {
        if (session == null) return false
        val state = getOrCreateState(session) ?: return false
        synchronized(state) {
            return state.phase != Phase.COMPLETE &&
                state.phase != Phase.ABORTING &&
                state.phase != Phase.TERMINATED
        }
    }

    fun onDialogAnimatedIn(session: Any?) {
        if (session == null) return
        val state = getOrCreateState(session) ?: return
        synchronized(state) {
            if (state.phase == Phase.ABORTING || state.phase == Phase.TERMINATED) return
            state.uiReady = true
        }
        sendGuidanceForCurrentState(session, state)
    }

    fun onAuthenticationSucceeded(
        session: Any?,
        sensorId: Int,
        strong: Boolean,
        token: ByteArray?,
    ): SuccessAction {
        if (session == null) return SuccessAction(SuccessDecision.PROCEED_CURRENT)
        val state = getOrCreateState(session)
            ?: return SuccessAction(SuccessDecision.PROCEED_CURRENT)
        val modality = sensorModality(session, sensorId)
        if (modality != TYPE_FACE && modality != TYPE_FINGERPRINT) {
            return SuccessAction(SuccessDecision.PROCEED_CURRENT)
        }

        synchronized(state) {
            if (state.phase == Phase.ABORTING || state.phase == Phase.TERMINATED) {
                return SuccessAction(SuccessDecision.CONSUME)
            }

            val bit = if (modality == TYPE_FACE) VERIFIED_FACE else VERIFIED_FINGERPRINT
            if (state.verifiedMask and bit != 0) {
                return SuccessAction(SuccessDecision.CONSUME)
            }
            state.verifiedMask = state.verifiedMask or bit

            if (modality == TYPE_FINGERPRINT) {
                state.fingerprintToken?.fill(0)
                state.fingerprintSensorId = sensorId
                state.fingerprintStrong = strong
                state.fingerprintToken = token?.clone()
            }

            markSuccessfulSensorStopped(session, sensorId)

            val hasFace = state.verifiedMask and VERIFIED_FACE != 0
            val hasFingerprint = state.verifiedMask and VERIFIED_FINGERPRINT != 0
            if (hasFace && hasFingerprint) {
                state.phase = Phase.COMPLETE
                cancelDeadlineLocked(state)
                logInfo(
                    "DualAuth: both factors verified for ${state.packageName}; final=" +
                        if (modality == TYPE_FACE) "FACE" else "FINGERPRINT"
                )

                if (modality == TYPE_FINGERPRINT) {
                    return SuccessAction(SuccessDecision.PROCEED_CURRENT)
                }

                if (state.fingerprintSensorId >= 0) {
                    return SuccessAction(
                        decision = SuccessDecision.PROCEED_STORED_FINGERPRINT,
                        sensorId = state.fingerprintSensorId,
                        strong = state.fingerprintStrong,
                        token = state.fingerprintToken?.clone(),
                    )
                }

                logWarn("DualAuth: fingerprint completion cache missing; using current callback")
                return SuccessAction(SuccessDecision.PROCEED_CURRENT)
            }

            state.phase = if (hasFace) Phase.WAIT_FINGERPRINT else Phase.WAIT_FACE
            scheduleSecondFactorDeadlineLocked(session, state)
            logInfo(
                "DualAuth: first factor " +
                    (if (modality == TYPE_FACE) "FACE" else "FINGERPRINT") +
                    " verified for ${state.packageName}; waiting for the other factor"
            )
        }

        sendGuidanceForCurrentState(session, state)
        return SuccessAction(SuccessDecision.CONSUME)
    }
    fun beforeErrorReceived(
        session: Any?,
        sensorId: Int,
        cookie: Int,
        error: Int,
    ): ErrorDecision {
        if (session == null) return ErrorDecision.PROCEED
        val state = findState(session) ?: return ErrorDecision.PROCEED
        if (sensorModality(session, sensorId) != TYPE_FACE) return ErrorDecision.PROCEED

        val shouldConsume = synchronized(state) {
            state.faceRestartPending ||
                state.verifiedMask and VERIFIED_FACE != 0
        }
        if (!shouldConsume) return ErrorDecision.PROCEED

        markSensorStopped(session, sensorId, cookie, error)
        val restart = synchronized(state) {
            val pending = state.faceRestartPending &&
                (state.phase == Phase.WAIT_BOTH || state.phase == Phase.WAIT_FACE)
            state.faceRestartPending = false
            pending
        }

        if (restart) {
            postFaceRetry(session, state)
        }

        logInfo(
            "DualAuth: absorbed FACE sensor error=$error for ${state.packageName}" +
                if (restart) "; scheduling re-arm" else ""
        )
        return ErrorDecision.CONSUME_FALSE
    }

    fun afterErrorReceived(
        session: Any?,
        sensorId: Int,
        error: Int,
        sessionFinished: Boolean,
    ) {
        if (session == null) return
        val state = findState(session) ?: return

        if (sessionFinished) {
            clear(session, "framework_error_finished:$error")
            return
        }

        if (sensorModality(session, sensorId) == TYPE_FINGERPRINT) {
            logWarn(
                "DualAuth: fingerprint error=$error for ${state.packageName}; " +
                    "preserving Android recovery semantics"
            )
        }
    }

    fun onAuthenticationRejected(session: Any?, sensorId: Int): Boolean {
        if (session == null) return false
        val state = findState(session) ?: return false
        if (sensorModality(session, sensorId) != TYPE_FACE) return false

        val needsFace = synchronized(state) {
            state.phase == Phase.WAIT_BOTH || state.phase == Phase.WAIT_FACE
        }
        if (!needsFace) return true

        requestFaceRestart(session, state, sensorId, "face_rejected")
        return true
    }

    fun onAuthenticationTimedOut(session: Any?, sensorId: Int): Boolean {
        if (session == null) return false
        val state = findState(session) ?: return false
        if (sensorModality(session, sensorId) != TYPE_FACE) return false

        val needsFace = synchronized(state) {
            state.phase == Phase.WAIT_BOTH || state.phase == Phase.WAIT_FACE
        }
        if (!needsFace) return true

        requestFaceRestart(session, state, sensorId, "face_timeout")
        return true
    }

    fun clear(session: Any?, reason: String = "framework_cleanup") {
        if (session == null) return
        val state = synchronized(sessions) { sessions.remove(session) } ?: return

        synchronized(state) {
            state.phase = Phase.TERMINATED
            cancelDeadlineLocked(state)
            state.fingerprintToken?.fill(0)
            state.fingerprintToken = null
            state.faceRestartPending = false
        }
        logInfo("DualAuth: cleared ${state.packageName}; reason=$reason")
    }
    private fun getOrCreateState(session: Any): SessionState? {
        findState(session)?.let { return it }

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

    private fun findState(session: Any): SessionState? {
        synchronized(sessions) {
            return sessions[session]
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

        val sensor = findSensor(session, sensorId) ?: return 0
        return sensorModality(sensor)
    }

    private fun sensorModality(sensor: Any): Int {
        val field = sensorModalityField(sensor.javaClass) ?: return 0
        return runCatching { field.getInt(sensor) }.getOrDefault(0)
    }

    private fun findSensor(session: Any, sensorId: Int): Any? {
        val handles = handlesFor(session)
        val preAuth = runCatching { handles.preAuthInfo?.get(session) }.getOrNull() ?: return null
        val sensorsField = eligibleSensorsField(preAuth.javaClass) ?: return null
        val sensors = runCatching { sensorsField.get(preAuth) as? Iterable<*> }
            .getOrNull() ?: return null

        for (sensor in sensors) {
            if (sensor == null) continue
            val idField = sensorIdField(sensor.javaClass) ?: continue
            val id = runCatching { idField.getInt(sensor) }.getOrNull() ?: continue
            if (id == sensorId) return sensor
        }
        return null
    }

    private fun stopFaceStage(session: Any, state: SessionState, sensorId: Int) {
        val shouldStop = synchronized(state) {
            if (state.faceStopRequested) {
                false
            } else {
                state.faceStopRequested = true
                true
            }
        }
        if (!shouldStop) return

        val method = handlesFor(session).pauseSensorIfSupported ?: run {
            logWarn("DualAuth: FACE stop helper unavailable; continuing bounded stage")
            return
        }

        runCatching {
            method.invoke(session, sensorId)
        }.onSuccess {
            logInfo("DualAuth: requested FACE sensor stop after stage-1 success")
        }.onFailure {
            logError("DualAuth: failed to stop FACE sensor; continuing bounded stage", it)
        }
    }

    private fun markSensorStopped(
        session: Any,
        sensorId: Int,
        cookie: Int,
        error: Int,
    ) {
        val sensor = findSensor(session, sensorId) ?: return
        val method = sensorStopMethod(sensor.javaClass) ?: return
        runCatching {
            method.invoke(sensor, cookie, error)
        }.onFailure {
            logError("DualAuth: failed to mark consumed FACE error as stopped", it)
        }
    }

    private fun advanceFingerprintStage(session: Any, state: SessionState) {
        val ready = synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT &&
                state.uiReady &&
                !state.fingerprintStartRequested
        }
        if (!ready) return

        startFingerprintStage(session, state)
    }

    private fun startFingerprintStage(session: Any, state: SessionState) {
        val method = handlesFor(session).let { handles ->
            handles.onStartFingerprint
                ?: handles.startFingerprintSensorsNow
                ?: handles.startAllPreparedFingerprintSensors
        }

        if (method == null) {
            postAbort(session, state, "fingerprint_start_method_missing")
            return
        }

        val started = runCatching {
            method.invoke(session)
            true
        }.onFailure {
            logError("DualAuth: fingerprint stage start threw", it)
        }.getOrDefault(false)

        if (!started) {
            postAbort(session, state, "fingerprint_start_failed")
            return
        }

        synchronized(state) {
            if (state.phase == Phase.WAIT_FINGERPRINT) {
                state.fingerprintStartRequested = true
            }
        }
        logInfo("DualAuth: fingerprint stage started for ${state.packageName}")
    }

    private fun scheduleStageDeadline(session: Any, state: SessionState) {
        synchronized(state) {
            cancelDeadlineLocked(state)
            if (state.phase != Phase.WAIT_FINGERPRINT) return

            state.deadlineAtElapsedMs = SystemClock.elapsedRealtime() + FINGERPRINT_STAGE_TIMEOUT_MS
            val sessionRef = WeakReference(session)
            val stateRef = WeakReference(state)
            val deadline = state.deadlineAtElapsedMs

            val runnable = Runnable {
                val liveSession = sessionRef.get() ?: return@Runnable
                val liveState = stateRef.get() ?: return@Runnable

                val expired = synchronized(liveState) {
                    liveState.phase == Phase.WAIT_FINGERPRINT &&
                        liveState.deadlineAtElapsedMs == deadline &&
                        SystemClock.elapsedRealtime() >= deadline
                }

                if (expired) {
                    postAbort(liveSession, liveState, "fingerprint_stage_deadline")
                }
            }

            state.deadlineRunnable = runnable
            handler.postDelayed(runnable, FINGERPRINT_STAGE_TIMEOUT_MS)
        }
    }

    private fun postAbort(session: Any, state: SessionState, reason: String) {
        val shouldPost = synchronized(state) {
            if (
                state.phase == Phase.COMPLETE ||
                state.phase == Phase.ABORTING ||
                state.phase == Phase.TERMINATED ||
                state.abortPosted
            ) {
                false
            } else {
                state.phase = Phase.ABORTING
                state.abortPosted = true
                cancelDeadlineLocked(state)
                true
            }
        }
        if (!shouldPost) return

        val sessionRef = WeakReference(session)
        val stateRef = WeakReference(state)
        handler.post {
            val liveSession = sessionRef.get() ?: return@post
            val liveState = stateRef.get() ?: return@post
            if (findState(liveSession) !== liveState) return@post

            logWarn("DualAuth: aborting ${liveState.packageName}; reason=$reason")
            forceCancelSession(liveSession)
        }
    }

    private fun forceCancelSession(session: Any) {
        val handles = handlesFor(session)
        val cancelled = runCatching {
            handles.onCancelAuthSession?.invoke(session, true)
            handles.onCancelAuthSession != null
        }.onFailure {
            logError("DualAuth: forced AuthSession cancellation failed", it)
        }.getOrDefault(false)

        if (cancelled) return

        runCatching {
            handles.cancelAllSensors?.invoke(session)
        }.onFailure {
            logError("DualAuth: fallback sensor cancellation failed", it)
        }
    }

    private fun cancelDeadlineLocked(state: SessionState) {
        state.deadlineRunnable?.let(handler::removeCallbacks)
        state.deadlineRunnable = null
        state.deadlineAtElapsedMs = 0L
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
                pauseSensorIfSupported = findMethod(type, "pauseSensorIfSupported") {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                },
                onCancelAuthSession = findMethod(type, "onCancelAuthSession") {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                },
                cancelAllSensors = findMethod(type, "cancelAllSensors") {
                    it.parameterTypes.isEmpty()
                },
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

    private fun sensorStopMethod(type: Class<*>): Method? {
        cachedSensorStopMethod?.let {
            if (cachedSensorStopClass === type) return it
        }

        synchronized(this) {
            cachedSensorStopMethod?.let {
                if (cachedSensorStopClass === type) return it
            }

            val method = findMethod(type, "goToStoppedStateIfCookieMatches") {
                val p = it.parameterTypes
                p.size == 2 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Int::class.javaPrimitiveType
            }
            cachedSensorStopClass = type
            cachedSensorStopMethod = method
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

    private companion object {
        const val TYPE_FINGERPRINT = 2
        const val TYPE_FACE = 8
        const val FINGERPRINT_STAGE_TIMEOUT_MS = 15_000L
    }
}
