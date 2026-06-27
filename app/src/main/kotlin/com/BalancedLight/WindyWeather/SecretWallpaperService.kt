package com.BalancedLight.WindyWeather

import android.app.AlertDialog
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.opengl.GLUtils
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.PowerManager
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import android.opengl.GLU
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.opengles.GL11
import javax.microedition.khronos.opengles.GL11Ext

class SecretWallpaperService : GLWallpaperService() {
    private var mSamsungSyncBridge: SamsungWeatherSyncBridge? = null
    private var mReceiver: WeatherReceiver? = null
    private var mAeroWeatherSyncReceiver: AeroWeatherSyncReceiver? = null
    private val TESTMODE = false
    private lateinit var mContext: Context
    private var mLoadedImageset: Int =
        com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
    private var mLoadedImagesetDayNight = false
    private var mbImageSetLoading = false
    private var mbSurfaceCreated = false
    private var mbIsPreview = false
    private var mnSunriseTime = 600
    private var mnSunsetTime = 1800
    private var mnHighTemp = 0
    private var mnLowTemp = 0
    private var mnCurrentTemp = 0
    private var mnHumidityPercent = 0
    private var currentWindSpeedKmh = 0.0f
    private var isBelowFreezingNow = false
    private var isHighHumidityNow = false
    private var mbIsNight = false
    private var mbManySnows = true
    private var mOrientation = 0
    private var mUnlock = false
    private var prevCond: WeatherConditions =
        com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D0_NOTHING
    private var prevWeatherStartTime: Long = 0
    private var prevWeatherChangedDone = false
    private var mEnableLogo = true
    private var mLastWeatherRefreshMs = 0L
    private var mNetworkCallback: NetworkCallback? = null
    private val mWeatherHandler: WeatherHandler =
        com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherHandler(this)

    private class WeatherHandler(service: SecretWallpaperService?) : Handler() {
        private val mServiceRef: WeakReference<SecretWallpaperService?>

        init {
            mServiceRef = WeakReference(service)
        }

        override fun handleMessage(msg: Message) {
            val service: SecretWallpaperService? = mServiceRef.get()
            if (service == null) {
                return
            }
            when (msg.what) {
                300 -> service.showLocationConsentAlertDialog(service.mContext)
                310 -> service.updateWeatherInfo()
                320 -> service.startCurrentLocationWeatherDataService()
                330 -> com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService?.setImageSetChange(true)
            }
        }
    }

    enum class WeatherConditions {
        D0_NOTHING,
        D1_CLEAR,
        D2_CLOUDY,
        D3_DREARY,
        D4_FOG,
        D5_RAIN_SHOWERS,
        D6_THUNDERSTORMS,
        D7_FLURRIES_SNOW,
        D8_ICE_COLD,
        D9_SLEET,
        D10_MOSTLY_CLEAR
    }

    override fun onCreate() {
        Log.d("WindyWeather", "Wallpaper create")
        super.onCreate()
        initCscFeature()
        initService()
    }

    override fun onDestroy() {
        Log.d("WindyWeather", "Wallpaper destroy")
        unregisterNetworkCallback()
        super.onDestroy()
        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mTimeTickReceiver != null) {
            unregisterReceiver(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mTimeTickReceiver)
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mTimeTickReceiver = null
        }
        if (this.mReceiver != null) {
            unregisterReceiver(this.mReceiver)
            this.mReceiver = null
        }
        if (this.mAeroWeatherSyncReceiver != null) {
            unregisterReceiver(this.mAeroWeatherSyncReceiver)
            this.mAeroWeatherSyncReceiver = null
        }
        if (this.mSamsungSyncBridge != null) {
            this.mSamsungSyncBridge?.stop()
            this.mSamsungSyncBridge = null
        }
    }

    override fun onCreateEngine(): WallpaperService.Engine {
        super.onCreateEngine()
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mWallpaperEngine =
            CSPWallpaperEngine(this.mContext)
        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.setEnableLogo(
            this.mEnableLogo
        )
        return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mWallpaperEngine!!
    }

    val engine: CSPWallpaperEngine?
        get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mWallpaperEngine

    private fun initCscFeature() {
        val pref: SharedPreferences =
            getSharedPreferences("com.BalancedLight.WindyWeather", Context.MODE_PRIVATE)
        this.mEnableLogo = pref.getBoolean(
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SHOW_LEGACY_LOGO,
            false
        )
    }

    private fun initService() {
        val filter: IntentFilter = IntentFilter("android.intent.action.TIME_TICK")
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mTimeTickReceiver =
            TimeTickReceiver()
        registerReceiverCompat(
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mTimeTickReceiver,
            filter
        )
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mConnManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        this.mContext = getApplicationContext()
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService = this
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref =
            getSharedPreferences("com.BalancedLight.WindyWeather", Context.MODE_PRIVATE)
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref?.getInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_WEATHER_CONDITION,
                com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
            ) ?: com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref?.getInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_PREV_WEATHER_CONDITION,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
            ) ?: com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
        this.mEnableLogo = this.isLegacyLogoVisible
        this.mbIsNight = this.isNightEffective
        registerNetworkCallback()
        this.mSamsungSyncBridge = SamsungWeatherSyncBridge(this.mContext)
        this.mSamsungSyncBridge?.start()
        val filter2: IntentFilter = IntentFilter()
        filter2.addAction("android.intent.action.USER_PRESENT")
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_FORCE_WEATHER_REFRESH)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_WEATHER_REFRESH_INTERVAL)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_TARGET_FPS)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_POWER_SAVE_TARGET_FPS)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_FRAME_RATE_DEPENDENT_ANIMATION)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_GROUND_PARALLAX)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_FORCED_SCENE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_FORCED_WEATHER_CODE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_OLD_NIGHT_EFFECT)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_CITY_NAME_VISIBLE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_LEGACY_LOGO_VISIBLE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_DAY_NIGHT_MODE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_HIDE_THUNDER_RAINDROPS_LEGACY)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_FREEZING_FROST)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_HUMIDITY_WATERDROP)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_DELAY_SNOW_GROUND)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_CLASSIC_WATERMARK)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_TEXTURE_PACK)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_WEATHER_SOURCE_MODE)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_AEROWEATHER_REFRESH_SYNC)
        filter2.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SAMSUNG_PROVIDER_CHANGED_INTERNAL)
        this.mReceiver = WeatherReceiver()
        registerReceiverCompat(this.mReceiver, filter2)
        val aeroFilter: IntentFilter = IntentFilter()
        aeroFilter.addAction(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_AEROWEATHER_SYNC_REFRESH)
        this.mAeroWeatherSyncReceiver =
            AeroWeatherSyncReceiver()
        registerReceiverCompatExported(this.mAeroWeatherSyncReceiver, aeroFilter)
        updateWeatherInfo()
        val stale: Boolean = WeatherDataCoordinator.isCacheStale(
            this.mContext,
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_CACHE_STALE_MS
        )
        if (stale && this.isWeatherRefreshEnabled) {
            requestWeatherRefresh(
                true,
                false,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
            )
        } else {
            this.mLastWeatherRefreshMs = System.currentTimeMillis()
            Log.d("WindyWeather", "Using fresh weather cache at startup")
        }
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver?, filter: IntentFilter) {
        if (receiver == null) {
            return
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun registerReceiverCompatExported(
        receiver: BroadcastReceiver?,
        filter: IntentFilter
    ) {
        if (receiver == null) {
            return
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun registerNetworkCallback() {
        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mConnManager == null || this.mNetworkCallback != null) {
            return
        }
        try {
            this.mNetworkCallback = object : NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (this@SecretWallpaperService.isWeatherRefreshEnabled) {
                        this@SecretWallpaperService.mWeatherHandler.sendMessage(
                            this@SecretWallpaperService.mWeatherHandler.obtainMessage(320)
                        )
                    }
                }
            }
            val callback = this.mNetworkCallback ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mConnManager?.registerNetworkCallback(
                request,
                callback
            )
        } catch (e: Exception) {
            Log.w("WindyWeather", "Unable to register network callback", e)
            this.mNetworkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val connManager = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mConnManager
        val callback = this.mNetworkCallback
        if (connManager == null || callback == null) {
            return
        }
        try {
            connManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("WindyWeather", "Unable to unregister network callback", e)
        } finally {
            this.mNetworkCallback = null
        }
    }

    fun startCurrentLocationWeatherDataService() {
        requestWeatherRefresh(false, false)
    }

    fun stopCurrentLocationWeatherDataService() {
        this.mLastWeatherRefreshMs = 0L
    }

    fun showLocationConsentAlertDialog(context: Context?) {
        if (context == null) {
            return
        }
        val intentLocSet: Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intentLocSet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intentLocSet)
        } catch (e: Exception) {
            Log.e("WindyWeather", "Unable to open location settings", e)
        }
    }

    private fun requestWeatherRefresh(force: Boolean, manualSource: Boolean) {
        requestWeatherRefresh(
            force,
            manualSource,
            if (manualSource) com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER else com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
        )
    }

    private fun requestWeatherRefresh(
        force: Boolean,
        manualSource: Boolean = false,
        origin: String? = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
    ) {
        if (!manualSource && !this.isWeatherRefreshEnabled) {
            Log.d(
                "WindyWeather",
                "Weather refresh interval is off; skipping automatic refresh"
            )
            return
        }
        val now: Long = System.currentTimeMillis()
        val refreshIntervalMs = this.weatherRefreshIntervalMs
        if (!force && refreshIntervalMs > 0L && now - this.mLastWeatherRefreshMs < refreshIntervalMs) {
            return
        }
        this.mLastWeatherRefreshMs = now
        recordSyncOrigin(origin)
        val sourceMode = this.weatherSourceMode
        WeatherDataCoordinator.refreshAsync(this.mContext, sourceMode, { snapshot ->
            if (updateWeatherInfo()) {
                setImageSetChange(true)
            }
            SamsungRefreshNudger.tryNudge(this.mContext, origin)
        })
    }

    fun convertWeatherStringToImageSetNum(nWeather: Int): WeatherConditions {
        val retWeather: WeatherConditions
        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isMostlyClearCode(
                nWeather
            )
        ) {
            retWeather =
                com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR
        } else {
            when (nWeather) {
                0 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR

                2 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY

                3 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY

                45, 48 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG

                51, 53, 55, 61, 63, 65, 80, 81, 82 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS

                95, 96, 99 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS

                71, 73, 75, 77, 85, 86 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW

                56, 57, 66, 67 -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET

                else -> retWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY
            }
        }
        this.mbManySnows = nWeather == 75 || nWeather == 77 || nWeather == 86
        return retWeather
    }

    fun setImageSetChange(bChange: Boolean) {
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mbImageSetChange = bChange
    }

    fun setCityNameChange(bChange: Boolean) {
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mbCityNameChange = bChange
    }

    fun checkIsDayOrNight(): Boolean {
        val now: Calendar = Calendar.getInstance()
        val nCurTime: Int = (now.get(Calendar.HOUR_OF_DAY) * 100) + now.get(Calendar.MINUTE)
        return nCurTime < com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mnSunriseTime || nCurTime >= com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mnSunsetTime
    }

    fun updateWeatherInfo(): Boolean {
        val snapshot: WeatherSnapshot? = WeatherDataCoordinator.readFromCache(this.mContext)
        val forcedWeatherCode = this.forcedWeatherCodeOverride
        val previousWeather: Int =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather =
            previousWeather
        if (snapshot == null || snapshot.weatherCode === WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
            if (forcedWeatherCode != WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurrentWeatherCode =
                    forcedWeatherCode
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                    convertWeatherStringToImageSetNum(forcedWeatherCode).ordinal
                applyForcedSceneOverride()
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref != null) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit()
                        .putInt(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_WEATHER_CONDITION,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                        )
                        .putInt(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_PREV_WEATHER_CONDITION,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather
                        )
                        .apply()
                }
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isSupportedSceneOrdinal(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                )
            }
            val forcedScene = this.forcedSceneOrdinal
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isSupportedSceneOrdinal(
                    forcedScene
                )
            ) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                    forcedScene
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref != null) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit()
                        .putInt(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_WEATHER_CONDITION,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                        )
                        .putInt(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_PREV_WEATHER_CONDITION,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather
                        )
                        .apply()
                }
                return true
            }
            return false
        }

        val previousWeahter = previousWeather
        val previousMoonPhase: Int =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase
        val previousSunriseTime = this.mnSunriseTime
        val previousSunsetTime = this.mnSunsetTime
        val previousBelowFreezing = this.isBelowFreezingNow
        val previousHighHumidity = this.isHighHumidityNow
        val previousCity: String? =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName
        val sourceWeatherCode: Int = snapshot.weatherCode
        val effectiveWeatherCode =
            if (forcedWeatherCode != WeatherSnapshot.UNKNOWN_WEATHER_CODE) forcedWeatherCode else sourceWeatherCode
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurrentWeatherCode =
            effectiveWeatherCode
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
            convertWeatherStringToImageSetNum(effectiveWeatherCode).ordinal
        if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isSupportedSceneOrdinal(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
            )
        ) {
            Log.d(
                "WindyWeather",
                "Weather num out of bounds:" + com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
            )
            return false
        }
        this.mnHighTemp = snapshot.highTempC
        this.mnLowTemp = snapshot.lowTempC
        this.mnCurrentTemp = snapshot.currentTempC
        this.mnHumidityPercent = snapshot.humidityPercent
        this.currentWindSpeedKmh = snapshot.windSpeedKmh
        this.isBelowFreezingNow = this.mnCurrentTemp <= 0
        this.isHighHumidityNow = this.mnHumidityPercent >= 90
        this.mnSunriseTime = snapshot.sunriseTime
        this.mnSunsetTime = snapshot.sunsetTime
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase =
            snapshot.moonPhase
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName =
            if (snapshot.cityName == null) "" else snapshot.cityName
        applyForcedSceneOverride()
        Log.d(
            "WindyWeather",
            "Applying weatherCode source=" + sourceWeatherCode + " effective=" + effectiveWeatherCode + " forcedOverride=" + forcedWeatherCode + " tempC=" + this.mnCurrentTemp + " windKmh=" + this.currentWindSpeedKmh + " sourceLabel=" + snapshot.codeSource + " updatedMs=" + snapshot.lastUpdatedMs
        )

        val editor: SharedPreferences.Editor =
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit()
        try {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != previousWeahter) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
                Log.d(
                    "WindyWeather",
                    "!!!!!!!Weather Changed: " + com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                )
            }
            editor.putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_WEATHER_CONDITION,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
            )
            editor.putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_PREV_WEATHER_CONDITION,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather
            )
            if (previousMoonPhase != com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
                editor.putInt(
                    "last_moon_phase_num_2",
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase
                )
            }
            if (previousSunriseTime != this.mnSunriseTime) {
                editor.putInt("last_sunrise_time_2", this.mnSunriseTime)
            }
            if (previousSunsetTime != this.mnSunsetTime) {
                editor.putInt("last_sunset_time_2", this.mnSunsetTime)
            }
            if (previousBelowFreezing != this.isBelowFreezingNow) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
            }
            if (previousHighHumidity != this.isHighHumidityNow) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
            }
            if (previousCity != com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName && !com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName.isNullOrEmpty()) {
                if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mbImageSetChange) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setCityNameChange(
                        true
                    )
                }
                editor.putString(
                    "last_city_name_2",
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName
                )
            }
            editor.putInt("last_high_temp_2", this.mnHighTemp)
            editor.putInt("last_low_temp_2", this.mnLowTemp)
            editor.putInt("last_current_temp_2", this.mnCurrentTemp)
            editor.putInt("last_humidity_percent_2", this.mnHumidityPercent)
            editor.putFloat("last_wind_speed_kmh_2", this.currentWindSpeedKmh)
            editor.apply()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return true
        }
    }

    inner class WeatherReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val action: String? = safeIntent.action
            if (action == null) {
                return
            }
            Log.d("WindyWeather", "WeatherReceiver:" + action)
            if ("com.android.wallpaper.livepicker.SET_LIVE_WALLPAPER".equals(action)) {
                this@SecretWallpaperService.mWeatherHandler.sendMessage(
                    this@SecretWallpaperService.mWeatherHandler.obtainMessage(
                        300
                    )
                )
            } else if ("android.intent.action.USER_PRESENT".equals(action)) {
                this@SecretWallpaperService.mUnlock = true
                if (this@SecretWallpaperService.isWeatherRefreshEnabled) {
                    this@SecretWallpaperService.mWeatherHandler.sendMessage(
                        this@SecretWallpaperService.mWeatherHandler.obtainMessage(
                            320
                        )
                    )
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_FORCE_WEATHER_REFRESH.equals(
                    action
                )
            ) {
                this@SecretWallpaperService.requestWeatherRefresh(
                    true,
                    true,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER
                )
                if (this@SecretWallpaperService.updateWeatherInfo()) {
                    this@SecretWallpaperService.setImageSetChange(true)
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_WEATHER_REFRESH_INTERVAL.equals(
                    action
                )
            ) {
                val intervalMinutes: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_WEATHER_REFRESH_INTERVAL_MINUTES,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_DEFAULT_MINUTES
                )
                this@SecretWallpaperService.weatherRefreshIntervalMinutes = intervalMinutes
                if (this@SecretWallpaperService.isWeatherRefreshEnabled) {
                    this@SecretWallpaperService.requestWeatherRefresh(
                        true,
                        true,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER
                    )
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_TARGET_FPS.equals(
                    action
                )
            ) {
                val fps: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_TARGET_FPS,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_DEFAULT
                )
                this@SecretWallpaperService.configuredTargetFrameRate = fps
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_POWER_SAVE_TARGET_FPS.equals(
                    action
                )
            ) {
                val fps: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_POWER_SAVE_TARGET_FPS,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_POWER_SAVE_DEFAULT
                )
                this@SecretWallpaperService.configuredPowerSaveTargetFrameRate = fps
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_FRAME_RATE_DEPENDENT_ANIMATION.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_FRAME_RATE_DEPENDENT_ANIMATION_ENABLED,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.FRAME_RATE_DEPENDENT_ANIMATION_DEFAULT
                )
                this@SecretWallpaperService.isFrameRateDependentAnimationEnabled = enabled
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_GROUND_PARALLAX.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_GROUND_PARALLAX_ENABLED,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.GROUND_PARALLAX_DEFAULT
                )
                this@SecretWallpaperService.isGroundParallaxEnabled = enabled
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_FORCED_SCENE.equals(
                    action
                )
            ) {
                val forcedScene: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_DEBUG_FORCED_SCENE,
                    -1
                )
                this@SecretWallpaperService.forcedSceneOrdinal = forcedScene
                if (this@SecretWallpaperService.updateWeatherInfo()) {
                    this@SecretWallpaperService.setImageSetChange(true)
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_FORCED_WEATHER_CODE.equals(
                    action
                )
            ) {
                val forcedWeatherCode: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_DEBUG_FORCED_WEATHER_CODE,
                    WeatherSnapshot.UNKNOWN_WEATHER_CODE
                )
                this@SecretWallpaperService.forcedWeatherCodeOverride = forcedWeatherCode
                if (this@SecretWallpaperService.updateWeatherInfo()) {
                    this@SecretWallpaperService.setImageSetChange(true)
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_OLD_NIGHT_EFFECT.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_OLD_NIGHT_EFFECT_ENABLED,
                    false
                )
                this@SecretWallpaperService.isOldNightEffectEnabled = enabled
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_CITY_NAME_VISIBLE.equals(
                    action
                )
            ) {
                val visible: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_CITY_NAME_VISIBLE,
                    true
                )
                this@SecretWallpaperService.isCityNameVisible = visible
                if (visible) {
                    this@SecretWallpaperService.setCityNameChange(true)
                }
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_LEGACY_LOGO_VISIBLE.equals(
                    action
                )
            ) {
                val visible2: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_LEGACY_LOGO_VISIBLE,
                    false
                )
                this@SecretWallpaperService.isLegacyLogoVisible = visible2
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_DAY_NIGHT_MODE.equals(
                    action
                )
            ) {
                val mode: Int = safeIntent.getIntExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_DAY_NIGHT_MODE,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_AUTO
                )
                this@SecretWallpaperService.dayNightMode = mode
                this@SecretWallpaperService.mbIsNight =
                    this@SecretWallpaperService.isNightEffective
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_DEBUG_SET_HIDE_THUNDER_RAINDROPS_LEGACY.equals(
                    action
                )
            ) {
                val hideLegacy: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_HIDE_THUNDER_RAINDROPS_LEGACY,
                    false
                )
                this@SecretWallpaperService.isHideThunderRaindropsLegacyEnabled = hideLegacy
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_FREEZING_FROST.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_LEGACY_FREEZING_FROST_ENABLED,
                    true
                )
                this@SecretWallpaperService.isLegacyBelowFreezingFrostEnabled = enabled
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_HUMIDITY_WATERDROP.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_LEGACY_HUMIDITY_WATERDROP_ENABLED,
                    true
                )
                this@SecretWallpaperService.isLegacyHighHumidityWaterdropEnabled = enabled
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_DELAY_SNOW_GROUND.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_LEGACY_DELAY_SNOW_GROUND_ENABLED,
                    true
                )
                this@SecretWallpaperService.isLegacyDelayedSnowGroundEnabled = enabled
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_LEGACY_CLASSIC_WATERMARK.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_LEGACY_CLASSIC_WATERMARK_ENABLED,
                    false
                )
                this@SecretWallpaperService.isLegacyClassicWatermarkEnabled = enabled
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_TEXTURE_PACK.equals(
                    action
                )
            ) {
                val texturePack: String? =
                    safeIntent.getStringExtra(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_TEXTURE_PACK)
                this@SecretWallpaperService.texturePack = texturePack
                this@SecretWallpaperService.setImageSetChange(true)
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_WEATHER_SOURCE_MODE.equals(
                    action
                )
            ) {
                val sourceMode: String? =
                    safeIntent.getStringExtra(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_WEATHER_SOURCE_MODE)
                this@SecretWallpaperService.weatherSourceMode = sourceMode
                if (this@SecretWallpaperService.mSamsungSyncBridge != null) {
                    this@SecretWallpaperService.mSamsungSyncBridge?.onModeChanged(sourceMode)
                }
                this@SecretWallpaperService.requestWeatherRefresh(
                    true,
                    true,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER
                )
                if (this@SecretWallpaperService.updateWeatherInfo()) {
                    this@SecretWallpaperService.setImageSetChange(true)
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SET_AEROWEATHER_REFRESH_SYNC.equals(
                    action
                )
            ) {
                val enabled: Boolean = safeIntent.getBooleanExtra(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_AEROWEATHER_REFRESH_SYNC_ENABLED,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.AEROWEATHER_REFRESH_SYNC_DEFAULT
                )
                this@SecretWallpaperService.setAeroWeatherRefreshSyncEnabled(enabled)
                if (this@SecretWallpaperService.mSamsungSyncBridge != null) {
                    this@SecretWallpaperService.mSamsungSyncBridge?.onSyncSettingChanged()
                }
            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_SAMSUNG_PROVIDER_CHANGED_INTERNAL.equals(
                    action
                )
            ) {
                this@SecretWallpaperService.recordSyncOrigin(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_SAMSUNG_OBSERVER)
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(
                        this@SecretWallpaperService.weatherSourceMode
                    )
                ) {
                    WeatherDataCoordinator.refreshSamsungOnlyAsync(
                        this@SecretWallpaperService.mContext,
                        { snapshot ->
                            if (this@SecretWallpaperService.updateWeatherInfo()) {
                                this@SecretWallpaperService.setImageSetChange(true)
                            }
                            this@SecretWallpaperService.requestWeatherRefresh(
                                true,
                                true,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_SAMSUNG_OBSERVER
                            )
                        })
                } else {
                    this@SecretWallpaperService.requestWeatherRefresh(
                        true,
                        true,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_SAMSUNG_OBSERVER
                    )
                }
            }
        }
    }

    inner class AeroWeatherSyncReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val safeIntent = intent ?: return
            val action: String? = safeIntent.action
            if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ACTION_AEROWEATHER_SYNC_REFRESH.equals(
                    action
                )
            ) {
                return
            }
            val sourcePackage: String? =
                safeIntent.getStringExtra(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.EXTRA_AEROWEATHER_SYNC_SOURCE_PACKAGE)
            if (sourcePackage != null && sourcePackage.length > 0 && !com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TRUSTED_AEROWEATHER_PACKAGE.equals(
                    sourcePackage
                )
            ) {
                Log.d(
                    "WindyWeather",
                    "Ignoring AeroWeather sync from untrusted package=" + sourcePackage
                )
                return
            }
            if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isAeroWeatherRefreshSyncEnabled(
                    context
                )
            ) {
                Log.d("WindyWeather", "Ignoring AeroWeather sync while sync is disabled")
                return
            }
            Log.d(
                "WindyWeather",
                "Accepted AeroWeather sync refresh source=" + sourcePackage
            )
            this@SecretWallpaperService.requestWeatherRefresh(
                true,
                true,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_AEROWEATHER_SYNC
            )
        }
    }

    inner class TimeTickReceiver : BroadcastReceiver() {
        override fun onReceive(arg0: Context?, intent: Intent?) {
            val action: String? = if (intent != null) intent.action else null
            if (!"android.intent.action.TIME_TICK".equals(action)) {
                return
            }
            val effectiveNight = this@SecretWallpaperService.isNightEffective
            if (effectiveNight != com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbIsNight) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbIsNight =
                    effectiveNight
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbIsNight) {
                    Log.d("WindyWeather", "DAY -> NIGHT changed")
                } else {
                    Log.d("WindyWeather", "NIGHT -> DAY changed")
                }
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
            }
            val intervalMs = this@SecretWallpaperService.weatherRefreshIntervalMs
            if (this@SecretWallpaperService.isWeatherRefreshEnabled && intervalMs > 0L && System.currentTimeMillis() - this@SecretWallpaperService.mLastWeatherRefreshMs >= intervalMs) {
                this@SecretWallpaperService.mWeatherHandler.sendMessage(
                    this@SecretWallpaperService.mWeatherHandler.obtainMessage(
                        320
                    )
                )
            }
        }
    }

    private val isWeatherRefreshEnabled: Boolean
        get() = this.weatherRefreshIntervalMinutes > com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES

    private var weatherRefreshIntervalMinutes: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_DEFAULT_MINUTES
            }
            val minutes: Int =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_WEATHER_REFRESH_INTERVAL_MINUTES,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_DEFAULT_MINUTES
                )
            if (minutes == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES
            }
            if (minutes < com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MIN_MINUTES) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MIN_MINUTES
            }
            return Math.min(
                minutes,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MAX_MINUTES
            )
        }
        private set(minutes) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalizedMinutes: Int
            if (minutes == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES) {
                normalizedMinutes =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES
            } else if (minutes < com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MIN_MINUTES) {
                normalizedMinutes =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MIN_MINUTES
            } else {
                normalizedMinutes = Math.min(
                    minutes,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_MAX_MINUTES
                )
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_WEATHER_REFRESH_INTERVAL_MINUTES,
                normalizedMinutes
            ).apply()
            if (normalizedMinutes == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES) {
                this.mLastWeatherRefreshMs = 0L
            }
        }

    private val weatherRefreshIntervalMs: Long
        get() {
            val minutes = this.weatherRefreshIntervalMinutes
            if (minutes == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_REFRESH_OFF_MINUTES) {
                return 0L
            }
            return minutes * 60L * 1000L
        }

    private fun normalizeFrameRate(fps: Int): Int {
        return Math.max(
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_MIN,
            Math.min(
                fps,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_MAX
            )
        )
    }

    private var configuredTargetFrameRate: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_DEFAULT
            }
            val fps: Int =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_TARGET_FPS,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_DEFAULT
                )
            return normalizeFrameRate(fps)
        }
        private set(fps) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalized = normalizeFrameRate(fps)
            val currentPowerSaveFps = this.configuredPowerSaveTargetFrameRate
            val editor: SharedPreferences.Editor =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_TARGET_FPS,
                    normalized
                )
            if (currentPowerSaveFps > normalized) {
                editor.putInt(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_POWER_SAVE_TARGET_FPS,
                    normalized
                )
            }
            editor.apply()
        }

    private var configuredPowerSaveTargetFrameRate: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_POWER_SAVE_DEFAULT
            }
            val baseFps = this.configuredTargetFrameRate
            val fps: Int =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_POWER_SAVE_TARGET_FPS,
                    Math.min(
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_POWER_SAVE_DEFAULT,
                        baseFps
                    )
                )
            return Math.min(baseFps, normalizeFrameRate(fps))
        }
        private set(fps) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalized: Int = Math.min(this.configuredTargetFrameRate, normalizeFrameRate(fps))
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_POWER_SAVE_TARGET_FPS,
                normalized
            ).apply()
        }

    private var isFrameRateDependentAnimationEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.FRAME_RATE_DEPENDENT_ANIMATION_DEFAULT
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_FRAME_RATE_DEPENDENT_ANIMATION,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.FRAME_RATE_DEPENDENT_ANIMATION_DEFAULT
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_FRAME_RATE_DEPENDENT_ANIMATION,
                enabled
            ).apply()
        }

    private var isGroundParallaxEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.GROUND_PARALLAX_DEFAULT
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_GROUND_PARALLAX,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.GROUND_PARALLAX_DEFAULT
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_GROUND_PARALLAX,
                enabled
            ).apply()
        }

    private var forcedSceneOrdinal: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return -1
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_FORCED_SCENE,
                -1
            )
        }
        private set(ordinal) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_FORCED_SCENE,
                ordinal
            ).apply()
        }

    private var forcedWeatherCodeOverride: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return WeatherSnapshot.UNKNOWN_WEATHER_CODE
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_FORCED_WEATHER_CODE,
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            )
        }
        private set(weatherCode) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalizedCode =
                if (weatherCode >= 0) weatherCode else WeatherSnapshot.UNKNOWN_WEATHER_CODE
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_FORCED_WEATHER_CODE,
                normalizedCode
            ).apply()
        }

    private var isLegacyBelowFreezingFrostEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return true
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_FREEZING_FROST,
                true
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_FREEZING_FROST,
                enabled
            ).apply()
        }

    private var isLegacyHighHumidityWaterdropEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return true
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_HUMIDITY_WATERDROP,
                true
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_HUMIDITY_WATERDROP,
                enabled
            ).apply()
        }

    private var isLegacyDelayedSnowGroundEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return true
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_DELAY_SNOW_GROUND,
                true
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_DELAY_SNOW_GROUND,
                enabled
            ).apply()
        }

    private var isLegacyClassicWatermarkEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return false
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_CLASSIC_WATERMARK,
                false
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LEGACY_CLASSIC_WATERMARK,
                enabled
            ).apply()
        }

    private fun shouldUseSnowGroundTexturesForSnowScene(): Boolean {
        if (!this.isLegacyDelayedSnowGroundEnabled) {
            return true
        }
        return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal
    }

    private fun applyForcedSceneOverride() {
        val forcedScene = this.forcedSceneOrdinal
        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isSupportedSceneOrdinal(
                forcedScene
            )
        ) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                forcedScene
            Log.d("WindyWeather", "Forced scene override applied: " + forcedScene)
        }
    }

    private var isCityNameVisible: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return true
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SHOW_CITY_NAME,
                true
            )
        }
        private set(visible) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SHOW_CITY_NAME,
                visible
            ).apply()
        }

    private var isOldNightEffectEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return false
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_OLD_NIGHT_EFFECT,
                false
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_OLD_NIGHT_EFFECT,
                enabled
            ).apply()
        }

    private var isLegacyLogoVisible: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return false
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SHOW_LEGACY_LOGO,
                false
            )
        }
        private set(visible) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SHOW_LEGACY_LOGO,
                visible
            ).apply()
            this.mEnableLogo = visible
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.setEnableLogo(
                visible
            )
        }

    private var isHideThunderRaindropsLegacyEnabled: Boolean
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return false
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_HIDE_THUNDER_RAINDROPS_LEGACY,
                false
            )
        }
        private set(enabled) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_HIDE_THUNDER_RAINDROPS_LEGACY,
                enabled
            ).apply()
        }

    private var dayNightMode: Int
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_AUTO
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_DAY_NIGHT_MODE,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_AUTO
            )
        }
        private set(mode) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalizedMode: Int
            if (mode < com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_AUTO || mode > com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_FORCE_NIGHT) {
                normalizedMode =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_AUTO
            } else {
                normalizedMode = mode
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putInt(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_DEBUG_DAY_NIGHT_MODE,
                normalizedMode
            ).apply()
        }

    private val isNightEffective: Boolean
        get() {
            val mode = this.dayNightMode
            if (mode == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_FORCE_DAY) {
                return false
            }
            if (mode == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.DAY_NIGHT_MODE_FORCE_NIGHT) {
                return true
            }
            return checkIsDayOrNight()
        }

    private fun normalizeWeatherSourceMode(sourceMode: String?): String? {
        return if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(
                sourceMode
            )
        ) com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_SAMSUNG_DEVICE else com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_OPEN_METEO
    }

    private fun recordSyncOrigin(origin: String?) {
        if (this.mContext == null) {
            return
        }
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.setLastSyncOrigin(
            this.mContext,
            origin
        )
    }

    private var weatherSourceMode: String?
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_OPEN_METEO
            }
            val sourceMode: String? =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getString(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_WEATHER_SOURCE_MODE,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.WEATHER_SOURCE_OPEN_METEO
                )
            return normalizeWeatherSourceMode(sourceMode)
        }
        private set(sourceMode) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalized = normalizeWeatherSourceMode(sourceMode)
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putString(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_WEATHER_SOURCE_MODE,
                normalized
            ).apply()
            Log.d("WindyWeather", "Weather source mode set to " + normalized)
        }

    private fun setAeroWeatherRefreshSyncEnabled(enabled: Boolean) {
        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
            return
        }
        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putBoolean(
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH,
            enabled
        ).apply()
        Log.d("WindyWeather", "AeroWeather refresh sync set to " + enabled)
    }

    private var texturePack: String?
        get() {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_HQ
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getString(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_TEXTURE_PACK,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_HQ
            )
        }
        private set(texturePack) {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref == null) {
                return
            }
            val normalizedPack: String =
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_LEGACY.equals(
                        texturePack
                    )
                ) com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_LEGACY else com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_HQ
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.edit().putString(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_TEXTURE_PACK,
                normalizedPack
            ).apply()
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sTextureResourceCache.clear()
            Log.d("WindyWeather", "Texture pack set to " + normalizedPack)
        }

    private fun resolveTextureId(baseName: String?): Int {
        val context: Context = if (this.mContext != null) this.mContext else this
        if (context == null || baseName == null || baseName.isEmpty()) {
            return 0
        }
        val hqResId: Int =
            context.resources.getIdentifier(baseName, "drawable", context.packageName)
        if (hqResId == 0) {
            return 0
        }
        return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.resolveTextureResource(
            context,
            hqResId
        )
    }

    private fun removeGarbageData(str: String): String {
        val length: Int = str.length
        var j = 0
        val buffer: StringBuilder = StringBuilder()
        buffer.setLength(length)
        for (i in 0..<length) {
            val character: Char = str[i]
            val code: Int = str.codePointAt(i)
            if (code != 0) {
                buffer.setCharAt(j, character)
                j++
            }
        }
        return buffer.toString()
    }

    private fun parseWeatherText(detail: String?): String {
        return if (detail == null) "" else detail
    }

    fun setPreviewWeather() {
        if (!this.prevWeatherChangedDone) {
            val currentTime: Long = System.currentTimeMillis()
            if (currentTime - this.prevWeatherStartTime >= 3000) {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null || com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.engine == null || com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.engine!!.mRenderer == null || !com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.engine!!.mRenderer!!.isImageSetLoading) {
                    if (this.prevCond == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D0_NOTHING) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                        this.prevCond =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR
                    } else if (this.prevCond == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal
                        this.prevCond =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS
                    } else if (this.prevCond == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal
                        this.prevCond =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS
                    } else if (this.prevCond == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal
                        this.prevCond =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW
                    } else if (this.prevCond == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                        this.prevCond =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D0_NOTHING
                        this.prevWeatherChangedDone = true
                    }
                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                            true
                        )
                    }
                    this.prevWeatherStartTime = System.currentTimeMillis()
                }
            }
        }
    }

    inner class CSPWallpaperEngine(context: Context) : GLWallpaperService.GLEngine() {
        var mContext: Context?
        var mIsFirstRunPreviewThread: Boolean = true
        var mRenderer: CSPRenderer?

        init {
            this.mRenderer =
                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer(context)
            setRenderer(this.mRenderer)
            setRenderMode(1)
            this.mContext = context
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            Log.d("WindyWeather", "Engine create")
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onDestroy() {
            Log.d("WindyWeather", "Engine destroy")
            if (!isPreview()) {
                this@SecretWallpaperService.stopCurrentLocationWeatherDataService()
            }
            if (this.mRenderer != null) {
                this.mRenderer!!.release()
            }
            this.mRenderer = null
            super.onDestroy()
        }

        override fun onPause() {
            if (this.mRenderer != null) {
                this.mRenderer!!.setEnginePause(true)
            }
            super.onPause()
        }

        override fun onResume() {
            this.mRenderer!!.setEnginePause(false)
            super.onResume()
            if (isPreview()) {
                Log.d("WindyWeather", "onResume: PREVIEW !!!")
                this.mRenderer!!.mOffset = 1.0f
                this.mRenderer!!.isPreview = true
                this@SecretWallpaperService.mbIsNight =
                    this@SecretWallpaperService.isNightEffective
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                val unused: Int =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                this@SecretWallpaperService.setImageSetChange(true)
                this@SecretWallpaperService.prevCond =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D0_NOTHING
                this@SecretWallpaperService.prevWeatherStartTime = 0L
                this@SecretWallpaperService.prevWeatherChangedDone = false
            } else {
                Log.d("WindyWeather", "onResume: LIVE !!!")
                this.mRenderer!!.isPreview = false
                this@SecretWallpaperService.mbIsPreview = false
                if (!this@SecretWallpaperService.updateWeatherInfo()) {
                    try {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_moon_phase_num_2",
                                2
                            )
                        val unused2: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_WEATHER_CONDITION,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                            )
                        val unused3: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_PREV_WEATHER_CONDITION,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                            )
                        val unused6: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather
                        this@SecretWallpaperService.mnSunriseTime =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_sunrise_time_2",
                                600
                            )
                        this@SecretWallpaperService.mnSunsetTime =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_sunset_time_2",
                                1800
                            )
                        this@SecretWallpaperService.mnHighTemp =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_high_temp_2",
                                0
                            )
                        this@SecretWallpaperService.mnLowTemp =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_low_temp_2",
                                0
                            )
                        this@SecretWallpaperService.mnCurrentTemp =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_current_temp_2",
                                this@SecretWallpaperService.mnLowTemp
                            )
                        this@SecretWallpaperService.mnHumidityPercent =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getInt(
                                "last_humidity_percent_2",
                                0
                            )
                        this@SecretWallpaperService.currentWindSpeedKmh =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getFloat(
                                "last_wind_speed_kmh_2",
                                0.0f
                            )
                        this@SecretWallpaperService.isBelowFreezingNow =
                            this@SecretWallpaperService.mnCurrentTemp <= 0
                        this@SecretWallpaperService.isHighHumidityNow =
                            this@SecretWallpaperService.mnHumidityPercent >= 90
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref!!.getString(
                                "last_city_name_2",
                                ""
                            )
                        val unused4: String? =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                        val unused5: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                        val unused7: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnPrevWeather
                        this@SecretWallpaperService.mnHumidityPercent = 0
                        this@SecretWallpaperService.currentWindSpeedKmh = 0.0f
                        this@SecretWallpaperService.isBelowFreezingNow = false
                        this@SecretWallpaperService.isHighHumidityNow = false
                    }
                }
                this@SecretWallpaperService.startCurrentLocationWeatherDataService()
                val isNight = this@SecretWallpaperService.isNightEffective
                if (this@SecretWallpaperService.mbIsNight != isNight) {
                    this@SecretWallpaperService.mbIsNight = isNight
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                        true
                    )
                }
                val display: Display =
                    (this@SecretWallpaperService.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                this@SecretWallpaperService.mOrientation = display.rotation
            }
            this.mRenderer!!.sceneDrawStatus = true
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (this.mRenderer != null) {
                this.mRenderer!!.onTouchEvent(event)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int
        ) {
            if (this.mRenderer == null) {
                return
            }
            // Always accept launcher page offsets; parallax visibility is controlled by user prefs.
            this.mRenderer!!.setSystemOffset(xOffset, isPreview(), true)
        }
    }

    class CSPRenderer(context: Context) : GLWallpaperService.Renderer {
        private var cityname: RectOneToSixteen? = null
        private var cloud1: RectOneToTwo? = null
        private var cloud2: RectOneToTwo? = null
        private var cloud_light_a_01: RectOneToTwo? = null
        private var cloud_light_a_02: RectOneToTwo? = null
        private var cloud_light_a_03: RectOneToTwo? = null
        private var cloud_light_b_01: RectOneToTwo? = null
        private var cloud_light_b_02: RectOneToTwo? = null
        private var cloud_light_b_03: RectOneToTwo? = null
        private val day_night: Square? = null
        private var fog: Square? = null
        private var frost: Square? = null
        private var land_01: RectOneToFour? = null
        private var land_02: RectOneToFour? = null
        private var lawn_01: RectOneToFour? = null
        private var lightning1: Square? = null
        private var lightning2: Square? = null
        private var lightning3: Square? = null
        private var logo: RectOneToFour? = null
        private val mContext: Context
        var mGl: GL10? = null
        var mOffset = 0f
        private var mbImgLoaded = false
        private var meteor: Square? = null
        private var moon: Square? = null
        private val next: Square? = null
        private var nightcover: Square? = null
        private val prev: Square? = null
        private var rain1: Square? = null
        private var rain2: Square? = null
        private var rain3: Square? = null
        private var raindrop1: Array<RectOneToTwo?>? = null
        private var raindrop2: Array<RectOneToTwo?>? = null
        private var sky: Square? = null
        private var sky_flash: Square? = null
        private var sky_stars: Square? = null
        private var snow1: Square? = null
        private var snow2: Square? = null
        private var snow3: Square? = null
        private var snow4: Square? = null
        private var star: Square? = null
        private var sun1: Square? = null
        private var sun2: Square? = null
        private var sun3: Square? = null
        private var sun4: Square? = null
        private var waterdrop: Square? = null
        private var windmill_center_01: Square? = null
        private var windmill_pillar_01: Square? = null
        private var windmill_pillar_02: Square? = null
        private var windmill_pillar_flip_01: Square? = null
        private var windmill_pillar_flip_02: Square? = null
        private var windmill_wing: Square? = null
        private var windmill_wing_blur: Square? = null
        private var m1280x720 = false
        private val mScaleView = 5.0f
        private var mfLandscape = 1.0f
        private var mIsPortrait = true
        private var bSnowOn = false
        private var bThunderOn = false
        private var bRainOn = false
        private var bClearOn = false
        private var sunlight_cnt = 0.0f
        var fAlpha: Float = 0.0f
        var x_a_cloud_A_1: Float = 0.0f
        var x_a_cloud_A_2: Float = 0.0f
        var x_a_cloud_A_3: Float = 0.0f
        var x_a_cloud_A_4: Float = 0.0f
        var x_a_cloud_B_1: Float = 0.0f
        var x_a_cloud_B_2: Float = 0.0f
        var x_a_cloud_B_3: Float = 0.0f
        var x_a_cloud_B_4: Float = 0.0f
        var x_a_cloud_B_5: Float = 0.0f
        var x_a_cloud_B_6: Float = 0.0f
        var x_a_cloud_B_7: Float = 0.0f
        var y_a_cloud_A_1: Float = 0.0f
        var y_a_cloud_A_2: Float = 0.0f
        var y_a_cloud_A_3: Float = 0.0f
        var y_a_cloud_A_4: Float = 0.0f
        var y_a_cloud_B_1: Float = 0.0f
        var y_a_cloud_B_2: Float = 0.0f
        var y_a_cloud_B_3: Float = 0.0f
        var y_a_cloud_B_4: Float = 0.0f
        var y_a_cloud_B_5: Float = 0.0f
        var y_a_cloud_B_6: Float = 0.0f
        var y_a_cloud_B_7: Float = 0.0f
        var x_a_meteor: Float = 0.0f
        var y_a_meteor: Float = 0.0f
        var scale_a_meteor: Float = 0.0f
        var alpha_a_meteor: Float = 0.0f
        var x_star: FloatArray = floatArrayOf(1.0f, -1.7f, 1.2f, -1.5f, -4.5f, -6.1f, -7.5f)
        var y_star: FloatArray = floatArrayOf(5.4f, 4.5f, 3.2f, 3.0f, 4.7f, 5.2f, 4.8f)
        var size_star: FloatArray = floatArrayOf(0.1f, 0.1f, 0.08f, 0.1f, 0.08f, 0.08f, 0.1f)
        var n_snow1: Int = 5
        var n_snow2: Int = 70
        var n_snow3: Int = 150
        val typeA: Boolean = true
        val typeB: Boolean = false
        var windmill_pos_x: FloatArray = floatArrayOf(
            -6.5f,
            -3.5f,
            -0.8f,
            8.5f,
            10.4f,
            -7.9f,
            -4.4f,
            -0.2f,
            11.5f,
            12.0f,
            -11.5f,
            -6.0f,
            -3.0f
        )
        var windmill_pos_y: FloatArray = floatArrayOf(
            -2.8f,
            -1.3f,
            2.2f,
            0.3f,
            -1.1f,
            -2.7f,
            -2.75f,
            -2.75f,
            -2.5f,
            -2.8f,
            -3.5f,
            -3.3f,
            -3.2f
        )
        var windmill_pos_z: FloatArray = floatArrayOf(
            -23.0f,
            -23.0f,
            -23.0f,
            -23.0f,
            -23.0f,
            -24.05f,
            -24.05f,
            -24.05f,
            -23.95f,
            -23.95f,
            -25.0f,
            -25.0f,
            -25.0f
        )
        var windmill_scale_x: FloatArray = floatArrayOf(
            0.2f,
            0.35f,
            0.75f,
            0.5f,
            0.3f,
            0.15f,
            0.12f,
            0.12f,
            0.15f,
            0.09f,
            0.08f,
            0.08f,
            0.08f
        )
        var windmill_scale_y: FloatArray = floatArrayOf(
            0.2f,
            0.35f,
            0.75f,
            0.5f,
            0.3f,
            0.15f,
            0.12f,
            0.12f,
            0.15f,
            0.09f,
            0.08f,
            0.08f,
            0.08f
        )
        var windmill_distance: IntArray = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 2, 2, 2)
        var windmill_type: BooleanArray = booleanArrayOf(
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        )
        var windmill_flip: BooleanArray = booleanArrayOf(
            false,
            false,
            false,
            true,
            true,
            false,
            false,
            false,
            true,
            true,
            false,
            false,
            false
        )
        var windmill_pillar_offset_x: FloatArray = floatArrayOf(
            -0.05f,
            -0.1f,
            -0.15f,
            0.1f,
            0.05f,
            -0.02f,
            -0.02f,
            -0.05f,
            0.02f,
            0.02f,
            -0.05f,
            -0.05f,
            -0.05f
        )
        var windmill_pillar_offset_y: FloatArray = floatArrayOf(
            -1.55f,
            -2.7f,
            -5.9f,
            -3.9f,
            -2.32f,
            -1.18f,
            -0.9f,
            -0.9f,
            -1.15f,
            -0.7f,
            -0.6f,
            -0.6f,
            -0.6f
        )
        var windmill_rotor_offset_x: FloatArray = floatArrayOf(
            0.0f,
            -0.04f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f
        )
        var windmill_rotor_offset_y: FloatArray = floatArrayOf(
            0.0f,
            0.03f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f
        )
        var windmill_wing_offset: FloatArray = floatArrayOf(
            0.0f,
            30.0f,
            60.0f,
            90.0f,
            120.0f,
            30.0f,
            60.0f,
            90.0f,
            120.0f,
            150.0f,
            60.0f,
            90.0f,
            120.0f
        )
        var windmill_alpha: FloatArray = floatArrayOf(
            0.9f,
            0.9f,
            1.0f,
            1.0f,
            0.9f,
            1.0f,
            1.0f,
            1.0f,
            0.8f,
            0.8f,
            0.9f,
            0.9f,
            0.9f
        )
        var windmill_rotation_visible: BooleanArray = booleanArrayOf(
            false,
            true,
            true,
            false,
            false,
            true,
            true,
            true,
            false,
            false,
            false,
            true,
            true
        )
        internal var windmillSet: Array<WindMill?>? = null
        var mOnSurfaceChanged: Boolean = false
        private var preOrientation = 0
        private val mCntMode = 0
        private var mFrameCnt = 0
        private var mFrameCntAccumulator = 0.0f
        private var mSurfaceWidth = 1
        private var mSurfaceHeight = 1
        private var mSurfaceAspect = 1.0f
        private var mLastLoggedDayNightMode = -1
        private var mLastLoggedShowCity = true
        private var mLastLoggedShowLogo = false
        private var mWindmillAngle = 0.0f
        private var mLastCityNameTextureText: String? = null
        private var mFrameTimingAccumNs = 0L
        private var mFrameTimingMaxNs = 0L
        private var mFrameTimingSamples = 0
        private var mLoggedMissingGl11Ext = false
        private var mLastAnimationStepNs = 0L

        init {
            this.mContext = context
            this.mbImgLoaded = false
            Log.d("WindyWeather", "Initialize Wallpaper: Init Images")
            generateImages(this.mContext)
            initMem()
        }

        private fun initMem() {
            this.windmillSet = arrayOfNulls<WindMill>(13)
            for (i in 0..12) {
                this.windmillSet!![i] =
                    WindMill()
                this.windmillSet!![i]!!.mCenter!!.setAttribute(
                    this.windmill_pos_x[i] + this.windmill_rotor_offset_x[i],
                    this.windmill_pos_y[i] + this.windmill_rotor_offset_y[i],
                    this.windmill_pos_z[i] - 0.1f,
                    this.windmill_scale_x[i] * 0.04f,
                    this.windmill_scale_y[i] * 0.04f
                )
                this.windmillSet!![i]!!.mPillar!!.setAttribute(
                    this.windmill_pos_x[i] + this.windmill_pillar_offset_x[i],
                    this.windmill_pos_y[i] + this.windmill_pillar_offset_y[i],
                    this.windmill_pos_z[i] + 0.1f,
                    this.windmill_scale_x[i] * 0.08f,
                    this.windmill_scale_y[i]
                )
                this.windmillSet!![i]!!.mWing!!.setAttribute(
                    this.windmill_pos_x[i] + this.windmill_rotor_offset_x[i],
                    this.windmill_pos_y[i] + this.windmill_rotor_offset_y[i],
                    this.windmill_pos_z[i],
                    this.windmill_scale_x[i],
                    this.windmill_scale_y[i]
                )
                this.windmillSet!![i]!!.setDistance(this.windmill_distance[i])
                this.windmillSet!![i]!!.setType(this.windmill_type[i])
                this.windmillSet!![i]!!.setAlpha(this.windmill_alpha[i])
                this.windmillSet!![i]!!.setFlip(this.windmill_flip[i])
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw =
                BooleanArray(7)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star =
                FloatArray(7)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star =
                IntArray(7)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.dur_star =
                IntArray(7)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1 =
                FloatArray(5)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1 =
                FloatArray(5)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1 =
                FloatArray(5)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2 =
                FloatArray(70)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2 =
                FloatArray(70)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2 =
                FloatArray(70)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3 =
                FloatArray(150)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3 =
                FloatArray(150)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3 =
                FloatArray(150)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start =
                IntArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration =
                IntArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num =
                IntArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale =
                FloatArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x =
                FloatArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y =
                FloatArray(40)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start =
                IntArray(20)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num =
                IntArray(20)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos =
                IntArray(20)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration =
                IntArray(20)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start =
                IntArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_x =
                FloatArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_y =
                FloatArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale =
                FloatArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start =
                IntArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_x =
                FloatArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_y =
                FloatArray(8)
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale =
                FloatArray(8)
        }

        private fun deleteMem() {
            for (i in 0..12) {
                this.windmillSet!![i]!!.destroy()
                this.windmillSet!![i] = null
            }
            this.windmillSet = null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.dur_star =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3 =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_x =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_y =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_x =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_y =
                null
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale =
                null
        }

        private fun deleteImages(gl: GL10?) {
            if (this.sky != null) {
                this.sky!!.deleteGLTexture(gl, this.mContext)
                this.sky = null
            }
            if (this.sky_stars != null) {
                this.sky_stars!!.deleteGLTexture(gl, this.mContext)
                this.sky_stars = null
            }
            if (this.waterdrop != null) {
                this.waterdrop!!.deleteGLTexture(gl, this.mContext)
                this.waterdrop = null
            }
            if (this.rain1 != null) {
                this.rain1!!.deleteGLTexture(gl, this.mContext)
                this.rain1 = null
            }
            if (this.rain2 != null) {
                this.rain2!!.deleteGLTexture(gl, this.mContext)
                this.rain2 = null
            }
            if (this.rain3 != null) {
                this.rain3!!.deleteGLTexture(gl, this.mContext)
                this.rain3 = null
            }
            for (i in 0..24) {
                if (this.raindrop1!![i] != null) {
                    this.raindrop1!![i]!!.deleteGLTexture(gl, this.mContext)
                    this.raindrop1!![i] = null
                }
                if (this.raindrop2!![i] != null) {
                    this.raindrop2!![i]!!.deleteGLTexture(gl, this.mContext)
                    this.raindrop2!![i] = null
                }
            }
            this.raindrop1 = null
            this.raindrop2 = null
            if (this.fog != null) {
                this.fog!!.deleteGLTexture(gl, this.mContext)
                this.fog = null
            }
            if (this.cloud1 != null) {
                this.cloud1!!.deleteGLTexture(gl, this.mContext)
                this.cloud1 = null
            }
            if (this.cloud2 != null) {
                this.cloud2!!.deleteGLTexture(gl, this.mContext)
                this.cloud2 = null
            }
            if (this.sun1 != null) {
                this.sun1!!.deleteGLTexture(gl, this.mContext)
                this.sun1 = null
            }
            if (this.sun2 != null) {
                this.sun2!!.deleteGLTexture(gl, this.mContext)
                this.sun2 = null
            }
            if (this.sun3 != null) {
                this.sun3!!.deleteGLTexture(gl, this.mContext)
                this.sun3 = null
            }
            if (this.sun4 != null) {
                this.sun4!!.deleteGLTexture(gl, this.mContext)
                this.sun4 = null
            }
            if (this.star != null) {
                this.star!!.deleteGLTexture(gl, this.mContext)
                this.star = null
            }
            if (this.meteor != null) {
                this.meteor!!.deleteGLTexture(gl, this.mContext)
                this.meteor = null
            }
            if (this.moon != null) {
                this.moon!!.deleteGLTexture(gl, this.mContext)
                this.moon = null
            }
            if (this.snow1 != null) {
                this.snow1!!.deleteGLTexture(gl, this.mContext)
                this.snow1 = null
            }
            if (this.snow2 != null) {
                this.snow2!!.deleteGLTexture(gl, this.mContext)
                this.snow2 = null
            }
            if (this.snow3 != null) {
                this.snow3!!.deleteGLTexture(gl, this.mContext)
                this.snow3 = null
            }
            if (this.snow4 != null) {
                this.snow4!!.deleteGLTexture(gl, this.mContext)
                this.snow4 = null
            }
            if (this.frost != null) {
                this.frost!!.deleteGLTexture(gl, this.mContext)
                this.frost = null
            }
            if (this.nightcover != null) {
                this.nightcover!!.deleteGLTexture(gl, this.mContext)
                this.nightcover = null
            }
            if (this.logo != null) {
                this.logo!!.deleteGLTexture(gl, this.mContext)
                this.logo = null
            }
            if (this.cityname != null) {
                this.cityname!!.deleteGLTexture(gl, this.mContext)
                this.cityname = null
            }
            if (this.sky_flash != null) {
                this.sky_flash!!.deleteGLTexture(gl, this.mContext)
                this.sky_flash = null
            }
            if (this.lightning1 != null) {
                this.lightning1!!.deleteGLTexture(gl, this.mContext)
                this.lightning1 = null
            }
            if (this.lightning2 != null) {
                this.lightning2!!.deleteGLTexture(gl, this.mContext)
                this.lightning2 = null
            }
            if (this.lightning3 != null) {
                this.lightning3!!.deleteGLTexture(gl, this.mContext)
                this.lightning3 = null
            }
            if (this.windmill_wing != null) {
                this.windmill_wing!!.deleteGLTexture(gl, this.mContext)
                this.windmill_wing = null
            }
            if (this.windmill_wing_blur != null) {
                this.windmill_wing_blur!!.deleteGLTexture(gl, this.mContext)
                this.windmill_wing_blur = null
            }
            if (this.windmill_center_01 != null) {
                this.windmill_center_01!!.deleteGLTexture(gl, this.mContext)
                this.windmill_center_01 = null
            }
            if (this.windmill_pillar_01 != null) {
                this.windmill_pillar_01!!.deleteGLTexture(gl, this.mContext)
                this.windmill_pillar_01 = null
            }
            if (this.windmill_pillar_02 != null) {
                this.windmill_pillar_02!!.deleteGLTexture(gl, this.mContext)
                this.windmill_pillar_02 = null
            }
            if (this.windmill_pillar_flip_01 != null) {
                this.windmill_pillar_flip_01!!.deleteGLTexture(gl, this.mContext)
                this.windmill_pillar_flip_01 = null
            }
            if (this.windmill_pillar_flip_02 != null) {
                this.windmill_pillar_flip_02!!.deleteGLTexture(gl, this.mContext)
                this.windmill_pillar_flip_02 = null
            }
            if (this.land_01 != null) {
                this.land_01!!.deleteGLTexture(gl, this.mContext)
                this.land_01 = null
            }
            if (this.land_02 != null) {
                this.land_02!!.deleteGLTexture(gl, this.mContext)
                this.land_02 = null
            }
            if (this.lawn_01 != null) {
                this.lawn_01!!.deleteGLTexture(gl, this.mContext)
                this.lawn_01 = null
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bImagesetInitialized =
                false
        }

        private fun generateImages(context: Context?) {
            this.sky = Square(context, "sky")
            this.sky_stars = Square(context, "sky_stars")
            this.cloud1 = RectOneToTwo(context, "cloud1")
            this.cloud2 = RectOneToTwo(context, "cloud2")
            this.logo = RectOneToFour(context, "logo")
            this.cityname = RectOneToSixteen(context, "cityname")
            this.sun1 = Square(context, "sun1")
            this.sun2 = Square(context, "sun2")
            this.sun3 = Square(context, "sun3")
            this.sun4 = Square(context, "sun4")
            this.star = Square(context, "star")
            this.meteor = Square(context, "meteor")
            this.moon = Square(context, "moon")
            this.rain1 = Square(context, "rain1")
            this.rain2 = Square(context, "rain2")
            this.rain3 = Square(context, "rain3")
            this.fog = Square(context, "fog")
            this.raindrop1 = arrayOfNulls<RectOneToTwo>(25)
            this.raindrop2 = arrayOfNulls<RectOneToTwo>(25)
            for (i in 0..24) {
                this.raindrop1!![i] = RectOneToTwo(context, "raindrop1_" + i)
                this.raindrop2!![i] = RectOneToTwo(context, "raindrop2_" + i)
            }
            this.waterdrop = Square(context, "waterdrop")
            this.frost = Square(context, "frost")
            this.snow1 = Square(context, "snow1")
            this.snow2 = Square(context, "snow2")
            this.snow3 = Square(context, "snow3")
            this.snow4 = Square(context, "snow4")
            this.nightcover = Square(context, "nightcover")
            this.sky_flash = Square(context, "skyflash")
            this.lightning1 = Square(context, "lightning1")
            this.lightning2 = Square(context, "lightning2")
            this.lightning3 = Square(context, "lightning3")
            this.cloud_light_a_01 = RectOneToTwo(context, "cloud_light_a01")
            this.cloud_light_a_02 = RectOneToTwo(context, "cloud_light_a02")
            this.cloud_light_a_03 = RectOneToTwo(context, "cloud_light_a03")
            this.cloud_light_b_01 = RectOneToTwo(context, "cloud_light_b01")
            this.cloud_light_b_02 = RectOneToTwo(context, "cloud_light_b02")
            this.cloud_light_b_03 = RectOneToTwo(context, "cloud_light_b04")
            this.windmill_wing = Square(context, "windmill_wing")
            this.windmill_wing_blur = Square(context, "windmill_wing_blur")
            this.windmill_center_01 = Square(context, "windmill_center_01")
            this.windmill_pillar_01 = Square(context, "windmill_pillar_01")
            this.windmill_pillar_02 = Square(context, "windmill_pillar_02")
            this.windmill_pillar_flip_01 = Square(context, "windmill_pillar_flip_01")
            this.windmill_pillar_flip_02 = Square(context, "windmill_pillar_flip_02")
            this.land_01 = RectOneToFour(context, "land_01")
            this.land_02 = RectOneToFour(context, "land_02")
            this.lawn_01 = RectOneToFour(context, "lawn_01")
        }

        private fun loadImages(gl10: GL10?, context: Context?, i: Int, z: Boolean) {
            val i2: Int
            val i3: Int
            val mostlyClearScene =
                i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal
            val freezingFogMode =
                i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isFreezingFogCode(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurrentWeatherCode
                )
            val belowFreezingOverlay =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isBelowFreezingNow
                        && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyBelowFreezingFrostEnabled
                        && !this.isPreview
            val highHumidityOverlay =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isHighHumidityNow
                        && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyHighHumidityWaterdropEnabled
                        && !this.isPreview
            val shouldLoadFrost =
                i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal || freezingFogMode
                        || belowFreezingOverlay
            val useSnowGroundTextures =
                i != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null || com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.shouldUseSnowGroundTexturesForSnowScene()
            if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal && !useSnowGroundTextures) {
                Log.d(
                    "WindyWeather",
                    "Delaying snowy ground until next confirmed snow refresh"
                )
            }
            val loadStartNs: Long = System.nanoTime()
            Log.d("WindyWeather", "loadImages weather=" + i + " night=" + z)
            this.isImageSetLoading = true
            if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal) {
                if (!z) {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_01, false)
                    if (mostlyClearScene) {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_01, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_01, false)
                    } else {
                        this.cloud1!!.deleteGLTexture(gl10, context)
                        this.cloud2!!.deleteGLTexture(gl10, context)
                    }
                    this.sun1!!.loadGLTexture(gl10, context, R.drawable.a_sun_01, false)
                    this.sun2!!.loadGLTexture(gl10, context, R.drawable.a_sun_02, false)
                    this.sun3!!.loadGLTexture(gl10, context, R.drawable.a_sun_03, false)
                    this.sun4!!.loadGLTexture(gl10, context, R.drawable.a_sun_04, false)
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_01, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_02, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_01, false)
                    this.star!!.deleteGLTexture(gl10, context)
                    this.meteor!!.deleteGLTexture(gl10, context)
                    this.moon!!.deleteGLTexture(gl10, context)
                } else {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_02, false)
                    this.sky_stars!!.loadGLTexture(gl10, context, R.drawable.d_sky_stars, false)
                    this.star!!.loadGLTexture(gl10, context, R.drawable.d_star, false)
                    this.meteor!!.loadGLTexture(gl10, context, R.drawable.d_meteor, false)
                    val i4: Int =
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase - 1
                    if (i4 < 0) {
                        i3 = 0
                    } else {
                        i3 = if (i4 > 26) 26 else i4
                    }
                    this.moon!!.loadGLTexture(
                        gl10,
                        context,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.moonResouceID[i3],
                        false,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.moonIsReflect[i3]
                    )
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_03, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_04, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_02, false)
                    if (mostlyClearScene) {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_03, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_03, false)
                    } else {
                        this.cloud1!!.deleteGLTexture(gl10, context)
                        this.cloud2!!.deleteGLTexture(gl10, context)
                    }
                    this.sun1!!.deleteGLTexture(gl10, context)
                    this.sun2!!.deleteGLTexture(gl10, context)
                    this.sun3!!.deleteGLTexture(gl10, context)
                    this.sun4!!.deleteGLTexture(gl10, context)
                }
                this.rain1!!.deleteGLTexture(gl10, context)
                this.rain2!!.deleteGLTexture(gl10, context)
                this.rain3!!.deleteGLTexture(gl10, context)
                for (i5 in 0..24) {
                    this.raindrop1!![i5]!!.deleteGLTexture(gl10, this.mContext)
                    this.raindrop2!![i5]!!.deleteGLTexture(gl10, this.mContext)
                }
                this.waterdrop!!.deleteGLTexture(gl10, context)
                this.frost!!.deleteGLTexture(gl10, context)
                this.snow1!!.deleteGLTexture(gl10, context)
                this.snow2!!.deleteGLTexture(gl10, context)
                this.snow3!!.deleteGLTexture(gl10, context)
                this.sky_flash!!.deleteGLTexture(gl10, context)
                this.lightning1!!.deleteGLTexture(gl10, context)
                this.lightning2!!.deleteGLTexture(gl10, context)
                this.lightning3!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_03!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_03!!.deleteGLTexture(gl10, context)
                this.fog!!.deleteGLTexture(gl10, context)
                this.nightcover!!.deleteGLTexture(gl10, context)
                this.bClearOn = true
            } else if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D8_ICE_COLD.ordinal) {
                if (!z) {
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                        this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_03, false)
                    } else {
                        this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_01, false)
                    }
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_03, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_03, false)
                    } else {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_02, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_02, false)
                    }
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_05, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_02, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_03, false)
                    this.moon!!.deleteGLTexture(gl10, context)
                    this.star!!.deleteGLTexture(gl10, context)
                    this.meteor!!.deleteGLTexture(gl10, context)
                    this.nightcover!!.deleteGLTexture(gl10, context)
                } else {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_02, false)
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_03, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_03, false)
                    } else {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_04, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_04, false)
                    }
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                        this.nightcover!!.loadGLTexture(
                            gl10,
                            context,
                            R.drawable.nightcover_01,
                            false
                        )
                    } else {
                        this.nightcover!!.deleteGLTexture(gl10, context)
                    }
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_03, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_04, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_02, false)
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                        this.moon!!.deleteGLTexture(gl10, context)
                        this.star!!.deleteGLTexture(gl10, context)
                        this.meteor!!.deleteGLTexture(gl10, context)
                    } else {
                        val i6: Int =
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurMoonPhase - 1
                        if (i6 < 0) {
                            i2 = 0
                        } else {
                            i2 = if (i6 > 26) 26 else i6
                        }
                        this.moon!!.loadGLTexture(
                            gl10,
                            context,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.moonResouceID[i2],
                            false,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.moonIsReflect[i2]
                        )
                        this.star!!.loadGLTexture(gl10, context, R.drawable.d_star, false)
                        this.meteor!!.loadGLTexture(gl10, context, R.drawable.d_meteor, false)
                    }
                }
                if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                    if (!z) {
                        this.fog!!.loadGLTexture(gl10, context, R.drawable.fog_01, false)
                    } else {
                        this.fog!!.loadGLTexture(gl10, context, R.drawable.fog_02, false)
                    }
                } else {
                    this.fog!!.deleteGLTexture(gl10, context)
                }
                this.sun1!!.deleteGLTexture(gl10, context)
                this.sun2!!.deleteGLTexture(gl10, context)
                this.sun3!!.deleteGLTexture(gl10, context)
                this.sun4!!.deleteGLTexture(gl10, context)
                this.rain1!!.deleteGLTexture(gl10, context)
                this.rain2!!.deleteGLTexture(gl10, context)
                this.rain3!!.deleteGLTexture(gl10, context)
                for (i7 in 0..24) {
                    this.raindrop1!![i7]!!.deleteGLTexture(gl10, this.mContext)
                    this.raindrop2!![i7]!!.deleteGLTexture(gl10, this.mContext)
                }
                this.waterdrop!!.deleteGLTexture(gl10, context)
                if (freezingFogMode) {
                    // Open-Meteo code 48: keep fog visuals, but add frost without switching to snow ground assets.
                    this.frost!!.loadGLTexture(gl10, context, R.drawable.e_frost, false)
                } else {
                    this.frost!!.deleteGLTexture(gl10, context)
                }
                this.snow1!!.deleteGLTexture(gl10, context)
                this.snow2!!.deleteGLTexture(gl10, context)
                this.snow3!!.deleteGLTexture(gl10, context)
                this.snow4!!.deleteGLTexture(gl10, context)
                this.sky_flash!!.deleteGLTexture(gl10, context)
                this.lightning1!!.deleteGLTexture(gl10, context)
                this.lightning2!!.deleteGLTexture(gl10, context)
                this.lightning3!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_03!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_03!!.deleteGLTexture(gl10, context)
                this.sky_stars!!.deleteGLTexture(gl10, context)
            } else if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal) {
                if (!z) {
                    this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_02, true)
                    this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_02, true)
                } else {
                    this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_04, true)
                    this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_04, true)
                }
                if (!z) {
                    // Day rain should use the lighter rain sky, not the night backdrop.
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_03, false)
                    this.nightcover!!.deleteGLTexture(gl10, context)
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_01, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_02, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_01, false)
                } else {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_04, false)
                    this.nightcover!!.loadGLTexture(gl10, context, R.drawable.nightcover_01, false)
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_03, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_04, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_02, false)
                }
                this.waterdrop!!.loadGLTexture(gl10, context, R.drawable.c_waterdrop, false)
                this.rain1!!.loadGLTexture(gl10, context, R.drawable.c_rain_01, false)
                this.rain2!!.loadGLTexture(gl10, context, R.drawable.c_rain_02, false)
                this.rain3!!.loadGLTexture(gl10, context, R.drawable.c_rain_03, false)
                for (i8 in 0..24) {
                    this.raindrop1!![i8]!!.loadGLTexture(
                        gl10,
                        context,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_1[i8],
                        false
                    )
                    this.raindrop2!![i8]!!.loadGLTexture(
                        gl10,
                        context,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_2[i8],
                        false
                    )
                }
                this.frost!!.deleteGLTexture(gl10, context)
                this.sun1!!.deleteGLTexture(gl10, context)
                this.sun2!!.deleteGLTexture(gl10, context)
                this.sun3!!.deleteGLTexture(gl10, context)
                this.sun4!!.deleteGLTexture(gl10, context)
                this.star!!.deleteGLTexture(gl10, context)
                this.meteor!!.deleteGLTexture(gl10, context)
                this.moon!!.deleteGLTexture(gl10, context)
                this.snow1!!.deleteGLTexture(gl10, context)
                this.snow2!!.deleteGLTexture(gl10, context)
                this.snow3!!.deleteGLTexture(gl10, context)
                this.snow4!!.deleteGLTexture(gl10, context)
                this.sky_flash!!.deleteGLTexture(gl10, context)
                this.lightning1!!.deleteGLTexture(gl10, context)
                this.lightning2!!.deleteGLTexture(gl10, context)
                this.lightning3!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_03!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_03!!.deleteGLTexture(gl10, context)
                this.sky_stars!!.deleteGLTexture(gl10, context)
                this.fog!!.deleteGLTexture(gl10, context)
                this.bRainOn = true
            } else if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                if (!z) {
                    this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_02, true)
                    this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_02, true)
                } else {
                    this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_04, true)
                    this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_04, true)
                }
                if (!z) {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_03, false)
                    this.nightcover!!.deleteGLTexture(gl10, context)
                    if (useSnowGroundTextures || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                        this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_06, false)
                        this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_07, false)
                        this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_04, false)
                    } else {
                        this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_01, false)
                        this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_02, false)
                        this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_01, false)
                    }
                } else {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_04, false)
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                        this.nightcover!!.loadGLTexture(
                            gl10,
                            context,
                            R.drawable.nightcover_01,
                            false
                        )
                    } else {
                        this.nightcover!!.deleteGLTexture(gl10, context)
                    }
                    if (useSnowGroundTextures || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                        this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_08, false)
                        this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_09, false)
                        this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_05, false)
                    } else {
                        this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_03, false)
                        this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_04, false)
                        this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_02, false)
                    }
                }
                if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                    this.rain1!!.loadGLTexture(gl10, context, R.drawable.c_rain_01, false)
                    this.rain2!!.loadGLTexture(gl10, context, R.drawable.c_rain_02, false)
                    this.rain3!!.loadGLTexture(gl10, context, R.drawable.c_rain_03, false)
                    for (i9 in 0..24) {
                        this.raindrop1!![i9]!!.loadGLTexture(
                            gl10,
                            context,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_1[i9],
                            false
                        )
                        this.raindrop2!![i9]!!.loadGLTexture(
                            gl10,
                            context,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_2[i9],
                            false
                        )
                    }
                    this.waterdrop!!.loadGLTexture(gl10, context, R.drawable.c_waterdrop, false)
                    this.frost!!.deleteGLTexture(gl10, context)
                } else {
                    this.rain1!!.deleteGLTexture(gl10, context)
                    this.rain2!!.deleteGLTexture(gl10, context)
                    this.rain3!!.deleteGLTexture(gl10, context)
                    for (i10 in 0..24) {
                        this.raindrop1!![i10]!!.deleteGLTexture(gl10, this.mContext)
                        this.raindrop2!![i10]!!.deleteGLTexture(gl10, this.mContext)
                    }
                    this.waterdrop!!.deleteGLTexture(gl10, context)
                    this.frost!!.loadGLTexture(gl10, context, R.drawable.e_frost, false)
                }
                this.snow1!!.loadGLTexture(gl10, context, R.drawable.e_snow_01, false)
                this.snow2!!.loadGLTexture(gl10, context, R.drawable.e_snow_02, false)
                this.snow3!!.loadGLTexture(gl10, context, R.drawable.e_snow_03, false)
                this.snow4!!.loadGLTexture(gl10, context, R.drawable.e_snow_04, false)
                this.sun1!!.deleteGLTexture(gl10, context)
                this.sun2!!.deleteGLTexture(gl10, context)
                this.sun3!!.deleteGLTexture(gl10, context)
                this.sun4!!.deleteGLTexture(gl10, context)
                this.star!!.deleteGLTexture(gl10, context)
                this.meteor!!.deleteGLTexture(gl10, context)
                this.moon!!.deleteGLTexture(gl10, context)
                this.lightning1!!.deleteGLTexture(gl10, context)
                this.lightning2!!.deleteGLTexture(gl10, context)
                this.lightning3!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_a_03!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_01!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_02!!.deleteGLTexture(gl10, context)
                this.cloud_light_b_03!!.deleteGLTexture(gl10, context)
                this.sky_stars!!.deleteGLTexture(gl10, context)
                this.fog!!.deleteGLTexture(gl10, context)
                this.sky_flash!!.deleteGLTexture(gl10, context)
                this.bSnowOn = true
                if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                    this.bRainOn = true
                }
            } else if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal) {
                val thunderstormScene =
                    i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal
                val hideThunderRaindropsLegacy =
                    thunderstormScene && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isHideThunderRaindropsLegacyEnabled
                if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal) {
                    this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_03, false)
                    this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_03, false)
                    this.sky_flash!!.deleteGLTexture(gl10, context)
                    this.cloud_light_a_01!!.deleteGLTexture(gl10, context)
                    this.cloud_light_a_02!!.deleteGLTexture(gl10, context)
                    this.cloud_light_a_03!!.deleteGLTexture(gl10, context)
                    this.cloud_light_b_01!!.deleteGLTexture(gl10, context)
                    this.cloud_light_b_02!!.deleteGLTexture(gl10, context)
                    this.cloud_light_b_03!!.deleteGLTexture(gl10, context)
                    this.rain1!!.deleteGLTexture(gl10, context)
                    this.rain2!!.deleteGLTexture(gl10, context)
                    this.rain3!!.deleteGLTexture(gl10, context)
                    this.lightning1!!.deleteGLTexture(gl10, context)
                    this.lightning2!!.deleteGLTexture(gl10, context)
                    this.lightning3!!.deleteGLTexture(gl10, context)
                } else {
                    if (!z) {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_02, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_02, false)
                    } else {
                        this.cloud1!!.loadGLTexture(gl10, context, R.drawable.cloud_a_04, false)
                        this.cloud2!!.loadGLTexture(gl10, context, R.drawable.cloud_b_04, false)
                    }
                    this.sky_flash!!.loadGLTexture(gl10, context, R.drawable.g_sky_flash, false)
                    this.cloud_light_a_01!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_a_light_01,
                        true
                    )
                    this.cloud_light_a_02!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_a_light_02,
                        true
                    )
                    this.cloud_light_a_03!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_a_light_03,
                        true
                    )
                    this.cloud_light_b_01!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_b_light_01,
                        true
                    )
                    this.cloud_light_b_02!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_b_light_02,
                        true
                    )
                    this.cloud_light_b_03!!.loadGLTexture(
                        gl10,
                        context,
                        R.drawable.cloud_b_light_03,
                        true
                    )
                    this.rain1!!.loadGLTexture(gl10, context, R.drawable.c_rain_01, false)
                    this.rain2!!.loadGLTexture(gl10, context, R.drawable.c_rain_02, false)
                    this.rain3!!.loadGLTexture(gl10, context, R.drawable.c_rain_03, false)
                    this.lightning1!!.loadGLTexture(gl10, context, R.drawable.g_lightning_01, false)
                    this.lightning2!!.loadGLTexture(gl10, context, R.drawable.g_lightning_02, false)
                    this.lightning3!!.loadGLTexture(gl10, context, R.drawable.g_lightning_03, false)
                }
                if (!z) {
                    this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_03, false)
                    this.nightcover!!.deleteGLTexture(gl10, context)
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_05, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_02, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_03, false)
                } else {
                    if (i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal || i == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal) {
                        this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_04, false)
                    } else {
                        this.sky!!.loadGLTexture(gl10, context, R.drawable.sky_02, false)
                    }
                    this.nightcover!!.loadGLTexture(gl10, context, R.drawable.nightcover_01, false)
                    this.land_01!!.loadGLTexture(gl10, context, R.drawable.a_land_03, false)
                    this.land_02!!.loadGLTexture(gl10, context, R.drawable.a_land_04, false)
                    this.lawn_01!!.loadGLTexture(gl10, context, R.drawable.a_lawn_02, false)
                }
                this.sun1!!.deleteGLTexture(gl10, context)
                this.sun2!!.deleteGLTexture(gl10, context)
                this.sun3!!.deleteGLTexture(gl10, context)
                this.sun4!!.deleteGLTexture(gl10, context)
                this.star!!.deleteGLTexture(gl10, context)
                this.meteor!!.deleteGLTexture(gl10, context)
                this.moon!!.deleteGLTexture(gl10, context)
                if (thunderstormScene && !hideThunderRaindropsLegacy) {
                    this.waterdrop!!.loadGLTexture(gl10, context, R.drawable.c_waterdrop, false)
                    for (i11 in 0..24) {
                        this.raindrop1!![i11]!!.loadGLTexture(
                            gl10,
                            context,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_1[i11],
                            false
                        )
                        this.raindrop2!![i11]!!.loadGLTexture(
                            gl10,
                            context,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop_id_2[i11],
                            false
                        )
                    }
                } else {
                    this.waterdrop!!.deleteGLTexture(gl10, context)
                    for (i11 in 0..24) {
                        this.raindrop1!![i11]!!.deleteGLTexture(gl10, this.mContext)
                        this.raindrop2!![i11]!!.deleteGLTexture(gl10, this.mContext)
                    }
                }
                this.frost!!.deleteGLTexture(gl10, context)
                this.snow1!!.deleteGLTexture(gl10, context)
                this.snow2!!.deleteGLTexture(gl10, context)
                this.snow3!!.deleteGLTexture(gl10, context)
                this.snow4!!.deleteGLTexture(gl10, context)
                this.sky_stars!!.deleteGLTexture(gl10, context)
                this.fog!!.deleteGLTexture(gl10, context)
                this.bRainOn = thunderstormScene && !hideThunderRaindropsLegacy
                this.bThunderOn = true
            }
            if (shouldLoadFrost) {
                this.frost!!.loadGLTexture(gl10, context, R.drawable.e_frost, false)
            } else {
                this.frost!!.deleteGLTexture(gl10, context)
            }
            if (highHumidityOverlay && !this.waterdrop!!.textureLoaded) {
                this.waterdrop!!.loadGLTexture(gl10, context, R.drawable.c_waterdrop, false)
            }
            if (!this.windmill_wing!!.textureLoaded) {
                this.windmill_wing!!.loadGLTexture(gl10, context, R.drawable.a_windmill_wing, false)
            }
            if (!this.windmill_wing_blur!!.textureLoaded) {
                this.windmill_wing_blur!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_wing_blur2,
                    false
                )
            }
            if (!this.windmill_center_01!!.textureLoaded) {
                this.windmill_center_01!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_center_01,
                    false
                )
            }
            if (!this.windmill_pillar_01!!.textureLoaded) {
                this.windmill_pillar_01!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_pillar_01,
                    false
                )
            }
            if (!this.windmill_pillar_02!!.textureLoaded) {
                this.windmill_pillar_02!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_pillar_blur2_02,
                    false
                )
            }
            if (!this.windmill_pillar_flip_01!!.textureLoaded) {
                this.windmill_pillar_flip_01!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_pillar_flip_01,
                    false
                )
            }
            if (!this.windmill_pillar_flip_02!!.textureLoaded) {
                this.windmill_pillar_flip_02!!.loadGLTexture(
                    gl10,
                    context,
                    R.drawable.a_windmill_pillar_flip_blur2_02,
                    false
                )
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bEnableLogo) {
                val logoResource: Int =
                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null
                        && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyClassicWatermarkEnabled
                    )
                        R.drawable.logo_legacy
                    else
                        R.drawable.logo
                this.logo!!.loadGLTexture(gl10, context, logoResource, false)
            } else if (this.logo!!.textureLoaded) {
                this.logo!!.deleteGLTexture(gl10, context)
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isCityNameVisible) {
                loadCityName(gl10, context)
            } else {
                this.cityname!!.deleteGLTexture(gl10, context)
            }
            this.loadedImageset = i
            this.loadedImagesetDayNight = z
            this.isImageSetLoading = false
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bImagesetInitialized =
                true
            val loadDurationMs: Long = (System.nanoTime() - loadStartNs) / 1000000L
            Log.d(
                "WindyWeather",
                "loadImages complete loadedWeather=" + this.loadedImageset + " loadedNight=" + this.loadedImagesetDayNight + " durationMs=" + loadDurationMs
            )
            Runtime.getRuntime().totalMemory()
            val jFreeMemory: Long =
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        }

        fun loadCityName(gl: GL10?, context: Context?) {
            if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isCityNameVisible) {
                this.cityname!!.deleteGLTexture(gl, context)
                this.mLastCityNameTextureText = null
                return
            }
            val cityText =
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName == null) "" else com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityName
            if (cityText.equals(this.mLastCityNameTextureText) && this.cityname!!.textureLoaded) {
                return
            }
            val cityNameTv: InfoTextView = InfoTextView(this.mContext, 1024, 80)
            cityNameTv.setTextColor(Color.argb(160, 235, 235, 235))
            cityNameTv.setTextSize(10.0f)
            // Prefer current system sans (Samsung Sans on Samsung devices where configured).
            cityNameTv.setTextFont(InfoTextView.eFontStyle.FONT_STYLE_DROIDSANS)
            cityNameTv.setTextGravity(Gravity.START or Gravity.CENTER_VERTICAL)
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityNameBmp =
                cityNameTv.GetBitmapWithText(1024, 80, cityText)
            val unused: Bitmap? =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityNameBmp
            this.cityname!!.loadGLTexture(
                gl,
                context,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mCityNameBmp
            )
            this.mLastCityNameTextureText = cityText
        }

        var loadedImageset: Int
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mLoadedImageset
            set(nWeather) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mLoadedImageset =
                    nWeather
            }

        var loadedImagesetDayNight: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mLoadedImagesetDayNight
            set(bNight) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mLoadedImagesetDayNight =
                    bNight
            }

        fun setSystemOffset(xOffset: Float, preview: Boolean, touchEnabled: Boolean) {
            if (!touchEnabled || preview) {
                this.mOffset = 1.0f
                return
            }
            var offset = 2.5f * xOffset
            if (!this.mIsPortrait) {
                offset = 1.0f + ((offset - 1.0f) * 0.55f)
            }
            this.mOffset = offset
        }

        private val groundParallaxOffset: Float
            get() {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null
                    || !com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isGroundParallaxEnabled
                ) {
                    return 1.0f
                }
                return this.mOffset
            }

        private val skyParallaxShift: Float
            get() = (1.5f - 1.0f) * 5.0f

        private fun getGroundParallaxShift(weight: Float): Float {
            return (1.5f - (this.groundParallaxOffset * weight)) * 5.0f
        }

        var isImageSetLoading: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbImageSetLoading
            set(bLoading) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbImageSetLoading =
                    bLoading
            }

        private val isImagesetInitialized: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bImagesetInitialized && this.sky != null

        private val isMemInitialized: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bMemoryInitialized && com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw != null

        override fun onDrawFrame(gl10: GL10?) {
            if (gl10 == null) {
                return
            }
            val frameStartNs: Long = System.nanoTime()
            val animationFrameStep = resolveAnimationFrameStep(frameStartNs)
            if (!this.isImagesetInitialized) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                    true
                )
                this.mOnSurfaceChanged = false
            }
            if (!this.mbImgLoaded) {
                this.mbImgLoaded = true
            } else {
                if (this.isPreview) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setPreviewWeather()
                }
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mbImageSetChange && !this.mOnSurfaceChanged && !this.isImageSetLoading) {
                    loadImages(
                        gl10,
                        this.mContext,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather,
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isNightEffective
                    )
                    if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                            false
                        )
                    } else {
                        Log.d(
                            "WindyWeather",
                            "Loaded imageset & Current Weather are different"
                        )
                    }
                }
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mbCityNameChange) {
                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isCityNameVisible) {
                        loadCityName(gl10, this.mContext)
                    } else {
                        this.cityname!!.deleteGLTexture(gl10, this.mContext)
                    }
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setCityNameChange(
                        false
                    )
                }
            }
            if (!this.isImageSetLoading) {
                gl10.glClear(16640)
                gl10.glBlendFunc(1, 771)
                logOverlayStateIfChanged()
                try {
                    drawObjects(gl10, animationFrameStep)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                advanceFrameCounter(animationFrameStep)
                this.mOnSurfaceChanged = false
                recordFrameTiming(System.nanoTime() - frameStartNs)
            }
        }

        internal inner class DrawingAttribute {
            private var mfXpos = 0.0f
            private var mfYpos = 0.0f
            private var mfZpos = -20.0f
            private var mfXscale = 1.0f
            var mfYscale = 1.0f

            init {
                setAttribute(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            }

            fun moveTo(gl: GL10, nDistance: Int) {
                var weight = 0.0f
                gl.glLoadIdentity()
                when (nDistance) {
                    0 -> weight = 1.2f
                    1 -> weight = 0.5f
                    2 -> weight = 0.2f
                    else -> Log.d("WindyWeather", "distance not set")
                }
                val landscapeXShift = if (this@CSPRenderer.mIsPortrait) 0.0f else -0.12f
                val landscapeYShift = if (this@CSPRenderer.mIsPortrait) 0.0f else 0.08f
                val landscapeYScale = if (this@CSPRenderer.mIsPortrait) 1.0f else 1.14f
                gl.glTranslatef(
                    (this.mfXpos - 1.5f) + this@CSPRenderer.getGroundParallaxShift(weight) + landscapeXShift,
                    this.mfYpos + landscapeYShift,
                    this.mfZpos
                )
                gl.glScalef(
                    this.mfXscale * this@CSPRenderer.mfLandscape,
                    this.mfYscale * landscapeYScale,
                    0.0f
                )
            }


            fun moveToWithOffset(gl: GL10, nDistance: Int, extraX: Float, extraY: Float) {
                var weight = 0.0f
                gl.glLoadIdentity()
                when (nDistance) {
                    0 -> weight = 1.2f
                    1 -> weight = 0.5f
                    2 -> weight = 0.2f
                    else -> Log.d("WindyWeather", "distance not set")
                }
                val landscapeXShift = if (this@CSPRenderer.mIsPortrait) 0.0f else -0.12f
                val landscapeYShift = if (this@CSPRenderer.mIsPortrait) 0.0f else 0.08f
                val landscapeYScale = if (this@CSPRenderer.mIsPortrait) 1.0f else 1.14f
                gl.glTranslatef(
                    (this.mfXpos - 1.5f) + this@CSPRenderer.getGroundParallaxShift(weight) + landscapeXShift + extraX,
                    this.mfYpos + landscapeYShift + extraY,
                    this.mfZpos
                )
                gl.glScalef(
                    this.mfXscale * this@CSPRenderer.mfLandscape,
                    this.mfYscale * landscapeYScale,
                    0.0f
                )
            }

            fun setAttribute(xPos: Float, yPos: Float, zPos: Float, xScale: Float, yScale: Float) {
                setXpos(xPos)
                setYpos(yPos)
                setZpos(zPos)
                setXscale(xScale)
                setYscale(yScale)
            }

            fun setXpos(fXpos: Float) {
                this.mfXpos = fXpos
            }

            fun setYpos(fYpos: Float) {
                this.mfYpos = fYpos
            }

            fun setZpos(fZpos: Float) {
                this.mfZpos = fZpos
            }

            fun setXscale(fXscale: Float) {
                this.mfXscale = fXscale
            }

            fun setYscale(fYscale: Float) {
                this.mfYscale = fYscale
            }
        }

        internal inner class WindMill {
            var isCreated: Boolean = false
                private set
            var mPillar: DrawingAttribute? = null
            var mCenter: DrawingAttribute? = null
            var mWing: DrawingAttribute? = null
            private var mfFanAngle = 0.0f
            private var mbType = true
            var mnDistance = 0
            private var mfAlpha = 1.0f
            private var mbFlip = false

            init {
                this.isCreated = true
                create()
            }

            fun create() {
                this.mPillar = this@CSPRenderer.DrawingAttribute()
                this.mCenter = this@CSPRenderer.DrawingAttribute()
                this.mWing = this@CSPRenderer.DrawingAttribute()
            }

            fun destroy() {
                this.mPillar = null
                this.mCenter = null
                this.mWing = null
            }

            fun setFanAngle(fFanAngle: Float) {
                this.mfFanAngle = fFanAngle
            }

            fun setType(bType: Boolean) {
                this.mbType = bType
            }

            fun setDistance(nDistance: Int) {
                this.mnDistance = nDistance
            }

            fun setAlpha(fAlpha: Float) {
                this.mfAlpha = fAlpha
            }

            fun setFlip(bFlip: Boolean) {
                this.mbFlip = bFlip
            }

            fun drawWindMill(gl: GL10, bType: Boolean) {
                var fColor = 1.0f
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isNightEffective) {
                    val oldNightEffect: Boolean =
                        com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isOldNightEffectEnabled
                    if (oldNightEffect) {
                        fColor = 0.0f
                    } else {
                        fColor = 0.2f
                    }
                }
                var landscapeWingYOffset = 0.0f
                if (!this@CSPRenderer.mIsPortrait) {
                    val landscapeYScale = 1.14f
                    // Keep wing/hub attached as pillar height scales in landscape.
                    landscapeWingYOffset = this.mPillar!!.mfYscale * 8.0f * (landscapeYScale - 1.0f)
                }
                if (this.mnDistance == 0) {
                    this.mPillar!!.moveTo(gl, this.mnDistance)
                    if (!this.mbFlip) {
                        this@CSPRenderer.windmill_pillar_01!!.shortdraw(gl, fColor, this.mfAlpha)
                    } else {
                        this@CSPRenderer.windmill_pillar_flip_01!!.shortdraw(gl, fColor, this.mfAlpha)
                    }
                } else {
                    this.mPillar!!.moveTo(gl, this.mnDistance)
                    if (!this.mbFlip) {
                        this@CSPRenderer.windmill_pillar_02!!.shortdraw(gl, fColor, this.mfAlpha)
                    } else {
                        this@CSPRenderer.windmill_pillar_flip_02!!.shortdraw(gl, fColor, this.mfAlpha)
                    }
                }
                this.mWing!!.moveToWithOffset(gl, this.mnDistance, 0.0f, landscapeWingYOffset)
                gl.glRotatef(this.mfFanAngle, 0.0f, 0.0f, 1.0f)
                if (this.mnDistance == 0) {
                    this@CSPRenderer.windmill_wing!!.shortdraw(gl, fColor, this.mfAlpha)
                } else {
                    this@CSPRenderer.windmill_wing_blur!!.shortdraw(gl, fColor, this.mfAlpha)
                }
                if (this.mnDistance == 0) {
                    this.mCenter!!.moveToWithOffset(gl, this.mnDistance, 0.0f, landscapeWingYOffset)
                    this@CSPRenderer.windmill_center_01!!.shortdraw(gl, fColor, this.mfAlpha)
                }
            }
        }

        private fun drawObjects(gl10: GL10, animationFrameStep: Float) {
            var f: Float
            var f2: Float
            var f3: Float
            var f4: Float
            var f5: Float
            val fSqrt: Float
            val landscapeSceneFill =
                if (this.mIsPortrait) 1.0f else clamp(1.0f, this.mSurfaceAspect / 1.7777778f, 1.28f)
            val landscapeGroundFill =
                if (this.mIsPortrait) 1.0f else clamp(1.0f, this.mSurfaceAspect / 1.7777778f, 1.45f)
            val landscapeOverlayFill =
                if (this.mIsPortrait) 1.0f else clamp(1.0f, this.mSurfaceAspect / 1.7777778f, 1.50f)
            val cloudWrapSpan = if (this.mIsPortrait) 50.0f else 84.0f * landscapeSceneFill
            val cloudWrapSpanShort = if (this.mIsPortrait) 40.0f else 72.0f * landscapeSceneFill
            val loadedWeather = this.loadedImageset
            val loadedNight = this.loadedImagesetDayNight
            val clearFamilyScene =
                loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal
            val freezingFogOverlay =
                loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.isFreezingFogCode(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurrentWeatherCode
                )
            val belowFreezingOverlay =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isBelowFreezingNow
                        && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyBelowFreezingFrostEnabled
                        && !this.isPreview
            val humidityWaterdropOverlay =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isHighHumidityNow
                        && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyHighHumidityWaterdropEnabled
                        && !this.isPreview
            if (this.isPreview) {
                this.mOffset = 1.0f
            }
            val skyShift = this.skyParallaxShift
            val groundOffset = this.groundParallaxOffset
            val windmillRotationPerFrame = resolveWindmillDegreesPerFrame() * animationFrameStep
            this.mWindmillAngle -= windmillRotationPerFrame
            if (this.mWindmillAngle < -360000.0f) {
                this.mWindmillAngle += 360000.0f
            }
            gl10.glFrontFace(2305)
            gl10.glEnable(2884)
            gl10.glCullFace(1029)
            gl10.glEnableClientState(32884)
            gl10.glEnableClientState(32888)
            gl10.glLoadIdentity()
            gl10.glTranslatef((-1.5f) + skyShift, -2.3f, -30.0f)
            val skyScaleX =
                if (this.mIsPortrait) 2.0f * this.mfLandscape else 2.2f * this.mfLandscape * landscapeSceneFill
            gl10.glScalef(skyScaleX, 2.0f, 0.0f)
            this.sky!!.shortdraw(gl10, 1.0f, 1.0f)
            if (loadedNight && (loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal)) {
                this.nightcover!!.shortdraw(gl10, 1.0f, 1.0f)
            }
            if (clearFamilyScene && loadedNight) {
                gl10.glLoadIdentity()
                gl10.glTranslatef(1.3f + skyShift, 7.0f, -29.9f)
                gl10.glScalef(1.8f * this.mfLandscape, 0.45f, 0.0f)
                this.sky_stars!!.shortdraw(gl10, 1.0f, 1.0f)
            }
            if (clearFamilyScene || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D8_ICE_COLD.ordinal) {
                if (!loadedNight) {
                    if (clearFamilyScene) {
                        this.fAlpha = 1.0f
                        gl10.glLoadIdentity()
                        gl10.glTranslatef((skyShift * 0.2f) + 3.0f, 6.0f, -28.0f)
                        gl10.glRotatef(this.mFrameCnt * 0.54f, 0.0f, 0.0f, 1.0f)
                        this.sun1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                        gl10.glLoadIdentity()
                        gl10.glTranslatef((skyShift * 0.2f) + 3.0f, 6.0f, -28.0f)
                        gl10.glRotatef(this.mFrameCnt * 0.36f, 0.0f, 0.0f, 1.0f)
                        this.sun2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                        gl10.glLoadIdentity()
                        gl10.glTranslatef((skyShift * 0.2f) + 3.0f, 6.0f, -28.0f)
                        gl10.glRotatef(this.mFrameCnt * (-0.54f), 0.0f, 0.0f, 1.0f)
                        this.sun3!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                    }
                } else {
                    this.fAlpha = 1.0f
                    gl10.glLoadIdentity()
                    val moonBaseX = if (!this.m1280x720) 3.2f else 2.8f
                    var moonShift = skyShift
                    if (this.mIsPortrait) {
                        moonShift *= 0.2f
                        val moonHalfWidth = 2.4f
                        val moonMargin = 0.25f
                        val rightLimit = getWorldHalfWidth(28.5f) - moonHalfWidth - moonMargin
                        val moonX: Float = Math.min(moonBaseX + moonShift, rightLimit)
                        gl10.glTranslatef(moonX, 7.0f, -28.5f)
                    } else {
                        gl10.glTranslatef(moonBaseX + moonShift, 7.0f, -28.5f)
                    }
                    gl10.glScalef(0.3f, 0.3f, 0.0f)
                    this.moon!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                    if (this.mFrameCnt % 200 == 0 || this.bClearOn) {
                        for (i in 0..6) {
                            if (Math.random() > 0.20000000298023224) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw!![i] =
                                    true
                            } else {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw!![i] =
                                    false
                            }
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star!![i] =
                                (Math.random() * 100.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star!![i] =
                                0.0f
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.dur_star!![i] =
                                ((Math.random() * 20.0) + 30.0).toInt()
                        }
                    } else {
                        for (i2 in 0..6) {
                            if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.b_star_draw!![i2] && this.mFrameCnt % 200 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star!![i2]) {
                                if (this.mFrameCnt % 200 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star!![i2] + com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.dur_star!![i2]) {
                                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star!![i2] < 1.0f) {
                                        val fArr: FloatArray? =
                                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star
                                        fArr!![i2] = ((fArr[i2].toDouble()) + 0.04).toFloat()
                                    }
                                } else if (this.mFrameCnt % 200 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.start_star!![i2] + (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.dur_star!![i2] * 2) && com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star!![i2] > 0.0f) {
                                    val fArr2: FloatArray? =
                                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star
                                    fArr2!![i2] = ((fArr2[i2].toDouble()) - 0.04).toFloat()
                                }
                                gl10.glLoadIdentity()
                                gl10.glTranslatef(
                                    this.x_star[i2] + skyShift,
                                    this.y_star[i2],
                                    -28.0f
                                )
                                gl10.glScalef(
                                    this.mfLandscape * this.size_star[i2],
                                    this.size_star[i2],
                                    0.0f
                                )
                                this.star!!.shortdraw(
                                    gl10,
                                    com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star!![i2],
                                    com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.alpha_star!![i2]
                                )
                            }
                        }
                    }
                    if (this.mFrameCnt % 200 == 0 || this.bClearOn) {
                        if (this.bClearOn) {
                            this.x_a_meteor = 9.0f
                            this.y_a_meteor = 10.0f
                            this.scale_a_meteor = 0.4f
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.nMeteorInitCnt =
                                0
                            this.alpha_a_meteor = 1.0f
                        } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.nMeteorInitCnt > 199) {
                            this.x_a_meteor = ((Math.random() * 6.0) + 5.0).toFloat()
                            this.y_a_meteor = ((Math.random() * 8.0) + 8.0).toFloat()
                            this.scale_a_meteor = (Math.random() * 0.2).toFloat() + 0.2f
                            this.alpha_a_meteor = 1.0f
                        }
                        this.bClearOn = false
                    } else {
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.nMeteorInitCnt < 200) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.nMeteorInitCnt++
                        }
                        this.x_a_meteor = ((this.x_a_meteor.toDouble()) - 0.45).toFloat()
                        this.y_a_meteor = ((this.y_a_meteor.toDouble()) - 0.3).toFloat()
                        this.scale_a_meteor *= 0.98f
                        this.alpha_a_meteor *= 0.9f
                        gl10.glLoadIdentity()
                        gl10.glTranslatef(this.x_a_meteor + skyShift, this.y_a_meteor, -28.0f)
                        gl10.glScalef(
                            this.mfLandscape * this.scale_a_meteor,
                            this.scale_a_meteor,
                            0.0f
                        )
                        this.meteor!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                    }
                }
            }
            // Mostly clear owns this cloud pass; clear sky scenes stay cloud-free.
            if (loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal) {
                if (this.mFrameCnt < 100) {
                    this.x_a_cloud_A_3 = (((-0.025) * (this.mFrameCnt.toDouble())) - 18.0).toFloat()
                } else {
                    this.x_a_cloud_A_3 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 18.0)).toFloat()
                }
                if (this.mFrameCnt < 530) {
                    this.x_a_cloud_A_1 = (((-0.025) * (this.mFrameCnt.toDouble())) - 11.0).toFloat()
                } else {
                    this.x_a_cloud_A_1 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 11.0)).toFloat()
                }
                if (this.mFrameCnt < 400) {
                    this.x_a_cloud_B_3 = (((-0.025) * (this.mFrameCnt.toDouble())) - 9.0).toFloat()
                } else {
                    this.x_a_cloud_B_3 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 9.0)).toFloat()
                }
                if (this.mFrameCnt < 850) {
                    this.x_a_cloud_B_2 = (((-0.025) * (this.mFrameCnt.toDouble())) + 2.0).toFloat()
                } else {
                    this.x_a_cloud_B_2 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 2.0)).toFloat()
                }
                if (this.mFrameCnt < 1000) {
                    this.x_a_cloud_B_1 = (((-0.025) * (this.mFrameCnt.toDouble())) + 5.0).toFloat()
                } else {
                    this.x_a_cloud_B_1 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 5.0)).toFloat()
                }
                if (this.mFrameCnt < 1080) {
                    this.x_a_cloud_A_2 = (((-0.025) * (this.mFrameCnt.toDouble())) + 8.0).toFloat()
                } else {
                    this.x_a_cloud_A_2 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 8.0)).toFloat()
                }
                if (this.mFrameCnt < 1450) {
                    this.x_a_cloud_A_4 = (((-0.025) * (this.mFrameCnt.toDouble())) + 13.0).toFloat()
                } else {
                    this.x_a_cloud_A_4 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 13.0)).toFloat()
                }
                this.fAlpha = if (loadedNight) 0.45f else 0.3f
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_A_3 + skyShift, 4.5f, -27.0f)
                gl10.glScalef(2.8f * this.mfLandscape, 2.8f, 0.0f)
                this.cloud1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                this.fAlpha = if (loadedNight) 0.6f else 0.45f
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_1 + skyShift, -3.2f, -26.0f)
                gl10.glScalef(2.8f * this.mfLandscape, 2.8f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
            } else if (loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D8_ICE_COLD.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                if (this.mFrameCnt < 360) {
                    this.x_a_cloud_A_1 = (((-0.025) * (this.mFrameCnt.toDouble())) - 24.0).toFloat()
                } else {
                    this.x_a_cloud_A_1 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 24.0)).toFloat()
                }
                if (this.mFrameCnt < 1100) {
                    this.x_a_cloud_B_1 = (((-0.025) * (this.mFrameCnt.toDouble())) + 4.0).toFloat()
                } else {
                    this.x_a_cloud_B_1 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 4.0)).toFloat()
                }
                if (this.mFrameCnt < 400) {
                    this.x_a_cloud_B_2 = (((-0.025) * (this.mFrameCnt.toDouble())) - 10.0).toFloat()
                } else {
                    this.x_a_cloud_B_2 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 10.0)).toFloat()
                }
                if (this.mFrameCnt < 600) {
                    this.x_a_cloud_A_2 = (((-0.025) * (this.mFrameCnt.toDouble())) - 5.5).toFloat()
                } else {
                    this.x_a_cloud_A_2 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 5.5)).toFloat()
                }
                if (this.mFrameCnt < 850) {
                    this.x_a_cloud_A_3 = (((-0.025) * (this.mFrameCnt.toDouble())) + 2.5).toFloat()
                } else {
                    this.x_a_cloud_A_3 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) + 2.5)).toFloat()
                }
                if (this.mFrameCnt < 650) {
                    this.x_a_cloud_B_3 = (((-0.025) * (this.mFrameCnt.toDouble())) - 7.5).toFloat()
                } else {
                    this.x_a_cloud_B_3 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 7.5)).toFloat()
                }
                if (this.mFrameCnt < 800) {
                    this.x_a_cloud_B_4 = (((-0.025) * (this.mFrameCnt.toDouble())) - 5.0).toFloat()
                } else {
                    this.x_a_cloud_B_4 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 5.0)).toFloat()
                }
                if (this.mFrameCnt < 300) {
                    this.x_a_cloud_B_5 = (((-0.025) * (this.mFrameCnt.toDouble())) - 15.0).toFloat()
                } else {
                    this.x_a_cloud_B_5 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 15.0)).toFloat()
                }
                if (this.mFrameCnt < 110) {
                    this.x_a_cloud_B_6 = (((-0.025) * (this.mFrameCnt.toDouble())) - 30.0).toFloat()
                } else {
                    this.x_a_cloud_B_6 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpan.toDouble()) - 30.0)).toFloat()
                }
                if (this.mFrameCnt < 1000) {
                    this.x_a_cloud_B_7 = (((-0.025) * (this.mFrameCnt.toDouble())) + 5.0).toFloat()
                } else {
                    this.x_a_cloud_B_7 =
                        (((-0.025) * (this.mFrameCnt.toDouble())) + ((cloudWrapSpanShort.toDouble()) + 5.0)).toFloat()
                }
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.25f
                } else {
                    this.fAlpha = 0.9f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_A_1 + skyShift, 5.5f, -27.0f)
                gl10.glScalef(4.0f * this.mfLandscape, 4.4f, 0.0f)
                this.cloud1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.2f
                } else {
                    this.fAlpha = 0.2f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_A_2 + skyShift, 4.8f, -27.3f)
                gl10.glScalef(3.6f * this.mfLandscape, 3.6f, 0.0f)
                this.cloud1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.2f
                } else {
                    this.fAlpha = 0.2f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_A_3 + skyShift, 2.0f, -27.5f)
                gl10.glScalef(2.8f * this.mfLandscape, 2.8f, 0.0f)
                this.cloud1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.25f
                } else {
                    this.fAlpha = 0.5f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_3 + skyShift, 3.2f, -27.4f)
                gl10.glScalef(2.8f * this.mfLandscape, 3.2f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.25f
                } else {
                    this.fAlpha = 0.9f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_1 + skyShift, 6.2f, -26.9f)
                gl10.glScalef(4.4f * this.mfLandscape, 5.0f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.25f
                } else {
                    this.fAlpha = 0.3f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_2 + skyShift, 7.2f, -27.1f)
                gl10.glScalef(3.4f * this.mfLandscape, 3.4f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.2f
                } else {
                    this.fAlpha = 0.4f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_4 + skyShift, 0.2f, -27.6f)
                gl10.glScalef(3.2f * this.mfLandscape, 3.2f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.25f
                } else {
                    this.fAlpha = 0.4f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_5 + skyShift, 0.3f, -27.7f)
                gl10.glScalef(3.8f * this.mfLandscape, 3.8f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.2f
                } else {
                    this.fAlpha = 0.2f
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(this.x_a_cloud_B_6 + skyShift, -0.2f, -27.8f)
                gl10.glScalef(4.4f * this.mfLandscape, 4.4f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImagesetDayNight) {
                    this.fAlpha = 0.15f
                } else {
                    this.fAlpha = 0.47f
                }
                gl10.glLoadIdentity()
                gl10.glScalef(8.0f * this.mfLandscape, 8.0f, 0.0f)
                this.cloud2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal) {
                    if (this.bThunderOn || this.mFrameCnt % 400 == 0) {
                        for (i3 in 0..19) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![i3] =
                                (Math.random() * 390.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![i3] =
                                (Math.random() * 20.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![i3] =
                                (Math.random() * 9.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i3] =
                                (Math.random() * 3.0).toInt()
                        }
                        if (this.bThunderOn) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![0] =
                                5
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![0] =
                                10
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![0] =
                                5
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![0] =
                                2
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![1] =
                                15
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![1] =
                                15
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![1] =
                                6
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![1] =
                                1
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![2] =
                                20
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![2] =
                                20
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![2] =
                                7
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![2] =
                                2
                        }
                    } else {
                        for (i5 in 0..19) {
                            if (this.mFrameCnt % 400 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![i5] && this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![i5] + com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![i5]) {
                                var f6 = 0.0f
                                var f7 = 0.0f
                                var f8 = 0.0f
                                when (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![i5]) {
                                    0 -> {
                                        f6 = this.x_a_cloud_A_1
                                        f7 = 5.5f
                                        f8 = 2.2f
                                    }

                                    1 -> {
                                        f6 = this.x_a_cloud_A_2
                                        f7 = 6.0f
                                        f8 = 1.5f
                                    }

                                    2 -> {
                                        f6 = this.x_a_cloud_A_3
                                        f7 = 3.5f
                                        f8 = 1.2f
                                    }

                                    3 -> {
                                        f6 = this.x_a_cloud_B_1
                                        f7 = 6.5f
                                        f8 = 2.0f
                                    }

                                    4 -> {
                                        f6 = this.x_a_cloud_B_2
                                        f7 = 8.0f
                                        f8 = 1.2f
                                    }

                                    5 -> {
                                        f6 = this.x_a_cloud_B_3
                                        f7 = 5.8f
                                        f8 = 1.0f
                                    }

                                    6 -> {
                                        f6 = this.x_a_cloud_B_4
                                        f7 = 1.8f
                                        f8 = 1.6f
                                    }

                                    7 -> {
                                        f6 = this.x_a_cloud_B_5
                                        f7 = 0.8f
                                        f8 = 2.2f
                                    }

                                    8 -> {
                                        f6 = this.x_a_cloud_B_6
                                        f7 = 0.5f
                                        f8 = 1.2f
                                    }
                                }
                                if (this.mFrameCnt % 400 < (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_start!![i5].toDouble()) + ((com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_duration!![i5].toDouble()) * 0.5)) {
                                    this.fAlpha = 0.6f + (Math.random().toFloat() * 0.4f)
                                } else {
                                    this.fAlpha = 0.0f + (Math.random().toFloat() * 0.4f)
                                }
                                gl10.glLoadIdentity()
                                gl10.glTranslatef(f6 + skyShift, f7, -26.0f)
                                gl10.glScalef(this.mfLandscape * f8, f8, 0.0f)
                                if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_num!![i5] < 3) {
                                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 0) {
                                        this.cloud_light_a_01!!.shortdraw(
                                            gl10,
                                            this.fAlpha,
                                            this.fAlpha
                                        )
                                    } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 1) {
                                        this.cloud_light_a_02!!.shortdraw(
                                            gl10,
                                            this.fAlpha,
                                            this.fAlpha
                                        )
                                    } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 2) {
                                        this.cloud_light_a_03!!.shortdraw(
                                            gl10,
                                            this.fAlpha,
                                            this.fAlpha
                                        )
                                    }
                                } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 0) {
                                    this.cloud_light_b_01!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                                } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 1) {
                                    this.cloud_light_b_02!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                                } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.cloud_light_pos!![i5] == 2) {
                                    this.cloud_light_b_03!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                                }
                            }
                        }
                    }
                }
            }
            if (this.windmillSet != null) {
                for (i6 in this.windmillSet!!.indices) {
                    if (this.windmillSet!![i6] != null && this.windmillSet!![i6]!!.mnDistance == 2 && this.windmillSet!![i6]!!.isCreated) {
                        this.windmillSet!![i6]!!.setFanAngle(this.mWindmillAngle + this.windmill_wing_offset[i6])
                        this.windmillSet!![i6]!!.drawWindMill(gl10, true)
                    }
                }
            }
            gl10.glLoadIdentity()
            val land2X = if (this.mIsPortrait)
                (-1.5f) + ((1.5f - (groundOffset * 0.5f)) * 5.0f)
            else
                (-1.2f) + ((1.5f - (groundOffset * 0.68f)) * 5.0f)
            val land2ScaleX =
                if (this.mIsPortrait) 3.6f * this.mfLandscape else 4.45f * this.mfLandscape * landscapeSceneFill
            val land2Y = if (this.mIsPortrait) -5.2f else -5.02f
            val land2ScaleY = if (this.mIsPortrait) 1.8f else 2.05f
            gl10.glTranslatef(land2X, land2Y, -24.0f)
            gl10.glScalef(land2ScaleX, land2ScaleY, 0.0f)
            this.land_02!!.shortdraw(gl10, 1.0f, 1.0f)
            if (this.windmillSet != null) {
                for (i7 in this.windmillSet!!.indices) {
                    if (this.windmillSet!![i7] != null && this.windmillSet!![i7]!!.mnDistance == 1 && this.windmillSet!![i7]!!.isCreated) {
                        this.windmillSet!![i7]!!.setFanAngle(this.mWindmillAngle + this.windmill_wing_offset[i7])
                        this.windmillSet!![i7]!!.drawWindMill(gl10, true)
                    }
                }
            }
            gl10.glLoadIdentity()
            val land1X = if (this.mIsPortrait)
                (1.5f - (groundOffset * 1.2f)) * 5.0f
            else
                (1.5f - (groundOffset * 0.92f)) * 5.0f
            val land1ScaleX =
                if (this.mIsPortrait) 3.5f * this.mfLandscape else 4.55f * this.mfLandscape * landscapeSceneFill
            val land1Y = if (this.mIsPortrait) -6.4f else -6.14f
            val land1ScaleY = if (this.mIsPortrait) 3.2f else 3.55f
            gl10.glTranslatef(land1X, land1Y, -23.0f)
            gl10.glScalef(land1ScaleX, land1ScaleY, 0.0f)
            this.land_01!!.shortdraw(gl10, 1.0f, 1.0f)
            if (this.windmillSet != null) {
                for (i8 in this.windmillSet!!.indices) {
                    if (this.windmillSet!![i8] != null && this.windmillSet!![i8]!!.mnDistance == 0 && this.windmillSet!![i8]!!.isCreated) {
                        this.windmillSet!![i8]!!.setFanAngle(this.mWindmillAngle + this.windmill_wing_offset[i8])
                        this.windmillSet!![i8]!!.drawWindMill(gl10, true)
                    }
                }
            }
            val lawnDepth = 23.0f
            val lawnY = if (this.mIsPortrait) -4.3f else -4.22f
            gl10.glLoadIdentity()
            var lawnX = (1.5f - (groundOffset * 1.2f)) * 5.0f
            if (!this.mIsPortrait) {
                lawnX += -0.12f * landscapeGroundFill
            }
            val lawnScaleX: Float
            val lawnScaleY: Float
            if (this.mIsPortrait) {
                lawnScaleX = 3.5f * this.mfLandscape
                lawnScaleY = 1.0f
            } else {
                // Keep lawn coverage stable across very wide landscape aspect ratios.
                val lawnCoverage = clamp(0.74f, (0.74f * landscapeGroundFill) + 0.05f, 0.95f)
                val lawnHeightCoverage =
                    clamp(0.105f, 0.105f + ((landscapeGroundFill - 1.0f) * 0.02f), 0.14f)
                lawnScaleX = getRectOneToFourScaleForScreenWidth(lawnDepth, lawnCoverage)
                lawnScaleY = getRectOneToFourScaleForScreenHeight(lawnDepth, lawnHeightCoverage)
            }
            gl10.glTranslatef(lawnX, lawnY, -lawnDepth)
            gl10.glScalef(lawnScaleX, lawnScaleY, 0.0f)
            gl10.glDisable(2929)
            this.lawn_01!!.shortdraw(gl10, 1.0f, 1.0f)
            gl10.glEnable(2929)
            gl10.glDisable(2929)
            if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal && !this.loadedImagesetDayNight && this.bClearOn) {
                val f9 = this.sunlight_cnt
                this.sunlight_cnt = 1.0f + f9
                if (f9 == 200.0f) {
                    this.sunlight_cnt = 0.0f
                    this.bClearOn = false
                } else if (this.sunlight_cnt < 200.0f) {
                    gl10.glLoadIdentity()
                    gl10.glTranslatef(0.6f + 3.0f + (skyShift * 0.15f), 6.0f - 1.75f, -20.5f)
                    if (this.sunlight_cnt > 0.0f && this.sunlight_cnt < 160.0f) {
                        fSqrt = Math.sqrt((this.sunlight_cnt / 160.0f).toDouble()).toFloat()
                    } else if (this.sunlight_cnt < 200.0f) {
                        fSqrt = Math.sqrt((1.0f - ((this.sunlight_cnt - 160.0f) / 40.0f)).toDouble()).toFloat()
                    } else {
                        fSqrt = 0.0f
                    }
                    val f10 = 2.0f + (((this.sunlight_cnt.toDouble()) * 0.004).toFloat())
                    gl10.glScalef(this.mfLandscape * f10 * 0.6f, f10 * 0.6f, 0.0f)
                    gl10.glRotatef((this.sunlight_cnt * (-0.15f)) - 70.0f, 0.0f, 0.0f, 1.0f)
                    this.sun4!!.shortdraw(gl10, fSqrt, fSqrt)
                }
            }
            if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                gl10.glLoadIdentity()
                gl10.glTranslatef(0.0f, 0.0f, -20.0f)
                if (this.mIsPortrait) {
                    gl10.glScalef(0.7f, 1.05f, 0.0f)
                } else {
                    gl10.glRotatef(90.0f, 0.0f, 0.0f, 1.0f)
                    val fogScaleX = 1.2f * landscapeOverlayFill
                    val fogScaleY = 1.9f * clamp(1.0f, landscapeOverlayFill * 0.96f, 1.35f)
                    gl10.glScalef(fogScaleX, fogScaleY, 0.0f)
                }
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isNightEffective) {
                    this.fog!!.shortdraw(gl10, 0.9f, 0.7f)
                } else {
                    this.fog!!.shortdraw(gl10, 0.4f, 0.4f)
                }
            }
            if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal || this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal || this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                gl10.glLoadIdentity()
                gl10.glTranslatef(0.0f, 0.0f, -20.5f)
                val rainScaleX: Float
                if (this.mIsPortrait) {
                    rainScaleX = 0.75f * this.mfLandscape
                } else {
                    // Fill wider landscapes so c_rain reaches both edges.
                    rainScaleX = 1.3f * this.mfLandscape * landscapeOverlayFill
                }
                gl10.glScalef(rainScaleX, 1.9f, 0.0f)
                when (this.mFrameCnt % 3) {
                    0 -> this.rain1!!.shortdraw(gl10, 1.0f, 1.0f)
                    1 -> this.rain2!!.shortdraw(gl10, 1.0f, 1.0f)
                    2 -> this.rain3!!.shortdraw(gl10, 1.0f, 1.0f)
                }
                val showThunderRaindropsOverlay =
                    this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal
                            && !com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isHideThunderRaindropsLegacyEnabled
                if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal || this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal || showThunderRaindropsOverlay) {
                    gl10.glLoadIdentity()
                    gl10.glTranslatef(0.0f, 0.0f, -20.0f)
                    if (this.mIsPortrait) {
                        gl10.glScalef(0.7f, 1.1f, 0.0f)
                    } else {
                        gl10.glRotatef(90.0f, 0.0f, 0.0f, 1.0f)
                        val waterdropScaleX = 1.2f * landscapeOverlayFill
                        val waterdropScaleY =
                            1.9f * clamp(1.0f, landscapeOverlayFill * 0.96f, 1.35f)
                        gl10.glScalef(waterdropScaleX, waterdropScaleY, 0.0f)
                    }
                    this.waterdrop!!.shortdraw(gl10, 1.0f, 1.0f)
                    if (this.bRainOn || this.mFrameCnt % 400 == 0) {
                        for (i9 in 1..7) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start!![i9] =
                                ((Math.random() * 300.0) + 50.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start!![i9] =
                                ((Math.random() * 300.0) + 50.0).toInt()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_x!![i9] =
                                ((Math.random() * 8.0) - 4.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_y!![i9] =
                                (Math.random() * 3.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_x!![i9] =
                                ((Math.random() * 8.0) - 4.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_y!![i9] =
                                (Math.random() * 3.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale!![i9] =
                                ((Math.random() * 0.5) + 0.5).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale!![i9] =
                                ((Math.random() * 0.5) + 0.5).toFloat()
                        }
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start!![2] =
                            this.mFrameCnt + 20
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start!![2] =
                            this.mFrameCnt + 55
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_x!![2] =
                            -1.5f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_y!![2] =
                            -1.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_x!![2] =
                            3.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_y!![2] =
                            -0.5f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale!![2] =
                            1.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale!![2] =
                            1.0f
                        this.bRainOn = false
                    }
                    for (i10 in 0..7) {
                        if (this.mFrameCnt % 400 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start!![i10] && this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start!![i10] + 50) {
                            gl10.glLoadIdentity()
                            gl10.glTranslatef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_x!![i10] * this.mfLandscape,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_y!![i10],
                                -19.5f
                            )
                            gl10.glScalef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale!![i10] * 0.6f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_scale!![i10] * 2.4f,
                                0.0f
                            )
                            this.raindrop1!![((this.mFrameCnt % 400) - com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop1_start!![i10]) / 2]!!.shortdraw(
                                gl10,
                                1.0f,
                                1.0f
                            )
                        }
                        if (this.mFrameCnt % 400 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start!![i10] && this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start!![i10] + 50) {
                            gl10.glLoadIdentity()
                            gl10.glTranslatef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_x!![i10] * this.mfLandscape,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_y!![i10],
                                -19.5f
                            )
                            gl10.glScalef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale!![i10] * 0.6f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_scale!![i10] * 4.8f,
                                0.0f
                            )
                            this.raindrop2!![((this.mFrameCnt % 400) - com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.raindrop2_start!![i10]) / 2]!!.shortdraw(
                                gl10,
                                1.0f,
                                1.0f
                            )
                        }
                    }
                }
            }
            if (humidityWaterdropOverlay
                && this.loadedImageset != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal && this.loadedImageset != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal && this.loadedImageset != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal
            ) {
                gl10.glLoadIdentity()
                gl10.glTranslatef(0.0f, 0.0f, -20.0f)
                if (this.mIsPortrait) {
                    gl10.glScalef(0.7f, 1.1f, 0.0f)
                } else {
                    gl10.glRotatef(90.0f, 0.0f, 0.0f, 1.0f)
                    val waterdropScaleX = 1.2f * landscapeOverlayFill
                    val waterdropScaleY = 1.9f * clamp(1.0f, landscapeOverlayFill * 0.96f, 1.35f)
                    gl10.glScalef(waterdropScaleX, waterdropScaleY, 0.0f)
                }
                this.waterdrop!!.shortdraw(gl10, 1.0f, 1.0f)
            }
            if (loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal || freezingFogOverlay || belowFreezingOverlay) {
                gl10.glLoadIdentity()
                val frostDepth = 20.3f
                gl10.glTranslatef(0.0f, 0.0f, -frostDepth)
                if (this.mIsPortrait) {
                    val frostScaleX = getSquareScaleForScreenWidth(frostDepth, 1.18f)
                    val frostScaleY = getSquareScaleForScreenHeight(frostDepth, 1.0f)
                    gl10.glScalef(frostScaleX, frostScaleY, 0.0f)
                } else {
                    gl10.glRotatef(90.0f, 0.0f, 0.0f, 1.0f)
                    val frostScaleX = getSquareScaleForScreenHeight(frostDepth, 1.05f)
                    val frostScaleY = getSquareScaleForScreenWidth(frostDepth, 1.02f)
                    gl10.glScalef(frostScaleX, frostScaleY, 0.0f)
                }
                this.frost!!.shortdraw(gl10, 1.0f, 1.0f)
            }
            if (loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal || loadedWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                if (this.bSnowOn) {
                    for (i11 in 0..<this.n_snow1) {
                        if (this.mIsPortrait) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1!![i11] =
                                (Math.random() * 24.0).toFloat() - 12.0f
                        } else {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1!![i11] =
                                (Math.random() * 40.0).toFloat() - 20.0f
                        }
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1!![i11] =
                            (Math.random() * 16.0).toFloat() - 8.0f
                        if ((Math.random() * 100.0).toInt() % 2 == 0) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i11] =
                                1.0f
                        } else {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i11] =
                                0.5f
                        }
                    }
                    for (i12 in 0..<this.n_snow2) {
                        if (this.mIsPortrait) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i12] =
                                (Math.random() * 24.0).toFloat() - 12.0f
                        } else {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i12] =
                                (Math.random() * 40.0).toFloat() - 20.0f
                        }
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2!![i12] =
                            (Math.random() * 16.0).toFloat() - 8.0f
                        val iRandom = (Math.random() * 100.0).toInt() % 3
                        if (iRandom == 0) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i12] =
                                1.0f
                        } else if (iRandom == 1) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i12] =
                                0.7f
                        } else if (iRandom == 2) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i12] =
                                0.5f
                        }
                    }
                    for (i13 in 0..<this.n_snow3) {
                        if (this.mIsPortrait) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3!![i13] =
                                (Math.random() * 24.0).toFloat() - 12.0f
                        } else {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3!![i13] =
                                (Math.random() * 40.0).toFloat() - 20.0f
                        }
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3!![i13] =
                            (Math.random() * 16.0).toFloat() - 8.0f
                        val iRandom2 = (Math.random() * 100.0).toInt() % 4
                        if (iRandom2 == 0) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i13] =
                                1.0f
                        } else if (iRandom2 == 1) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i13] =
                                0.5f
                        } else if (iRandom2 == 2) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i13] =
                                0.3f
                        } else if (iRandom2 == 3) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i13] =
                                0.2f
                        }
                    }
                    this.bSnowOn = false
                } else {
                    for (i14 in 0..<this.n_snow1) {
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1!![i14] < -8.0f) {
                            if (this.mIsPortrait) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1!![i14] =
                                    (Math.random() * 24.0).toFloat() - 12.0f
                            } else {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1!![i14] =
                                    (Math.random() * 40.0).toFloat() - 20.0f
                            }
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1!![i14] =
                                9.0f
                            if ((Math.random() * 100.0).toInt() % 2 == 0) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] =
                                    1.0f
                            } else {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] =
                                    0.5f
                            }
                        } else {
                            val fArr3: FloatArray? =
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1
                            fArr3!![i14] = fArr3[i14] - 0.04f
                        }
                        gl10.glLoadIdentity()
                        gl10.glTranslatef(
                            (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow1!![i14] + skyShift) - 1.0f,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow1!![i14],
                            -20.0f
                        )
                        gl10.glRotatef(this.mFrameCnt * 0.225f, 0.0f, 0.0f, 1.0f)
                        if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbManySnows) {
                            gl10.glScalef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] * 0.1f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] * 0.1f,
                                0.0f
                            )
                        } else {
                            gl10.glScalef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] * 0.08f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] * 0.08f,
                                0.0f
                            )
                        }
                        if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                            this.snow1!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] / 2.0f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14] / 2.0f
                            )
                        } else {
                            this.snow1!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14],
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow1!![i14]
                            )
                        }
                    }
                    for (i15 in 0..<this.n_snow2) {
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2!![i15] < -8.0f) {
                            if (this.mIsPortrait) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i15] =
                                    (Math.random() * 24.0).toFloat() - 12.0f
                            } else {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i15] =
                                    (Math.random() * 40.0).toFloat() - 20.0f
                            }
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2!![i15] =
                                9.0f
                            val iRandom3 = (Math.random() * 100.0).toInt() % 3
                            if (iRandom3 == 0) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] =
                                    1.0f
                            } else if (iRandom3 == 1) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] =
                                    0.7f
                            } else if (iRandom3 == 2) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] =
                                    0.5f
                            }
                        } else {
                            val fArr4: FloatArray? =
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2
                            fArr4!![i15] = fArr4[i15] - 0.02f
                        }
                        gl10.glLoadIdentity()
                        gl10.glTranslatef(
                            (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i15] + skyShift) - 1.0f,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2!![i15],
                            -20.0f
                        )
                        gl10.glScalef(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] * 0.02f,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] * 0.02f,
                            0.0f
                        )
                        if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                            this.snow2!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] / 2.0f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15] / 2.0f
                            )
                        } else {
                            this.snow2!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15],
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow2!![i15]
                            )
                        }
                        if ((i15 and 273) == 1 && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbManySnows) {
                            gl10.glLoadIdentity()
                            if (this.loadedImageset != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                                gl10.glTranslatef(
                                    (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow2!![i15] + skyShift) - 1.0f,
                                    com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow2!![i15] + 1.0f,
                                    -21.0f
                                )
                                gl10.glScalef(0.35f, 0.35f, 0.0f)
                                this.snow4!!.shortdraw(gl10, 0.8f, 0.8f)
                            }
                        }
                    }
                    for (i16 in 0..<this.n_snow3) {
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3!![i16] < -8.0f) {
                            if (this.mIsPortrait) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3!![i16] =
                                    (Math.random() * 24.0).toFloat() - 12.0f
                            } else {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3!![i16] =
                                    (Math.random() * 40.0).toFloat() - 20.0f
                            }
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3!![i16] =
                                9.0f
                            val iRandom4 = (Math.random() * 100.0).toInt() % 4
                            if (iRandom4 == 0) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] =
                                    1.0f
                            } else if (iRandom4 == 1) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] =
                                    0.5f
                            } else if (iRandom4 == 2) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] =
                                    0.3f
                            } else if (iRandom4 == 3) {
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] =
                                    0.2f
                            }
                        } else {
                            val fArr5: FloatArray? =
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3
                            fArr5!![i16] = fArr5[i16] - 0.01f
                        }
                        gl10.glLoadIdentity()
                        gl10.glTranslatef(
                            (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.x_snow3!![i16] + skyShift) - 1.0f,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.y_snow3!![i16],
                            -20.0f
                        )
                        gl10.glScalef(
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] * 0.01f,
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] * 0.01f,
                            0.0f
                        )
                        if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                            this.snow3!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] / 2.0f,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16] / 2.0f
                            )
                        } else {
                            this.snow3!!.shortdraw(
                                gl10,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16],
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.scale_snow3!![i16]
                            )
                        }
                    }
                }
            }
            if (this.loadedImageset == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal) {
                if (this.bThunderOn || this.mFrameCnt % 400 == 0) {
                    for (i17 in 0..39) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i17] =
                            (Math.random() * 400.0).toInt()
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![i17] =
                            (Math.random() * 15.0).toInt()
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![i17] =
                            (Math.random() * 100.0).toInt() % 3
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i17] =
                            ((Math.random() * 0.5) + 0.5).toFloat()
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i17] > 0.75) {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![i17] =
                                ((Math.random() * 16.0) - 8.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![i17] =
                                ((Math.random() * 3.0) + 8.0).toFloat()
                        } else {
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![i17] =
                                ((Math.random() * 16.0) - 8.0).toFloat()
                            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![i17] =
                                ((Math.random() * 3.0) + 3.0).toFloat()
                        }
                    }
                    if (this.bThunderOn) {
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![0] =
                            (this.mFrameCnt % 400) + 3
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![0] =
                            10
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![0] =
                            2
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![0] =
                            1.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![0] =
                            2.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![0] =
                            8.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![1] =
                            (this.mFrameCnt % 400) + 7
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![1] =
                            15
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![1] =
                            1
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![1] =
                            0.7f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![1] =
                            -3.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![1] =
                            8.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![2] =
                            (this.mFrameCnt % 400) + 13
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![2] =
                            12
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![2] =
                            2
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![2] =
                            1.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![2] =
                            3.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![2] =
                            9.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![3] =
                            (this.mFrameCnt % 400) + 16
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![3] =
                            8
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![3] =
                            2
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![3] =
                            1.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![3] =
                            10.0f
                        com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![3] =
                            10.0f
                    }
                    this.bThunderOn = false
                } else {
                    for (i18 in 0..39) {
                        if (this.mFrameCnt % 400 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18] && this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18] + 8) {
                            if (this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18] + 4) {
                                this.fAlpha = 0.8f + (Math.random().toFloat() * 0.2f)
                            } else {
                                this.fAlpha = 0.2f + (Math.random().toFloat() * 0.2f)
                            }
                            gl10.glLoadIdentity()
                            gl10.glTranslatef(7.0f, 0.0f, -19.0f)
                            if (this.mIsPortrait) {
                                gl10.glScalef(1.7f, 1.2f, 0.0f)
                            } else {
                                gl10.glRotatef(90.0f, 0.0f, 0.0f, 1.0f)
                                gl10.glScalef(1.5f, 2.7f, 0.0f)
                            }
                            this.sky_flash!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                        }
                        if (this.mFrameCnt % 400 > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18] && this.mFrameCnt % 400 < com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18] + com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![i18]) {
                            if (this.mFrameCnt % 400 < (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_start!![i18].toDouble()) + ((com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_duration!![i18].toDouble()) * 0.5)) {
                                if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i18] > 0.75) {
                                    this.fAlpha = 0.8f + (Math.random().toFloat() * 0.2f)
                                } else {
                                    this.fAlpha = 0.5f + (Math.random().toFloat() * 0.2f)
                                }
                            } else if (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i18] > 0.75) {
                                this.fAlpha = 0.3f + (Math.random().toFloat() * 0.2f)
                            } else {
                                this.fAlpha = 0.1f + (Math.random().toFloat() * 0.2f)
                            }
                            gl10.glLoadIdentity()
                            gl10.glTranslatef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_x!![i18] + skyShift,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_y!![i18],
                                -26.0f
                            )
                            gl10.glScalef(
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i18] * this.mfLandscape,
                                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_scale!![i18],
                                0.0f
                            )
                            when (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.thunder_num!![i18]) {
                                0 -> this.lightning1!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                                1 -> this.lightning2!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                                2 -> this.lightning3!!.shortdraw(gl10, this.fAlpha, this.fAlpha)
                            }
                        }
                    }
                }
            }
            val overlayDepth = 14.0f
            val marginXWorld = worldXFromPx(dpToPx(16.0f), overlayDepth)
            val marginYWorld = worldYFromPx(dpToPx(28.0f), overlayDepth)
            val gapWorld = worldYFromPx(dpToPx(8.0f), overlayDepth)
            val overlayBlockDownWorld = worldYFromPx(dpToPx(10.0f), overlayDepth)
            val topEdgeY = getWorldHalfHeight(overlayDepth) - marginYWorld - overlayBlockDownWorld
            val leftEdgeX = (-getWorldHalfWidth(overlayDepth)) + marginXWorld
            val logoScale = 0.5f
            val logoHalfWidth = 4.0f * logoScale
            val logoHalfHeight = 1.0f * logoScale
            val logoHeight = logoHalfHeight * 2.0f
            // logo/logo_legacy textures have transparent left padding (~25px of 512px).
            val watermarkTextInsetWorld = (8.0f * logoScale) * (25.0f / 512.0f)
            var cityScaleX = 0.34f
            // RectOneToSixteen has a 16:1 mesh ratio; city bitmap is 1024x80 (12.8:1).
            // Keep scale ratio matched so glyphs are not stretched.
            var cityScaleY = cityScaleX * 1.25f
            var cityHalfWidth = 16.0f * cityScaleX
            var cityHalfHeight = 1.0f * cityScaleY
            var blockHalfWidth: Float = Math.max(logoHalfWidth, cityHalfWidth)
            if (leftEdgeX + (blockHalfWidth * 2.0f) > getWorldHalfWidth(overlayDepth)) {
                cityScaleX = 0.30f
                cityScaleY = cityScaleX * 1.25f
                cityHalfWidth = 16.0f * cityScaleX
                cityHalfHeight = 1.0f * cityScaleY
                blockHalfWidth = Math.max(logoHalfWidth, cityHalfWidth)
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyLogoVisible && com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bEnableLogo) {
                val logoCenterX = leftEdgeX + logoHalfWidth
                val logoCenterY = topEdgeY - logoHalfHeight
                gl10.glLoadIdentity()
                gl10.glTranslatef(logoCenterX, logoCenterY, -overlayDepth)
                gl10.glScalef(logoScale, logoScale, 0.0f)
                this.logo!!.shortdraw(gl10, 1.0f, 1.0f)
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isCityNameVisible) {
                val cityTopY =
                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyLogoVisible)
                        topEdgeY - logoHeight - (gapWorld * 0.35f)
                    else
                        topEdgeY
                // Align city text start under watermark text start across orientations.
                val cityCenterX = leftEdgeX + watermarkTextInsetWorld + cityHalfWidth
                val cityCenterY: Float
                if (!this.mIsPortrait) { // landscape
                    cityCenterY = cityTopY + (gapWorld / 2.0f)
                } else {
                    cityCenterY = cityTopY + gapWorld
                }
                gl10.glLoadIdentity()
                gl10.glTranslatef(cityCenterX, cityCenterY, -overlayDepth)
                gl10.glScalef(cityScaleX, cityScaleY, 0.0f)
                this.cityname!!.shortdraw(gl10, 0.25f, 0.25f)
            }
            gl10.glDisableClientState(32884)
            gl10.glDisableClientState(32888)
            gl10.glDisable(2884)
            gl10.glColor4f(1.0f, 1.0f, 1.0f, 1.0f)
            this.sceneDrawStatus = true
        }

        fun release() {
            if (this.isImagesetInitialized) {
                deleteImages(this.mGl)
            }
            if (this.isMemInitialized) {
                deleteMem()
            }
            Runtime.getRuntime().totalMemory()
            val jFreeMemory: Long =
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        }

        override fun onSurfaceChanged(gl10: GL10?, i: Int, i2: Int) {
            if (gl10 == null) {
                return
            }
            if (i <= 0 || i2 <= 0) {
                Log.w("WindyWeather", "Ignoring invalid surface size: " + i + "x" + i2)
                this.mIsPortrait = true
                this.mfLandscape = 1.0f
                this.m1280x720 = false
                this.mSurfaceWidth = 1
                this.mSurfaceHeight = 1
                this.mSurfaceAspect = 1.0f
                return
            }
            val f: Float
            val aspect = (i.toFloat()) / (i2.toFloat())
            this.mSurfaceWidth = i
            this.mSurfaceHeight = i2
            this.mSurfaceAspect = aspect
            if (this.isPreview) {
                this.mOffset = 1.0f
            }
            this.preOrientation =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mOrientation
            if (i < i2) {
                this.mIsPortrait = true
                this.mfLandscape = 1.0f
                f = (i2.toFloat()) / (i.toFloat())
            } else {
                this.mIsPortrait = false
                this.mfLandscape = clamp(1.28f, 1.72f * (1.7777778f / aspect), 1.65f)
                f = aspect
            }
            if (Math.abs(f - 1.7777778f) < 0.05f) {
                this.m1280x720 = true
            } else {
                this.m1280x720 = false
            }
            Log.d(
                "WindyWeather",
                    "CHANGED mbSurfaceCreated: " + com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.surfaceCreated + " interval: " + com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mfInterval
            )
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal) {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D4_FOG.ordinal) {
                    if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal) {
                        if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal) {
                            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D7_FLURRIES_SNOW.ordinal) {
                                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather != com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D8_ICE_COLD.ordinal && com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather == com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal) {
                                    this.bSnowOn = true
                                    this.bRainOn = true
                                }
                            } else {
                                this.bSnowOn = true
                            }
                        } else {
                            this.bThunderOn = true
                        }
                    } else {
                        this.bRainOn = true
                    }
                }
            } else {
                this.bClearOn = true
            }
            if (gl10 !is GL11Ext && !this.mLoggedMissingGl11Ext) {
                this.mLoggedMissingGl11Ext = true
                Log.w("WindyWeather", "GL11Ext not supported; running with fallback path")
            }
            gl10.glViewport(0, 0, i, i2)
            gl10.glMatrixMode(5889)
            gl10.glLoadIdentity()
            GLU.gluPerspective(gl10, 45.0f, aspect, 0.1f, 40.0f)
            gl10.glMatrixMode(5888)
            gl10.glLoadIdentity()
            this.mOnSurfaceChanged = true
        }

        private fun resolveWindmillDegreesPerFrame(): Float {
            var windSpeedKmh = 0.0f
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null) {
                windSpeedKmh =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.currentWindSpeedKmh
            }
            // Keep motion alive in calm weather, then scale up with real wind speed.
            val normalizedWind = clamp(0.0f, windSpeedKmh / 40.0f, 1.0f)
            return 0.45f + (normalizedWind * 1.75f)
        }

        private fun resolveAnimationFrameStep(frameStartNs: Long): Float {
            val frameRateDependent =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null
                        || com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isFrameRateDependentAnimationEnabled
            if (frameRateDependent) {
                this.mLastAnimationStepNs = frameStartNs
                return 1.0f
            }
            if (this.mLastAnimationStepNs <= 0L) {
                this.mLastAnimationStepNs = frameStartNs
                return 1.0f
            }
            var deltaNs = frameStartNs - this.mLastAnimationStepNs
            this.mLastAnimationStepNs = frameStartNs
            if (deltaNs < 0L) {
                deltaNs = 0L
            } else if (deltaNs > com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.MAX_TIME_BASED_ANIMATION_STEP_NS) {
                deltaNs =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.MAX_TIME_BASED_ANIMATION_STEP_NS
            }
            return ((deltaNs.toFloat()) / 1.0E9f) * com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.TIME_BASED_ANIMATION_REFERENCE_FPS
        }

        private fun advanceFrameCounter(frameStep: Float) {
            var frameStep = frameStep
            if (frameStep < 0.0f) {
                frameStep = 0.0f
            }
            this.mFrameCntAccumulator += frameStep
            while (this.mFrameCntAccumulator >= (com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.FRAME_COUNTER_WRAP.toFloat())) {
                this.mFrameCntAccumulator -= com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.FRAME_COUNTER_WRAP.toFloat()
            }
            this.mFrameCnt = this.mFrameCntAccumulator.toInt()
        }

        private fun clamp(min: Float, value: Float, max: Float): Float {
            return Math.max(min, Math.min(value, max))
        }

        private fun getWorldHalfHeight(zAbs: Float): Float {
            return Math.tan(Math.toRadians(22.5)).toFloat() * zAbs
        }

        private fun getWorldHalfWidth(zAbs: Float): Float {
            return getWorldHalfHeight(zAbs) * this.mSurfaceAspect
        }

        private fun getSquareScaleForScreenWidth(zAbs: Float, coverageMultiplier: Float): Float {
            return ((getWorldHalfWidth(zAbs) * 2.0f) / 16.0f) * coverageMultiplier
        }

        private fun getSquareScaleForScreenHeight(zAbs: Float, coverageMultiplier: Float): Float {
            return ((getWorldHalfHeight(zAbs) * 2.0f) / 16.0f) * coverageMultiplier
        }

        private fun getRectOneToFourScaleForScreenWidth(zAbs: Float, coverageRatio: Float): Float {
            return ((getWorldHalfWidth(zAbs) * 2.0f) * coverageRatio) / 8.0f
        }

        private fun getRectOneToFourScaleForScreenHeight(zAbs: Float, coverageRatio: Float): Float {
            return ((getWorldHalfHeight(zAbs) * 2.0f) * coverageRatio) / 2.0f
        }

        private fun worldXFromPx(px: Float, zAbs: Float): Float {
            return (px / (this.mSurfaceWidth.toFloat())) * (getWorldHalfWidth(zAbs) * 2.0f)
        }

        private fun worldYFromPx(px: Float, zAbs: Float): Float {
            return (px / (this.mSurfaceHeight.toFloat())) * (getWorldHalfHeight(zAbs) * 2.0f)
        }

        private fun dpToPx(dp: Float): Float {
            return dp * this.mContext.resources.displayMetrics.density
        }

        private fun recordFrameTiming(frameDurationNs: Long) {
            this.mFrameTimingAccumNs += frameDurationNs
            if (frameDurationNs > this.mFrameTimingMaxNs) {
                this.mFrameTimingMaxNs = frameDurationNs
            }
            this.mFrameTimingSamples++
            if (this.mFrameTimingSamples >= com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.FRAME_TIMING_LOG_SAMPLE_WINDOW) {
                val avgFrameNs = this.mFrameTimingAccumNs / this.mFrameTimingSamples
                Log.d(
                    "WindyWeatherPerf",
                    ("onDrawFrame avgMs=" + ((avgFrameNs.toFloat()) / 1000000.0f)
                            + " maxMs=" + ((this.mFrameTimingMaxNs.toFloat()) / 1000000.0f)
                            + " samples=" + this.mFrameTimingSamples)
                )
                this.mFrameTimingAccumNs = 0L
                this.mFrameTimingMaxNs = 0L
                this.mFrameTimingSamples = 0
            }
        }

        private fun logOverlayStateIfChanged() {
            val mode: Int =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.dayNightMode
            val showCity: Boolean =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isCityNameVisible
            val showLogo: Boolean =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.isLegacyLogoVisible
            if (this.mLastLoggedDayNightMode != mode || this.mLastLoggedShowCity != showCity || this.mLastLoggedShowLogo != showLogo) {
                this.mLastLoggedDayNightMode = mode
                this.mLastLoggedShowCity = showCity
                this.mLastLoggedShowLogo = showLogo
                Log.d(
                    "WindyWeather",
                    "Overlay state dayNightMode=" + mode + " showCity=" + showCity + " showLogo=" + showLogo
                )
            }
        }

        override val targetFrameRate: Int
            get() {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null) {
                    return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_DEFAULT
                }
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.configuredTargetFrameRate
            }

        override val powerSaveTargetFrameRate: Int
            get() {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService == null) {
                    return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_POWER_SAVE_DEFAULT
                }
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.configuredPowerSaveTargetFrameRate
            }

        override val isPowerSaveModeEnabled: Boolean
            get() {
                val powerManager: PowerManager? =
                    this.mContext.getSystemService(Context.POWER_SERVICE) as PowerManager?
                return powerManager != null && powerManager.isPowerSaveMode()
            }

        override var sceneDrawStatus: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bSceneReady
            set(bFlag) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bSceneReady =
                    bFlag
            }

        fun setEnginePause(bFlag: Boolean) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bIsEnginePaused =
                bFlag
        }

        override fun onSurfaceCreated(gl10: GL10?, eGLConfig: EGLConfig?) {
            if (gl10 == null) {
                return
            }
            this.sceneDrawStatus = false
            val defaultDisplay: Display =
                (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager).defaultDisplay
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mOrientation =
                defaultDisplay.rotation
            com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bGLES11 =
                gl10 is GL11
            gl10.glEnable(3553)
            gl10.glTexParameterf(3553, 10241, 9728.0f)
            gl10.glTexParameterf(3553, 10240, 9729.0f)
            gl10.glTexParameterf(3553, 10242, 33071.0f)
            gl10.glTexParameterf(3553, 10243, 33071.0f)
            gl10.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            gl10.glShadeModel(7425)
            gl10.glClearDepthf(30.0f)
            gl10.glEnable(3042)
            gl10.glDepthFunc(515)
            gl10.glHint(3152, 4354)
            gl10.glEnable(3155)
            try {
                val maxTexture = IntArray(1)
                gl10.glGetIntegerv(3379, maxTexture, 0)
                if (maxTexture[0] > 0) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sMaxTextureSize =
                        maxTexture[0]
                    Log.d(
                        "WindyWeather",
                        "GL max texture size: " + com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sMaxTextureSize
                    )
                }
            } catch (e: Exception) {
                Log.w("WindyWeather", "Unable to query GL max texture size", e)
            }
            this.mGl = gl10
            if (this.isPreview) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
                val unused: Int =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mnCurWeather
            }
            if (!this.isMemInitialized) {
                initMem()
            }
            if (!this.isImagesetInitialized) {
                generateImages(com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mContext)
            }
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.setImageSetChange(
                true
            )
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.surfaceCreated = true
        }

        fun onTouchEvent(event: MotionEvent?) {
        }

        var isPreview: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbIsPreview
            set(bPreview) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbIsPreview =
                    bPreview
            }

        companion object {
            private const val bMemoryInitialized = false
            private var bImagesetInitialized = false
            private var bSceneReady = false
            private var bIsEnginePaused = false
            private var bEnableLogo = true
            private val raindrop_id_1 = intArrayOf(
                R.drawable.waterdrop_a_0,
                R.drawable.waterdrop_a_1,
                R.drawable.waterdrop_a_2,
                R.drawable.waterdrop_a_3,
                R.drawable.waterdrop_a_4,
                R.drawable.waterdrop_a_5,
                R.drawable.waterdrop_a_6,
                R.drawable.waterdrop_a_7,
                R.drawable.waterdrop_a_8,
                R.drawable.waterdrop_a_9,
                R.drawable.waterdrop_a_10,
                R.drawable.waterdrop_a_11,
                R.drawable.waterdrop_a_12,
                R.drawable.waterdrop_a_13,
                R.drawable.waterdrop_a_14,
                R.drawable.waterdrop_a_15,
                R.drawable.waterdrop_a_16,
                R.drawable.waterdrop_a_17,
                R.drawable.waterdrop_a_18,
                R.drawable.waterdrop_a_19,
                R.drawable.waterdrop_a_20,
                R.drawable.waterdrop_a_21,
                R.drawable.waterdrop_a_22,
                R.drawable.waterdrop_a_23,
                R.drawable.waterdrop_a_24
            )
            private val raindrop_id_2 = intArrayOf(
                R.drawable.waterdrop_b_0,
                R.drawable.waterdrop_b_1,
                R.drawable.waterdrop_b_2,
                R.drawable.waterdrop_b_3,
                R.drawable.waterdrop_b_4,
                R.drawable.waterdrop_b_5,
                R.drawable.waterdrop_b_6,
                R.drawable.waterdrop_b_7,
                R.drawable.waterdrop_b_8,
                R.drawable.waterdrop_b_9,
                R.drawable.waterdrop_b_10,
                R.drawable.waterdrop_b_11,
                R.drawable.waterdrop_b_12,
                R.drawable.waterdrop_b_13,
                R.drawable.waterdrop_b_14,
                R.drawable.waterdrop_b_15,
                R.drawable.waterdrop_b_16,
                R.drawable.waterdrop_b_17,
                R.drawable.waterdrop_b_18,
                R.drawable.waterdrop_b_19,
                R.drawable.waterdrop_b_20,
                R.drawable.waterdrop_b_21,
                R.drawable.waterdrop_b_22,
                R.drawable.waterdrop_b_23,
                R.drawable.waterdrop_b_24
            )
            var b_star_draw: BooleanArray? = null
            var alpha_star: FloatArray? = null
            var start_star: IntArray? = null
            var dur_star: IntArray? = null
            var x_snow1: FloatArray? = null
            var y_snow1: FloatArray? = null
            var scale_snow1: FloatArray? = null
            var x_snow2: FloatArray? = null
            var y_snow2: FloatArray? = null
            var scale_snow2: FloatArray? = null
            var x_snow3: FloatArray? = null
            var y_snow3: FloatArray? = null
            var scale_snow3: FloatArray? = null
            var thunder_start: IntArray? = null
            var thunder_duration: IntArray? = null
            var thunder_num: IntArray? = null
            var thunder_scale: FloatArray? = null
            var thunder_x: FloatArray? = null
            var thunder_y: FloatArray? = null
            var cloud_light_start: IntArray? = null
            var cloud_light_num: IntArray? = null
            var cloud_light_pos: IntArray? = null
            var cloud_light_duration: IntArray? = null
            var raindrop1_start: IntArray? = null
            var raindrop1_x: FloatArray? = null
            var raindrop1_y: FloatArray? = null
            var raindrop1_scale: FloatArray? = null
            var raindrop2_start: IntArray? = null
            var raindrop2_x: FloatArray? = null
            var raindrop2_y: FloatArray? = null
            var raindrop2_scale: FloatArray? = null
            var nMeteorInitCnt: Int = 0
            var bGLES11: Boolean = true
            private const val FRAME_TIMING_LOG_SAMPLE_WINDOW = 120
            private const val FRAME_COUNTER_WRAP = 2002
            private val TIME_BASED_ANIMATION_REFERENCE_FPS: Float =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TARGET_FPS_DEFAULT.toFloat()
            private const val MAX_TIME_BASED_ANIMATION_STEP_NS = 250000000L

            fun setEnableLogo(enable: Boolean) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.CSPRenderer.Companion.bEnableLogo =
                    enable
            }
        }
    }

    companion object {
        val ACTION_FORCE_WEATHER_REFRESH: String =
            "com.BalancedLight.WindyWeather.action.FORCE_WEATHER_REFRESH"
        val ACTION_SET_WEATHER_REFRESH_INTERVAL: String =
            "com.BalancedLight.WindyWeather.action.SET_WEATHER_REFRESH_INTERVAL"
        val ACTION_SET_TARGET_FPS: String = "com.BalancedLight.WindyWeather.action.SET_TARGET_FPS"
        val ACTION_SET_POWER_SAVE_TARGET_FPS: String =
            "com.BalancedLight.WindyWeather.action.SET_POWER_SAVE_TARGET_FPS"
        val ACTION_SET_FRAME_RATE_DEPENDENT_ANIMATION: String =
            "com.BalancedLight.WindyWeather.action.SET_FRAME_RATE_DEPENDENT_ANIMATION"
        val ACTION_SET_GROUND_PARALLAX: String =
            "com.BalancedLight.WindyWeather.action.SET_GROUND_PARALLAX"
        val ACTION_DEBUG_SET_FORCED_SCENE: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_FORCED_SCENE"
        val ACTION_DEBUG_SET_FORCED_WEATHER_CODE: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_FORCED_WEATHER_CODE"
        val ACTION_DEBUG_SET_OLD_NIGHT_EFFECT: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_OLD_NIGHT_EFFECT"
        val ACTION_DEBUG_SET_CITY_NAME_VISIBLE: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_CITY_NAME_VISIBLE"
        val ACTION_DEBUG_SET_LEGACY_LOGO_VISIBLE: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_LEGACY_LOGO_VISIBLE"
        val ACTION_DEBUG_SET_DAY_NIGHT_MODE: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_DAY_NIGHT_MODE"
        val ACTION_DEBUG_SET_HIDE_THUNDER_RAINDROPS_LEGACY: String =
            "com.BalancedLight.WindyWeather.action.DEBUG_SET_HIDE_THUNDER_RAINDROPS_LEGACY"
        val ACTION_SET_LEGACY_FREEZING_FROST: String =
            "com.BalancedLight.WindyWeather.action.SET_LEGACY_FREEZING_FROST"
        val ACTION_SET_LEGACY_HUMIDITY_WATERDROP: String =
            "com.BalancedLight.WindyWeather.action.SET_LEGACY_HUMIDITY_WATERDROP"
        val ACTION_SET_LEGACY_DELAY_SNOW_GROUND: String =
            "com.BalancedLight.WindyWeather.action.SET_LEGACY_DELAY_SNOW_GROUND"
        val ACTION_SET_LEGACY_CLASSIC_WATERMARK: String =
            "com.BalancedLight.WindyWeather.action.SET_LEGACY_CLASSIC_WATERMARK"
        val ACTION_SET_TEXTURE_PACK: String =
            "com.BalancedLight.WindyWeather.action.SET_TEXTURE_PACK"
        val ACTION_SET_WEATHER_SOURCE_MODE: String =
            "com.BalancedLight.WindyWeather.action.SET_WEATHER_SOURCE_MODE"
        val ACTION_SET_AEROWEATHER_REFRESH_SYNC: String =
            "com.BalancedLight.WindyWeather.action.SET_AEROWEATHER_REFRESH_SYNC"
        val ACTION_SAMSUNG_PROVIDER_CHANGED_INTERNAL: String =
            "com.BalancedLight.WindyWeather.action.SAMSUNG_PROVIDER_CHANGED_INTERNAL"
        val ACTION_AEROWEATHER_SYNC_REFRESH: String =
            "com.BalancedLight.WindyWeather.action.AEROWEATHER_SYNC_REFRESH"
        val EXTRA_WEATHER_REFRESH_INTERVAL_MINUTES: String =
            "extra_weather_refresh_interval_minutes"
        val EXTRA_TARGET_FPS: String = "extra_target_fps"
        val EXTRA_POWER_SAVE_TARGET_FPS: String = "extra_power_save_target_fps"
        val EXTRA_FRAME_RATE_DEPENDENT_ANIMATION_ENABLED: String =
            "extra_frame_rate_dependent_animation_enabled"
        val EXTRA_GROUND_PARALLAX_ENABLED: String = "extra_ground_parallax_enabled"
        val EXTRA_DEBUG_FORCED_SCENE: String = "extra_debug_forced_scene"
        val EXTRA_DEBUG_FORCED_WEATHER_CODE: String = "extra_debug_forced_weather_code"
        val EXTRA_OLD_NIGHT_EFFECT_ENABLED: String = "extra_old_night_effect_enabled"
        val EXTRA_CITY_NAME_VISIBLE: String = "extra_city_name_visible"
        val EXTRA_LEGACY_LOGO_VISIBLE: String = "extra_legacy_logo_visible"
        val EXTRA_DAY_NIGHT_MODE: String = "extra_day_night_mode"
        val EXTRA_HIDE_THUNDER_RAINDROPS_LEGACY: String = "extra_hide_thunder_raindrops_legacy"
        val EXTRA_LEGACY_FREEZING_FROST_ENABLED: String = "extra_legacy_freezing_frost_enabled"
        val EXTRA_LEGACY_HUMIDITY_WATERDROP_ENABLED: String =
            "extra_legacy_humidity_waterdrop_enabled"
        val EXTRA_LEGACY_DELAY_SNOW_GROUND_ENABLED: String =
            "extra_legacy_delay_snow_ground_enabled"
        val EXTRA_LEGACY_CLASSIC_WATERMARK_ENABLED: String =
            "extra_legacy_classic_watermark_enabled"
        val EXTRA_TEXTURE_PACK: String = "extra_texture_pack"
        val EXTRA_WEATHER_SOURCE_MODE: String = "extra_weather_source_mode"
        val EXTRA_AEROWEATHER_REFRESH_SYNC_ENABLED: String =
            "extra_aeroweather_refresh_sync_enabled"
        val EXTRA_AEROWEATHER_SYNC_SOURCE_PACKAGE: String = "extra_aeroweather_sync_source_package"
        val PREF_KEY_WEATHER_REFRESH_INTERVAL_MINUTES: String =
            "pref_weather_refresh_interval_minutes"
        val PREF_KEY_TARGET_FPS: String = "pref_target_fps"
        val PREF_KEY_POWER_SAVE_TARGET_FPS: String = "pref_power_save_target_fps"
        val PREF_KEY_FRAME_RATE_DEPENDENT_ANIMATION: String = "pref_frame_rate_dependent_animation"
        val PREF_KEY_GROUND_PARALLAX: String = "pref_ground_parallax"
        val PREF_KEY_DEBUG_FORCED_SCENE: String = "debug_forced_scene"
        val PREF_KEY_DEBUG_FORCED_WEATHER_CODE: String = "debug_forced_weather_code"
        val PREF_KEY_OLD_NIGHT_EFFECT: String = "pref_old_night_effect"
        val PREF_KEY_SHOW_CITY_NAME: String = "pref_show_city_name"
        val PREF_KEY_SHOW_LEGACY_LOGO: String = "pref_show_legacy_logo"
        val PREF_KEY_DEBUG_DAY_NIGHT_MODE: String = "debug_day_night_mode"
        val PREF_KEY_HIDE_THUNDER_RAINDROPS_LEGACY: String = "pref_hide_thunder_raindrops_legacy"
        val PREF_KEY_LEGACY_FREEZING_FROST: String = "pref_legacy_freezing_frost"
        val PREF_KEY_LEGACY_HUMIDITY_WATERDROP: String = "pref_legacy_humidity_waterdrop"
        val PREF_KEY_LEGACY_DELAY_SNOW_GROUND: String = "pref_legacy_delay_snow_ground"
        val PREF_KEY_LEGACY_CLASSIC_WATERMARK: String = "pref_legacy_classic_watermark"
        val PREF_KEY_TEXTURE_PACK: String = "pref_texture_pack"
        val PREF_KEY_WEATHER_SOURCE_MODE: String = "pref_weather_source_mode"
        val PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH: String = "pref_sync_with_aeroweather_refresh"
        val PREF_KEY_LAST_SAMSUNG_OBSERVER_TRIGGER_MS: String = "last_samsung_observer_trigger_ms"
        val PREF_KEY_LAST_HYBRID_IMMEDIATE_REFRESH_MS: String = "last_hybrid_immediate_refresh_ms"
        val PREF_KEY_LAST_SAMSUNG_NUDGE_MS: String = "last_samsung_nudge_ms"
        val PREF_KEY_LAST_SYNC_ORIGIN: String = "last_sync_origin"
        val PREF_KEY_SAMSUNG_NUDGE_SUPPRESS_UNTIL_MS: String = "samsung_nudge_suppress_until_ms"
        val WEATHER_SOURCE_OPEN_METEO: String = "open_meteo"
        val WEATHER_SOURCE_SAMSUNG_DEVICE: String = "samsung_device"
        val ORIGIN_SAMSUNG_OBSERVER: String = "samsung_observer"
        val ORIGIN_AEROWEATHER_SYNC: String = "aeroweather_sync"
        val ORIGIN_APP_REFRESH: String = "app_refresh"
        val ORIGIN_MANUAL_USER: String = "manual_user"
        private val TRUSTED_AEROWEATHER_PACKAGE = "com.BalancedLight.AeroWeather"
        private val PREF_KEY_LAST_WEATHER_CONDITION = "last_weather_conditon_num_2"
        private val PREF_KEY_LAST_PREV_WEATHER_CONDITION = "last_prev_weather_conditon_num_2"
        val TEXTURE_PACK_HQ: String = "hq"
        val TEXTURE_PACK_LEGACY: String = "legacy"
        const val DAY_NIGHT_MODE_AUTO: Int = 0
        const val DAY_NIGHT_MODE_FORCE_DAY: Int = 1
        const val DAY_NIGHT_MODE_FORCE_NIGHT: Int = 2
        const val WEATHER_REFRESH_OFF_MINUTES: Int = 0
        const val WEATHER_REFRESH_MIN_MINUTES: Int = 10
        const val WEATHER_REFRESH_MAX_MINUTES: Int = 360
        const val WEATHER_REFRESH_DEFAULT_MINUTES: Int = 15
        const val TARGET_FPS_MIN: Int = 15
        const val TARGET_FPS_MAX: Int = 60
        const val TARGET_FPS_DEFAULT: Int = 30
        const val TARGET_FPS_POWER_SAVE_DEFAULT: Int = 15
        const val FRAME_RATE_DEPENDENT_ANIMATION_DEFAULT: Boolean = true
        const val GROUND_PARALLAX_DEFAULT: Boolean = true
        const val AEROWEATHER_REFRESH_SYNC_DEFAULT: Boolean = true
        var mMainService: SecretWallpaperService? = null
        var mWallpaperEngine: CSPWallpaperEngine? = null
        private var mbImageSetChange = false
        private var mTimeTickReceiver: TimeTickReceiver? = null
        private var mPref: SharedPreferences? = null
        private var mConnManager: ConnectivityManager? = null
        private const val mfInterval: Long = 0
        private var mnCurWeather: Int =
            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
        private var mnPrevWeather: Int =
            com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal
        private var mnCurrentWeatherCode = 1
        private var mCityName: String? = ""
        private var mnCurMoonPhase = 0
        private const val mnPrevMoonPhase = 0
        private var mbCityNameChange = false
        private var mCityNameBmp: Bitmap? = null
        private val moonResouceID = intArrayOf(
            R.drawable.moon_00,
            R.drawable.moon_01,
            R.drawable.moon_01,
            R.drawable.moon_01,
            R.drawable.moon_01,
            R.drawable.moon_02,
            R.drawable.moon_02,
            R.drawable.moon_02,
            R.drawable.moon_02,
            R.drawable.moon_03,
            R.drawable.moon_03,
            R.drawable.moon_03,
            R.drawable.moon_03,
            R.drawable.moon_04,
            R.drawable.moon_05,
            R.drawable.moon_05,
            R.drawable.moon_05,
            R.drawable.moon_05,
            R.drawable.moon_06,
            R.drawable.moon_06,
            R.drawable.moon_06,
            R.drawable.moon_06,
            R.drawable.moon_07,
            R.drawable.moon_07,
            R.drawable.moon_07,
            R.drawable.moon_07,
            R.drawable.moon_00
        )
        private val moonIsReflect = booleanArrayOf(
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        )
        private val WEATHER_CACHE_STALE_MS = 6L * 60L * 60L * 1000L
        private var sMaxTextureSize = 0
        private val sTextureResourceCache: ConcurrentHashMap<String, Int> =
            ConcurrentHashMap()

        // Keep weather-code decisions in one place so new Open-Meteo codes are easy to map.
        private fun isMostlyClearCode(weatherCode: Int): Boolean {
            return weatherCode == 1
        }

        // Freezing fog uses the fog scene plus a frost overlay, not the full snow scene.
        private fun isFreezingFogCode(weatherCode: Int): Boolean {
            return weatherCode == 48
        }

        private fun isSupportedSceneOrdinal(sceneOrdinal: Int): Boolean {
            return sceneOrdinal >= com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D1_CLEAR.ordinal && sceneOrdinal <= com.BalancedLight.WindyWeather.SecretWallpaperService.WeatherConditions.D10_MOSTLY_CLEAR.ordinal
        }

        var surfaceCreated: Boolean
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbSurfaceCreated
            set(bCreated) {
                if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService != null) {
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mMainService!!.mbSurfaceCreated =
                        bCreated
                } else {
                    Log.e("WindyWeather", "mMainService null")
                }
            }

        private fun normalizeSyncOrigin(origin: String?): String {
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_SAMSUNG_OBSERVER.equals(
                    origin
                )
            ) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_SAMSUNG_OBSERVER
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_AEROWEATHER_SYNC.equals(
                    origin
                )
            ) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_AEROWEATHER_SYNC
            }
            if (com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER.equals(
                    origin
                )
            ) {
                return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_MANUAL_USER
            }
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
        }

        private fun getSyncPrefs(context: Context): SharedPreferences {
            val appContext: Context = context.getApplicationContext()
            return appContext.getSharedPreferences(
                "com.BalancedLight.WindyWeather",
                Context.MODE_PRIVATE
            )
        }

        fun setLastSamsungObserverTriggerMs(context: Context, value: Long) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(context)
                .edit().putLong(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SAMSUNG_OBSERVER_TRIGGER_MS,
                    value
                ).apply()
        }

        fun getLastSamsungObserverTriggerMs(context: Context): Long {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getLong(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SAMSUNG_OBSERVER_TRIGGER_MS,
                0L
            )
        }

        fun setLastHybridImmediateRefreshMs(context: Context, value: Long) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(context)
                .edit().putLong(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_HYBRID_IMMEDIATE_REFRESH_MS,
                    value
                ).apply()
        }

        fun getLastHybridImmediateRefreshMs(context: Context): Long {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getLong(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_HYBRID_IMMEDIATE_REFRESH_MS,
                0L
            )
        }

        fun setLastSamsungNudgeMs(context: Context, value: Long) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(context)
                .edit().putLong(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SAMSUNG_NUDGE_MS,
                    value
                ).apply()
        }

        fun getLastSamsungNudgeMs(context: Context): Long {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getLong(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SAMSUNG_NUDGE_MS,
                0L
            )
        }

        fun setSamsungNudgeSuppressUntilMs(context: Context, value: Long) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(context)
                .edit().putLong(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SAMSUNG_NUDGE_SUPPRESS_UNTIL_MS,
                    value
                ).apply()
        }

        fun getSamsungNudgeSuppressUntilMs(context: Context): Long {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getLong(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SAMSUNG_NUDGE_SUPPRESS_UNTIL_MS,
                0L
            )
        }

        fun setLastSyncOrigin(context: Context, origin: String?) {
            com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(context)
                .edit().putString(
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SYNC_ORIGIN,
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.normalizeSyncOrigin(
                        origin
                    )
                ).apply()
        }

        fun getLastSyncOrigin(context: Context): String {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getString(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_LAST_SYNC_ORIGIN,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
            ) ?: com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.ORIGIN_APP_REFRESH
        }

        fun isAeroWeatherRefreshSyncEnabled(context: Context): Boolean {
            return com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.getSyncPrefs(
                context
            ).getBoolean(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.AEROWEATHER_REFRESH_SYNC_DEFAULT
            )
        }

        fun resolveTextureResource(context: Context?, textureId: Int): Int {
            if (context == null || textureId == 0) {
                return textureId
            }
            val pref: SharedPreferences =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.mPref ?: context.getSharedPreferences(
                    "com.BalancedLight.WindyWeather",
                    Context.MODE_PRIVATE
                )
            val texturePack: String? = pref.getString(
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.PREF_KEY_TEXTURE_PACK,
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_HQ
            )
            val cacheKey = texturePack.toString() + ":" + textureId
            val cachedResId: Int? =
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sTextureResourceCache.get(
                    cacheKey
                )
            if (cachedResId != null) {
                return cachedResId
            }
            if (!com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.TEXTURE_PACK_LEGACY.equals(
                    texturePack
                )
            ) {
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sTextureResourceCache.put(
                    cacheKey,
                    textureId
                )
                return textureId
            }
            try {
                val baseName: String? = context.resources.getResourceEntryName(textureId)
                val resolved: Int =
                    com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.resolveTextureId(
                        context,
                        baseName,
                        textureId
                    )
                if (resolved == textureId) {
                    Log.d("WindyWeather", "Legacy texture fallback to HQ for " + baseName)
                }
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sTextureResourceCache.put(
                    cacheKey,
                    resolved
                )
                return resolved
            } catch (e: Exception) {
                Log.w(
                    "WindyWeather",
                    "Unable to resolve legacy texture for id=" + textureId,
                    e
                )
                com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sTextureResourceCache.put(
                    cacheKey,
                    textureId
                )
                return textureId
            }
        }

        private fun resolveTextureId(
            context: Context?,
            baseName: String?,
            fallbackResId: Int
        ): Int {
            if (context == null || baseName == null || baseName.isEmpty()) {
                return fallbackResId
            }
            val legacyName = baseName.toString() + "_lq"
            val legacyResId: Int = context.resources
                .getIdentifier(legacyName, "drawable", context.packageName)
            if (legacyResId != 0) {
                Log.d(
                    "WindyWeather",
                    "Resolved legacy texture " + baseName + " -> " + legacyName
                )
                return legacyResId
            }
            return fallbackResId
        }

        val maxTextureSize: Int
            get() = com.BalancedLight.WindyWeather.SecretWallpaperService.Companion.sMaxTextureSize
    }
}





