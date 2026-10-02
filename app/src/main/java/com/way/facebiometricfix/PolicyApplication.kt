package com.way.facebiometricfix

import android.app.Application
import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

class PolicyApplication :
    Application(),
    XposedServiceHelper.OnServiceListener {

    @Volatile
    private var xposedService: XposedService? = null

    private val serviceListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    lateinit var policyStore: PolicyStore
        private set

    override fun onCreate() {
        super.onCreate()
        policyStore = PolicyStore(this) {
            runCatching {
                xposedService?.getRemotePreferences(PolicyConfig.PREF_GROUP)
            }.getOrNull()
        }
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        policyStore.onRemoteAvailable()
        serviceListeners.forEach { it(true) }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
        }
        serviceListeners.forEach { it(false) }
    }

    fun isFrameworkConnected(): Boolean = xposedService != null

    fun addServiceListener(listener: (Boolean) -> Unit) {
        serviceListeners += listener
        listener(isFrameworkConnected())
    }

    fun removeServiceListener(listener: (Boolean) -> Unit) {
        serviceListeners -= listener
    }

    companion object {
        fun from(context: Context): PolicyApplication =
            context.applicationContext as PolicyApplication
    }
}
