package com.way.facebiometricfix

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Sequential FACE -> FINGERPRINT orchestration for one AuthSession.
 *
 * Recovery model:
 * - FACE success is consumed and never becomes the final framework success.
 * - FACE is stopped after stage 1; its later CANCELED callback is absorbed safely.
 * - FINGERPRINT start failures fail closed and force-cancel the session.
 * - FINGERPRINT framework timeout closes the current session instead of leaving it paused/stuck.
 * - FACE -> FINGERPRINT has a bounded deadline so an abandoned prompt cannot live forever.
 * - Every terminal/cancel/dismiss/client-death path invalidates delayed work.
 *
 * Hot paths are event-driven. The only delayed work is one bounded stage-deadline Runnable.
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

    enum class ErrorDecision {
        PROCEED,
        CONSUME_FALSE,
    }

    private enum class Phase {
        WAIT_FACE,
        WAIT_FINGERPRINT,
        COMPLETE,
        ABORTING,
        TERMINATED,
    }

    private data class SessionState(
        val packageName: String,
        var phase: Phase = Phase.WAIT_FACE,
        var uiReady: Boolean = false,
        var fingerprintStartRequested: Boolean = false,
        var faceStopRequested: Boolean = false,
        var abortPosted: Boolean = false,
        var deadlineAtElapsedMs: Long = 0L,
        var deadlineRunnable: Runnable? = null,
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
        val pauseSensorIfSupported: Method?,
        val onCancelAuthSession: Method?,
        val cancelAllSensors: Method?,
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

    fun prepare(session: Any?): Boolean {
        if (session == null) return false
        return getOrCreateState(session) != null
    }

    fun shouldDelayFingerprint(session: Any?): Boolean {
        if (session == null) return false
        val state = getOrCreateState(session) ?: return false
        synchronized(state) {
            return state.phase != Phase.COMPLETE &&
                state.phase != Phase.ABORTING &&
                state.phase != Phase.TERMINATED &&
                !state.fingerprintStartRequested
        }
    }

    fun shouldBlockFingerprintStart(session: Any?): Boolean {
        if (session == null) return false
        val state = getOrCreateState(session) ?: return false
        synchronized(state) {
            return when (state.phase) {
                Phase.WAIT_FACE -> true
                Phase.WAIT_FINGERPRINT -> !state.uiReady
                Phase.COMPLETE, Phase.ABORTING, Phase.TERMINATED -> false
            }
        }
    }

    fun onDialogAnimatedIn(session: Any?) {
        if (session == null) return
        val state = getOrCreateState(session) ?: return

        val shouldAdvance = synchronized(state) {
            if (state.phase == Phase.ABORTING || state.phase == Phase.TERMINATED) {
                false
            } else {
                state.uiReady = true
                state.phase == Phase.WAIT_FINGERPRINT
            }
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

    fun beforeErrorReceived(
        session: Any?,
        sensorId: Int,
        cookie: Int,
        error: Int,
    ): ErrorDecision {
        if (session == null) return ErrorDecision.PROCEED
        val state = findState(session) ?: return ErrorDecision.PROCEED

        val consumeFaceError = synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT &&
                sensorModality(session, sensorId) == TYPE_FACE
        }

        if (!consumeFaceError) return ErrorDecision.PROCEED

        markSensorStopped(session, sensorId, cookie, error)
        logInfo(
            "DualAuth: absorbed post-FACE sensor error=$error for " +
                "${state.packageName}; fingerprint stage remains active"
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
            val terminal = synchronized(state) {
                if (state.phase != Phase.WAIT_FINGERPRINT) {
                    false
                } else {
                    state.phase = Phase.ABORTING
                    cancelDeadlineLocked(state)
                    true
                }
            }
            if (terminal) {
                logWarn(
                    "DualAuth: fingerprint error=$error entered native terminal recovery for " +
                        state.packageName
                )
            }
        }
    }

    fun shouldConsumeRejected(session: Any?, sensorId: Int): Boolean {
        if (session == null) return false
        val state = findState(session) ?: return false
        return synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT &&
                sensorModality(session, sensorId) == TYPE_FACE
        }
    }

    fun shouldConsumeTimeout(session: Any?, sensorId: Int): Boolean {
        if (session == null) return false
        val state = findState(session) ?: return false
        return synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT &&
                sensorModality(session, sensorId) == TYPE_FACE
        }
    }

    fun afterAuthenticationTimedOut(session: Any?, sensorId: Int) {
        if (session == null) return
        val state = findState(session) ?: return
        if (sensorModality(session, sensorId) != TYPE_FINGERPRINT) return

        val shouldAbort = synchronized(state) {
            state.phase == Phase.WAIT_FINGERPRINT
        }
        if (shouldAbort) {
            postAbort(session, state, "fingerprint_framework_timeout")
        }
    }

    fun clear(session: Any?, reason: String = "framework_cleanup") {
        if (session == null) return

        val state = synchronized(sessions) {
            sessions.remove(session)
        } ?: return

        synchronized(state) {
            state.phase = Phase.TERMINATED
            cancelDeadlineLocked(state)
        }
        logInfo("DualAuth: cleared ${state.packageName}; reason=$reason")
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
                    logInfo(
                        "DualAuth: FACE verified for ${state.packageName}; " +
                            "waiting for fingerprint"
                    )
                }

                Phase.WAIT_FINGERPRINT,
                Phase.COMPLETE,
                Phase.ABORTING,
                Phase.TERMINATED,
                -> return SuccessDecision.CONSUME
            }
        }

        scheduleStageDeadline(session, state)
        stopFaceStage(session, state, sensorId)
        advanceFingerprintStage(session, state)

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
                    cancelDeadlineLocked(state)
                    logInfo(
                        "DualAuth: FINGERPRINT verified for ${state.packageName}; " +
                            "framework may complete authentication"
                    )
                    SuccessDecision.PROCEED
                }

                Phase.COMPLETE -> SuccessDecision.PROCEED
                Phase.ABORTING, Phase.TERMINATED -> SuccessDecision.CONSUME
            }
        }
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
