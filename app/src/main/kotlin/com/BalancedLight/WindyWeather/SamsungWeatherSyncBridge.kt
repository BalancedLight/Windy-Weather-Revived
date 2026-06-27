package com.BalancedLight.WindyWeather

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

import android.content.ContentResolver
import android.content.Intent

internal class SamsungWeatherSyncBridge(context: Context) {
    private val mContext: Context
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mObserver: ContentObserver? = null
    private var mStarted = false
    private var mRegistered = false
    private var mPolling = false

    @Volatile
    private var mPollInFlight = false

    @Volatile
    private var mLastSnapshotFingerprint: String? = null
    private val mPollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!mStarted || !shouldObserve()) {
                return
            }
            pollOnce()
            if (mPolling && mStarted && shouldObserve()) {
                mHandler.postDelayed(
                    this,
                    com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.POLL_INTERVAL_MS
                )
            }
        }
    }

    init {
        this.mContext = context.applicationContext
    }

    fun start() {
        this.mStarted = true
        evaluateState("start")
    }

    fun stop() {
        this.mStarted = false
        stopPolling()
        unregister()
    }

    fun onModeChanged(mode: String?) {
        evaluateState("mode_changed")
    }

    fun onSyncSettingChanged() {
        evaluateState("sync_setting_changed")
    }

    private fun evaluateState(reason: String?) {
        if (!this.mStarted) {
            return
        }
        if (!shouldObserve()) {
            stopPolling()
            unregister()
            return
        }
        register(reason)
        startPolling(reason)
    }

    private fun shouldObserve(): Boolean {
        if (!SecretWallpaperService.isAeroWeatherRefreshSyncEnabled(this.mContext)) {
            return false
        }
        return SamsungWeatherRepository.isLikelySupported(this.mContext)
    }

    private fun register(reason: String?) {
        if (this.mRegistered) {
            return
        }
        val resolver: ContentResolver = this.mContext.contentResolver
        if (this.mObserver == null) {
            this.mObserver = object : ContentObserver(this.mHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    handleProviderChanged(uri)
                }

                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    handleProviderChanged(null)
                }
            }
        }

        var count = 0
        val observer = this.mObserver ?: return
        for (uriText in SamsungWeatherRepository.observerUris) {
            try {
                resolver.registerContentObserver(Uri.parse(uriText), true, observer)
                count++
            } catch (e: SecurityException) {
                Log.d(
                    com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
                    "Observer blocked uri=" + uriText + " msg=" + e.message
                )
            } catch (e2: Exception) {
                Log.d(
                    com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
                    "Observer register failed uri=" + uriText + " msg=" + e2.message
                )
            }
        }
        this.mRegistered = count > 0
        Log.d(
            com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
            "Observer register reason=" + reason + " count=" + count
        )
    }

    private fun unregister() {
        if (!this.mRegistered || this.mObserver == null) {
            return
        }
        try {
            this.mContext.contentResolver.unregisterContentObserver(this.mObserver ?: return)
        } catch (e: Exception) {
            Log.d(
                com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
                "Observer unregister failed: " + e.message
            )
        }
        this.mRegistered = false
    }

    private fun startPolling(reason: String?) {
        if (this.mPolling) {
            return
        }
        this.mPolling = true
        this.mHandler.removeCallbacks(this.mPollRunnable)
        this.mHandler.post(this.mPollRunnable)
        Log.d(
            com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
            "Polling started reason=" + reason
        )
    }

    private fun stopPolling() {
        if (!this.mPolling) {
            return
        }
        this.mPolling = false
        this.mPollInFlight = false
        this.mHandler.removeCallbacks(this.mPollRunnable)
        this.mLastSnapshotFingerprint = null
        Log.d(
            com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
            "Polling stopped"
        )
    }

    private fun pollOnce() {
        if (this.mPollInFlight) {
            return
        }
        this.mPollInFlight = true
        Thread(label@{
            try {
                val snapshot: SamsungWeatherRepository.SamsungSnapshot? =
                    SamsungWeatherRepository.fetchLatest(this.mContext)
                val fingerprint = buildFingerprint(snapshot)
                if (fingerprint == null) {
                    return@label
                }
                val previousFingerprint = this.mLastSnapshotFingerprint
                this.mLastSnapshotFingerprint = fingerprint
                if (previousFingerprint != null && !previousFingerprint.equals(fingerprint)) {
                    this.mHandler.post({ dispatchProviderChange("poll", null) })
                }
            } finally {
                this.mPollInFlight = false
            }
        }, "ww-samsung-sync-poll").start()
    }

    private fun buildFingerprint(snapshot: SamsungWeatherRepository.SamsungSnapshot?): String? {
        if (snapshot == null || !snapshot.hasAnyData()) {
            return null
        }
        return (java.lang.String.valueOf(snapshot.lastUpdatedMs)
                + '|'
                + java.lang.String.valueOf(snapshot.weatherCode)
                + '|'
                + java.lang.String.valueOf(snapshot.currentTempC)
                + '|'
                + java.lang.String.valueOf(snapshot.highTempC)
                + '|'
                + java.lang.String.valueOf(snapshot.lowTempC)
                + '|'
                + java.lang.String.valueOf(snapshot.humidityPercent)
                + '|'
                + java.lang.String.valueOf(snapshot.windSpeedKmh)
                + '|'
                + java.lang.String.valueOf(snapshot.sunriseTime)
                + '|'
                + java.lang.String.valueOf(snapshot.sunsetTime)
                + '|'
                + java.lang.String.valueOf(snapshot.cityName))
    }

    private fun handleProviderChanged(uri: Uri?) {
        dispatchProviderChange("observer", uri)
    }

    private fun dispatchProviderChange(trigger: String?, uri: Uri?) {
        if (!shouldObserve()) {
            evaluateState("provider_change_no_longer_eligible")
            return
        }
        val now: Long = System.currentTimeMillis()
        val lastTrigger: Long =
            SecretWallpaperService.getLastSamsungObserverTriggerMs(this.mContext)
        if (lastTrigger > 0L && now - lastTrigger < com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.OBSERVER_DEBOUNCE_MS) {
            Log.d(
                com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
                "Debounced provider change trigger=" + trigger + " uri=" + uri + " ageMs=" + (now - lastTrigger)
            )
            return
        }
        val lastImmediate: Long =
            SecretWallpaperService.getLastHybridImmediateRefreshMs(this.mContext)
        if (lastImmediate > 0L && now - lastImmediate < com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.HYBRID_MIN_INTERVAL_MS) {
            SecretWallpaperService.setLastSamsungObserverTriggerMs(this.mContext, now)
            Log.d(
                com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
                "Cooldown provider change trigger=" + trigger + " uri=" + uri + " ageMs=" + (now - lastImmediate)
            )
            return
        }

        SecretWallpaperService.setLastSamsungObserverTriggerMs(this.mContext, now)
        SecretWallpaperService.setLastHybridImmediateRefreshMs(this.mContext, now)
        SecretWallpaperService.setSamsungNudgeSuppressUntilMs(
            this.mContext,
            now + com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.NUDGE_SUPPRESS_MS
        )
        SecretWallpaperService.setLastSyncOrigin(
            this.mContext,
            SecretWallpaperService.ORIGIN_SAMSUNG_OBSERVER
        )
        val intent: Intent = Intent(SecretWallpaperService.ACTION_SAMSUNG_PROVIDER_CHANGED_INTERNAL)
        intent.setPackage(this.mContext.packageName)
        this.mContext.sendBroadcast(intent)
        Log.d(
            com.BalancedLight.WindyWeather.SamsungWeatherSyncBridge.Companion.TAG,
            "Provider change dispatched trigger=" + trigger + " uri=" + uri
        )
    }

    companion object {
        private val TAG = "SamsungWeatherSync"
        private val OBSERVER_DEBOUNCE_MS = 60L * 1000L
        private val HYBRID_MIN_INTERVAL_MS = 3L * 60L * 1000L
        private val NUDGE_SUPPRESS_MS = 5L * 60L * 1000L
        private val POLL_INTERVAL_MS = 10L * 1000L
    }
}
