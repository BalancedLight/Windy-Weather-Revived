package com.BalancedLight.WindyWeather

import android.content.Context

internal interface DistributionServiceIntegration {
    fun start()
    fun stop()
    fun onWeatherSourceChanged(sourceMode: String?)
}

internal interface DistributionSettingsHooks {
    fun hasSamsungWeatherPermission(): Boolean
    fun requestSamsungWeatherPermission()
    fun refreshStatus()
}

internal object NoOpDistributionServiceIntegration : DistributionServiceIntegration {
    override fun start() = Unit
    override fun stop() = Unit
    override fun onWeatherSourceChanged(sourceMode: String?) = Unit
}

internal typealias DistributionRefreshRequest = (origin: String) -> Unit

internal fun interface DistributionContextAction {
    fun run(context: Context)
}
